package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every write of a sandbox is one conditional statement on the database clock, so two server replicas that act on the
 * same row change it once. A sandbox is live while it is {@code REQUESTED} or {@code READY}, and a subject has at most
 * one live sandbox ({@code uq_agent_sandbox_live_subject}).
 */
interface AgentSandboxRepository extends JpaRepository<AgentSandbox, UUID> {

    /** A sandbox as the owner and the reaper read it. */
    interface SandboxRow {
        UUID getId();

        long getWorkspaceId();

        String getSubjectKey();

        String getKind();

        String getClaimName();

        String getState();

        Instant getShutdownAt();
    }

    /** A sandbox that the reaper took back, and why. */
    interface RetiredRow extends SandboxRow {
        /** {@code expired}, {@code start_limit} or {@code idle}. */
        String getReason();
    }

    /** Spaces on both ends: a text block drops the trailing space of the {@code RETURNING} before it. */
    String COLUMNS = " id, workspace_id AS \"workspaceId\", subject_key AS \"subjectKey\", kind,"
            + " claim_name AS \"claimName\", state, shutdown_at AS \"shutdownAt\" ";

    /** Requests the subject's sandbox; empty when the subject already has a live one. */
    @Query(value = """
            INSERT INTO agent_sandbox (id, workspace_id, subject_key, kind, claim_name, shutdown_at)
            VALUES (:id, :workspaceId, :subjectKey, :kind, :claimName, :shutdownAt)
            ON CONFLICT (workspace_id, subject_key) WHERE state IN ('REQUESTED', 'READY') DO NOTHING
            RETURNING""" + COLUMNS, nativeQuery = true)
    Optional<SandboxRow> request(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("subjectKey") String subjectKey,
            @Param("kind") String kind,
            @Param("claimName") String claimName,
            @Param("shutdownAt") Instant shutdownAt);

    /** Physical capacity of a kind, including copies whose deletion must finish before their slot is free. */
    @WorkspaceAgnostic("The dispatch counts the live sandboxes of every workspace against one limit")
    @Query(value = """
            SELECT count(*) FROM agent_sandbox WHERE kind = :kind AND state IN ('REQUESTED', 'READY', 'DELETING')
            """, nativeQuery = true)
    int countLive(@Param("kind") String kind);

    /** Retires the oldest warm conversation copy under pressure, never a subject with queued or leased work. */
    @WorkspaceAgnostic("Dispatch reclaims idle conversation capacity across workspaces; each row carries its workspace")
    @Query(value = """
            WITH picked AS (
              SELECT s.id FROM agent_sandbox s
               WHERE s.kind = 'CONVERSATION' AND s.state = 'READY'
                 AND NOT EXISTS (SELECT 1 FROM agent_run r
                                  WHERE r.workspace_id = s.workspace_id AND r.concurrency_key = s.subject_key
                                    AND r.state <> 'ENDED')
               ORDER BY s.last_used_at, s.created_at, s.id
               LIMIT 1 FOR UPDATE OF s SKIP LOCKED)
            UPDATE agent_sandbox s SET state = 'DELETING' FROM picked WHERE s.id = picked.id
            RETURNING s.id, s.workspace_id AS "workspaceId", s.subject_key AS "subjectKey", s.kind,
                      s.claim_name AS "claimName", s.state, s.shutdown_at AS "shutdownAt"
            """, nativeQuery = true)
    Optional<SandboxRow> retireIdleConversation();

    @Query(value = "SELECT" + COLUMNS + """
             FROM agent_sandbox WHERE workspace_id = :workspaceId AND subject_key = :subjectKey
              AND state IN ('REQUESTED', 'READY')
            """, nativeQuery = true)
    Optional<SandboxRow> findLive(@Param("workspaceId") long workspaceId, @Param("subjectKey") String subjectKey);

    /** A sandbox's first call to the gateway: it becomes {@code READY}, while it is live and before its shutdown. */
    @Query(value = """
            UPDATE agent_sandbox SET state = 'READY', last_used_at = clock_timestamp()
             WHERE id = :id AND workspace_id = :workspaceId AND subject_key = :subjectKey
               AND state = 'REQUESTED' AND shutdown_at > clock_timestamp()
            RETURNING""" + COLUMNS, nativeQuery = true)
    Optional<SandboxRow> markReady(
            @Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("subjectKey") String subjectKey);

