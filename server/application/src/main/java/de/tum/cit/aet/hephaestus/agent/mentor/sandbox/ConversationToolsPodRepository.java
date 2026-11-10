package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The conversation tools Pods of the installation. Every bind is fenced on the turn's live lease, on the database
 * clock, so any server can bind, and an attempt that lost its lease binds nothing.
 */
@Repository
@WorkspaceAgnostic("Tools Pods are installation-wide; a bound row is fenced on its turn's run and names its workspace")
public interface ConversationToolsPodRepository extends JpaRepository<ConversationToolsPod, String> {

    /** The fence of every bind: the attempt holds its turn's run under its claim, with a live lease. */
    String LIVE_ATTEMPT = """
            SELECT t.workspace_id, t.id, t.attempt FROM mentor_turn t
              JOIN agent_run r ON r.id = t.run_id AND r.workspace_id = t.workspace_id AND r.state = 'LEASED'
                               AND r.lease_epoch = t.attempt AND r.leased_until > clock_timestamp()
             WHERE t.id = :turnId AND t.workspace_id = :workspaceId AND t.attempt = :attempt
               AND t.run_id = :runId AND t.status = 'RUNNING'
            """;

    /** A Pod that the pool starts: no attempt holds it yet. */
    @Modifying
    @Query(value = """
            INSERT INTO conversation_tools_pod (name, nonce, created_at, revoked)
            VALUES (:name, :nonce, clock_timestamp(), false)
            """, nativeQuery = true)
    int insertPooled(@Param("name") String name, @Param("nonce") UUID nonce);

    /**
     * A Pod started for one attempt, when the pool had none ready.
     *
     * @return 1, or 0 when the attempt lost its lease or already holds a Pod
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversation_tools_pod (name, nonce, created_at, workspace_id, turn_id, attempt, revoked)
            SELECT :name, :nonce, clock_timestamp(), live.workspace_id, live.id, live.attempt, false
              FROM (""" + LIVE_ATTEMPT + """
            ) live
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertBound(
            @Param("name") String name,
            @Param("nonce") UUID nonce,
            @Param("workspaceId") long workspaceId,
            @Param("turnId") UUID turnId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId);

    /** The Pod's tools program runs at this address. */
    @Modifying
    @Query(value = "UPDATE conversation_tools_pod SET address = :address WHERE name = :name", nativeQuery = true)
    int started(@Param("name") String name, @Param("address") String address);

    /** The Pod that this attempt holds, revoked or not. */
    @Query(value = """
            SELECT * FROM conversation_tools_pod WHERE turn_id = :turnId AND attempt = :attempt
            """, nativeQuery = true)
    Optional<ConversationToolsPod> findBound(@Param("turnId") UUID turnId, @Param("attempt") int attempt);

    /**
     * Binds the oldest ready pooled Pod that is younger than {@code poolLifeSeconds} to the attempt. Two servers that
     * bind at once take different Pods.
     *
     * @return the Pod's name, or empty when the pool has no ready Pod, the attempt holds one, or it lost its lease
     */
    @Query(value = """
            WITH live AS (""" + LIVE_ATTEMPT + """
            ), free AS (
              SELECT p.name FROM conversation_tools_pod p
               WHERE p.turn_id IS NULL AND p.address IS NOT NULL
                 AND p.created_at > clock_timestamp() - make_interval(secs => :poolLifeSeconds)
                 AND NOT EXISTS (SELECT 1 FROM conversation_tools_pod b WHERE b.turn_id = :turnId AND b.attempt = :attempt)
               ORDER BY p.created_at
               LIMIT 1
               FOR UPDATE SKIP LOCKED
            ), bound AS (
              UPDATE conversation_tools_pod p
                 SET workspace_id = live.workspace_id, turn_id = live.id, attempt = live.attempt
                FROM live, free
               WHERE p.name = free.name
              RETURNING p.name
            )
            SELECT name FROM bound
            """, nativeQuery = true)
    Optional<String> bindPooled(
            @Param("workspaceId") long workspaceId,
            @Param("turnId") UUID turnId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId,
            @Param("poolLifeSeconds") long poolLifeSeconds);

    /** Pooled Pods that a bind may still take, started or starting. */
    @Query(value = """
            SELECT count(*) FROM conversation_tools_pod
             WHERE turn_id IS NULL AND created_at > clock_timestamp() - make_interval(secs => :poolLifeSeconds)
            """, nativeQuery = true)
    long countPooled(@Param("poolLifeSeconds") long poolLifeSeconds);

    /** One server refills the pool at a time: it holds this lock until its transaction ends. */
    @Query(
            value = "SELECT pg_try_advisory_xact_lock(hashtextextended('conversation-tools-pool', 0))",
            nativeQuery = true)
    boolean lockPool();

    /**
     * Revokes the Pods of the workspace's turns, in the transaction that changed what they may read: each attempt's
     * next bind answers {@code CONTEXT_CHANGED} once.
     */
    @Modifying
    @Query(value = """
            UPDATE conversation_tools_pod SET revoked = true
             WHERE workspace_id = :workspaceId AND turn_id IS NOT NULL AND NOT revoked
            """, nativeQuery = true)
    int revokeWorkspace(@Param("workspaceId") long workspaceId);

    /**
     * Removes the row of a Pod that the attempt holds, so its next bind gets another one. A revoked row stays until a
     * bind answers {@code CONTEXT_CHANGED} with it: a discard of a Pod that the revocation removed must not hide that.
     */
    @Modifying
    @Query(value = """
            DELETE FROM conversation_tools_pod
             WHERE name = :name AND turn_id = :turnId AND attempt = :attempt AND revoked = :revoked
            """, nativeQuery = true)
    int releaseBound(
            @Param("name") String name,
            @Param("turnId") UUID turnId,
            @Param("attempt") int attempt,
            @Param("revoked") boolean revoked);

    /** The revoked Pods whose attempt has not bound again: their Jobs must be gone, whatever a delete answered. */
    @Query(value = "SELECT name FROM conversation_tools_pod WHERE revoked", nativeQuery = true)
    List<String> findRevokedNames();

    /**
     * Removes the row of the attempt's Pod when the attempt ends.
     *
     * @return the Pod, whose Job the caller deletes
     */
    @Query(value = """
            WITH ended AS (
              DELETE FROM conversation_tools_pod WHERE turn_id = :turnId AND attempt = :attempt
              RETURNING name
            )
            SELECT name FROM ended
            """, nativeQuery = true)
    List<String> releaseAttempt(@Param("turnId") UUID turnId, @Param("attempt") int attempt);

    /**
     * The rows that no longer name a Pod anyone may use: bound to an attempt without a live lease, pooled past the
     * pool's life, or never started within {@code startSeconds}.
     */
    @Query(value = """
            WITH gone AS (
              DELETE FROM conversation_tools_pod p
               WHERE (p.turn_id IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM mentor_turn t
                          JOIN agent_run r ON r.id = t.run_id AND r.workspace_id = t.workspace_id AND r.state = 'LEASED'
                                           AND r.lease_epoch = t.attempt AND r.leased_until > clock_timestamp()
                         WHERE t.workspace_id = p.workspace_id AND t.id = p.turn_id AND t.attempt = p.attempt
                           AND t.status IN ('RUNNING', 'STOPPED')))
                  OR (p.turn_id IS NULL AND p.created_at <= clock_timestamp() - make_interval(secs => :poolLifeSeconds))
                  OR (p.address IS NULL AND p.created_at <= clock_timestamp() - make_interval(secs => :startSeconds))
              RETURNING p.name
            )
            SELECT name FROM gone
            """, nativeQuery = true)
    List<String> deleteUnused(@Param("poolLifeSeconds") long poolLifeSeconds, @Param("startSeconds") long startSeconds);

    /** Every row's name, so a Job without one goes. */
    @Query(value = "SELECT name FROM conversation_tools_pod", nativeQuery = true)
    List<String> findAllNames();
}