    /** A {@code READY} sandbox before its shutdown; a read, so a call that leases nothing is no use of the sandbox. */
    @Query(value = "SELECT" + COLUMNS + """
             FROM agent_sandbox WHERE id = :id AND workspace_id = :workspaceId AND subject_key = :subjectKey
              AND state = 'READY' AND shutdown_at > clock_timestamp()
            """, nativeQuery = true)
    Optional<SandboxRow> findReady(
            @Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("subjectKey") String subjectKey);

    /**
     * Holds a live sandbox until the caller's transaction ends, so no reaper or release takes it back meanwhile; the
     * reaper skips a row that this holds.
     */
    @Query(value = "SELECT" + COLUMNS + """
             FROM agent_sandbox WHERE id = :id AND workspace_id = :workspaceId AND subject_key = :subjectKey
              AND state IN ('REQUESTED', 'READY') AND shutdown_at > clock_timestamp()
            FOR SHARE
            """, nativeQuery = true)
    Optional<SandboxRow> lockLive(
            @Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("subjectKey") String subjectKey);

    /** The first claim of a run is cold only when this physical sandbox served no earlier claim. */
    interface RunStart {
        Instant getStartedAt();

        boolean getCold();
    }

    @Query(value = """
            SELECT r.started_at AS "startedAt", NOT EXISTS (
                     SELECT 1 FROM agent_run previous
                      WHERE previous.workspace_id = s.workspace_id AND previous.concurrency_key = s.subject_key
                        AND previous.started_at >= s.created_at
                        AND (previous.id <> r.id OR r.lease_epoch > 1)) AS cold
              FROM agent_sandbox s
              JOIN agent_run r ON r.workspace_id = s.workspace_id AND r.concurrency_key = s.subject_key
             WHERE s.id = :sandboxId AND s.workspace_id = :workspaceId AND s.subject_key = :subjectKey
               AND r.id = :runId AND r.lease_epoch = :epoch AND r.started_at IS NOT NULL
            """, nativeQuery = true)
    Optional<RunStart> findRunStart(
            @Param("sandboxId") UUID sandboxId,
            @Param("workspaceId") long workspaceId,
            @Param("subjectKey") String subjectKey,
            @Param("runId") UUID runId,
            @Param("epoch") int epoch);

    /** The sandbox leased a run of its subject: the idle limits count from now. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_sandbox SET last_used_at = clock_timestamp()
             WHERE id = :id AND workspace_id = :workspaceId AND state IN ('REQUESTED', 'READY')
            """, nativeQuery = true)
    int used(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Takes back this one sandbox, if it is still live; a newer sandbox of its subject stays. */
    @Query(value = """
            UPDATE agent_sandbox SET state = 'DELETING'
             WHERE id = :id AND workspace_id = :workspaceId AND state IN ('REQUESTED', 'READY')
            RETURNING""" + COLUMNS, nativeQuery = true)
    Optional<SandboxRow> releaseSandbox(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Takes back this physical sandbox, including a deletion that must be retried. */
    @Query(value = """
            UPDATE agent_sandbox SET state = 'DELETING'
             WHERE id = :id AND workspace_id = :workspaceId AND state IN ('REQUESTED', 'READY', 'DELETING')
            RETURNING""" + COLUMNS, nativeQuery = true)
    Optional<SandboxRow> releaseCopy(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Takes back the subject's live sandbox, as an erasure or a change of access does. */
    @Query(value = """
            UPDATE agent_sandbox SET state = 'DELETING'
             WHERE workspace_id = :workspaceId AND subject_key = :subjectKey AND state IN ('REQUESTED', 'READY')
            RETURNING""" + COLUMNS, nativeQuery = true)
    Optional<SandboxRow> release(@Param("workspaceId") long workspaceId, @Param("subjectKey") String subjectKey);

    /** Physical conversation copies still owned by a selected workspace, including failed deletions. */
    @Query(
            value = "SELECT" + COLUMNS + " FROM agent_sandbox WHERE workspace_id = :workspaceId"
                    + " AND kind = 'CONVERSATION' AND state IN ('REQUESTED', 'READY', 'DELETING') ORDER BY id",
            nativeQuery = true)
    List<SandboxRow> findConversationCopies(@Param("workspaceId") long workspaceId);

    /** Revokes conversation credentials in the transaction that changes access. The reaper deletes claims. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_sandbox SET state = 'DELETING'
             WHERE workspace_id = :workspaceId AND kind = 'CONVERSATION' AND state IN ('REQUESTED', 'READY')
            """, nativeQuery = true)
    int revokeConversations(@Param("workspaceId") long workspaceId);

    /**
     * The reaper's one rule. A live sandbox is taken back when its shutdown time passed, when it did not call the
     * gateway within the start limit, or when its subject has no queued or leased run and it leased no run for its
     * kind's idle limit. A sandbox that another replica takes back at the same moment is skipped.
     */
    @WorkspaceAgnostic("The reaper takes back sandboxes of every workspace; each row carries its workspace")
    @Query(value = """
            WITH picked AS (
              SELECT x.id,
                     CASE WHEN x.shutdown_at <= clock_timestamp() THEN 'expired'
                          WHEN x.state = 'REQUESTED'
                               AND x.created_at <= clock_timestamp() - make_interval(secs => :startSeconds)
                            THEN 'start_limit'
                          ELSE 'idle' END AS reason
                FROM agent_sandbox x
               WHERE x.state IN ('REQUESTED', 'READY')
                 AND (x.shutdown_at <= clock_timestamp()
                      OR (x.state = 'REQUESTED'
                          AND x.created_at <= clock_timestamp() - make_interval(secs => :startSeconds))
                      OR (x.last_used_at <= clock_timestamp() - make_interval(secs =>
                              CASE WHEN x.kind = 'CONVERSATION' THEN :conversationIdleSeconds ELSE :idleSeconds END)
                          AND NOT EXISTS (SELECT 1 FROM agent_run r
                                           WHERE r.workspace_id = x.workspace_id
                                             AND r.concurrency_key = x.subject_key AND r.state <> 'ENDED')))
               LIMIT :batch FOR UPDATE OF x SKIP LOCKED)
            UPDATE agent_sandbox s SET state = 'DELETING' FROM picked WHERE s.id = picked.id
            RETURNING s.id, s.workspace_id AS "workspaceId", s.subject_key AS "subjectKey", s.kind,
                      s.claim_name AS "claimName", s.state, s.shutdown_at AS "shutdownAt", picked.reason
            """, nativeQuery = true)
    List<RetiredRow> retire(
            @Param("startSeconds") long startSeconds,
            @Param("idleSeconds") long idleSeconds,
            @Param("conversationIdleSeconds") long conversationIdleSeconds,
            @Param("batch") int batch);

    /** The sandboxes whose claims are being deleted, oldest first, including the ones whose earlier delete failed. */
    @WorkspaceAgnostic("The reaper deletes the claims of every workspace; each row carries its workspace")
    @Query(value = "SELECT" + COLUMNS + """
             FROM agent_sandbox WHERE state = 'DELETING' ORDER BY created_at, id LIMIT :batch
            """, nativeQuery = true)
    List<SandboxRow> findDeleting(@Param("batch") int batch);

    /** Deletes a sandbox whose claim is gone. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM agent_sandbox WHERE id = :id AND workspace_id = :workspaceId AND state = 'DELETING'
            """, nativeQuery = true)
    int deleteGone(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Which of these claims a sandbox row names, in any state. */
    @WorkspaceAgnostic("The reaper compares every claim of the sandbox namespace with the rows of every workspace")
    @Query(value = "SELECT claim_name FROM agent_sandbox WHERE claim_name IN (:names)", nativeQuery = true)
    List<String> findClaimNames(@Param("names") Collection<String> names);

    /** Deletes the workspace's sandboxes. Their claims then have no row, and the reaper deletes them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM agent_sandbox WHERE workspace_id = :workspaceId", nativeQuery = true)
    int deleteAllByWorkspaceId(@Param("workspaceId") long workspaceId);
}
