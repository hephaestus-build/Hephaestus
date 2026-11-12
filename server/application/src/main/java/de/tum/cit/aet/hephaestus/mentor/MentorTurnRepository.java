package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MentorTurnRepository extends JpaRepository<MentorTurn, UUID> {

    Optional<MentorTurn> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    Optional<MentorTurn> findByWorkspaceIdAndRequestId(Long workspaceId, String requestId);

    List<MentorTurn> findByWorkspaceIdAndConversationIdAndStatus(
            Long workspaceId, UUID conversationId, MentorTurn.Status status);

    /**
     * The turn's run is leased: a host holds the turn, running, or stopped while it commits its answer. The turn's run
     * ({@code agent_run}) holds the lease; the ledger only reads whether it is held.
     */
    String HELD_RUN = """
            (EXISTS (SELECT 1 FROM AgentRun held WHERE held.id = t.runId
                       AND held.state = de.tum.cit.aet.hephaestus.agent.run.AgentRunState.LEASED))
            """;

    interface TurnStatus {
        UUID getId();

        MentorTurn.Status getStatus();
    }

    @Query(
            "SELECT t.id AS id, t.status AS status FROM MentorTurn t WHERE t.workspaceId = :workspaceId AND t.id IN :ids")
    List<TurnStatus> findStatusesByWorkspaceIdAndIdIn(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    interface RunningSlackTurn {
        UUID getId();

        int getAttempt();
    }

    /**
     * The Slack turn of a conversation that runs now, as the database holds it at this statement: a projection, so no
     * turn that the transaction loaded earlier answers with its older state. A claim lets at most one turn of a
     * conversation run.
     */
    @Query("""
            SELECT t.id AS id, t.attempt AS attempt FROM MentorTurn t
            WHERE t.workspaceId = :workspaceId AND t.conversationId = :conversationId
              AND t.status = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING
              AND t.surface = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Surface.SLACK
            """)
    Optional<RunningSlackTurn> findRunningSlackTurn(
            @Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    /** The queued and running turns of a main conversation and its threads, oldest first. */
    @Query("""
            SELECT t FROM MentorTurn t JOIN MentorConversation c ON c.id = t.conversationId
            WHERE t.workspaceId = :workspaceId AND c.workspaceId = :workspaceId
              AND (c.id = :mainId OR c.parentConversationId = :mainId)
              AND t.status IN (de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.QUEUED,
                               de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING)
            ORDER BY t.createdAt, t.id
            """)
    List<MentorTurn> findActiveInTree(@Param("workspaceId") Long workspaceId, @Param("mainId") UUID mainId);

    @Query("""
            SELECT t FROM MentorTurn t
            WHERE t.workspaceId = :workspaceId AND t.surface = :surface
              AND t.status IN (de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.QUEUED,
                               de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING)
            """)
    List<MentorTurn> findActiveByWorkspaceIdAndSurface(
            @Param("workspaceId") Long workspaceId, @Param("surface") MentorTurn.Surface surface);

    /**
     * The attempt's claim of the turn's run, live on the database clock: the run is leased under {@code epoch} and
     * its lease has not run out. Every fenced read of a turn credential joins it.
     */
    String LIVE_CLAIM = """
            JOIN agent_run r ON r.id = t.run_id AND r.workspace_id = t.workspace_id AND r.state = 'LEASED'
                             AND r.lease_epoch = :epoch AND r.leased_until > clock_timestamp()
            """;

    /** One database-clock read of the token's complete live attempt fence. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM mentor_turn t
            """ + LIVE_CLAIM + """
                WHERE t.id = :id AND t.workspace_id = :workspaceId AND t.attempt = :epoch AND t.run_id = :runId
                  AND t.status = 'RUNNING'
            )
            """, nativeQuery = true)
    boolean hasLiveLease(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("runId") UUID runId);

    /** An attempt that holds its live lease, as any worker reads it for a call of its turn credential. */
    interface HeldAttempt {
        String getStatus();

        UUID getConversationId();

        Long getAccountId();

        @Nullable
        Instant getStartedAt();

        /** When the developer stopped the turn; {@code null} while it runs. */
        @Nullable
        Instant getFinishedAt();
    }

    /**
     * The attempt, while it holds its live lease, running or stopped, on the database clock.
     *
     * @return empty when the attempt lost its lease or ended
     */
    @WorkspaceAgnostic("The turn credential names the workspace; the statement filters by it")
    @Query(value = """
            SELECT t.status AS status, t.conversation_id AS conversationId, c.account_id AS accountId,
                   t.started_at AS startedAt, t.finished_at AS finishedAt
              FROM mentor_turn t
              JOIN mentor_conversation c ON c.id = t.conversation_id AND c.workspace_id = t.workspace_id
            """ + LIVE_CLAIM + """
             WHERE t.workspace_id = :workspaceId AND t.id = :id AND t.attempt = :epoch AND t.run_id = :runId
               AND t.status IN ('RUNNING', 'STOPPED')
            """, nativeQuery = true)
    Optional<HeldAttempt> findHeldAttempt(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("epoch") int epoch,
            @Param("runId") UUID runId);

    /** An attempt that holds its lease, so an erasure can end it. */
    interface LeasedAttempt {
        UUID getId();

        Long getWorkspaceId();

        int getAttempt();

        UUID getRunId();

        @Nullable
        Instant getStartedAt();
    }

    /** The attempts whose run is leased now, in one workspace, or in every workspace when it is {@code null}. */
    @WorkspaceAgnostic("Erasure ends the leased attempts of the workspace it names, or of all after a reconnect")
    @Query(value = """
            SELECT t.id, t.workspace_id AS workspaceId, t.attempt, t.run_id AS runId, t.started_at AS startedAt
              FROM mentor_turn t
              JOIN agent_run r ON r.id = t.run_id AND r.workspace_id = t.workspace_id AND r.state = 'LEASED'
                               AND r.lease_epoch = t.attempt AND r.leased_until > clock_timestamp()
             WHERE (CAST(:workspaceId AS bigint) IS NULL OR t.workspace_id = :workspaceId)
               AND t.status IN ('RUNNING', 'STOPPED')
            """, nativeQuery = true)
    List<LeasedAttempt> findLeasedAttempts(@Param("workspaceId") @Nullable Long workspaceId);

    /**
     * Counts one load of the attempt's context folder, on the database clock, while the attempt holds its live lease
     * and has loaded fewer than {@code max} items.
     *
     * @return 1 when the load counts, 0 when the attempt reached {@code max} or lost its lease
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE mentor_turn t SET tools_loads = t.tools_loads + 1
             WHERE t.id = :id AND t.workspace_id = :workspaceId AND t.attempt = :epoch AND t.run_id = :runId
               AND t.status = 'RUNNING' AND t.tools_loads < :max
               AND EXISTS (SELECT 1 FROM agent_run r WHERE r.id = t.run_id AND r.workspace_id = t.workspace_id
                             AND r.state = 'LEASED' AND r.lease_epoch = :epoch AND r.leased_until > clock_timestamp())
            """, nativeQuery = true)
    int countToolsLoad(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("runId") UUID runId,
            @Param("max") int max);

    /** Locks the row, so a state change reads the proxy's latest totals and the proxy waits for it. */
    @WorkspaceAgnostic("Locks by primary key; callers hold the id from a workspace-scoped read or a lease")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MentorTurn t WHERE t.id = :id")
    Optional<MentorTurn> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MentorTurn t WHERE t.id = :id AND t.workspaceId = :workspaceId")
    Optional<MentorTurn> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** Locks the turn of a run, as the run's handler does before it moves the run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MentorTurn t WHERE t.runId = :runId AND t.workspaceId = :workspaceId")
    Optional<MentorTurn> lockByRun(@Param("runId") UUID runId, @Param("workspaceId") Long workspaceId);

    /**
     * Locks the turn of a run that a claim just leased, unless a stop holds it: the claim then rolls back, so the stop,
     * which locks the turn before the run, never waits for the claim's lock on the run.
     */
    @Query(
            value = "SELECT t.* FROM mentor_turn t WHERE t.run_id = :runId AND t.workspace_id = :workspaceId"
                    + " FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<MentorTurn> lockClaimedByRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    @Query("""
            SELECT COUNT(t) FROM MentorTurn t
            WHERE t.workspaceId = :workspaceId AND t.conversationId = :conversationId
              AND t.status = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.QUEUED
            """)
    long countQueuedByWorkspaceIdAndConversationId(
            @Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    /**
     * Locks queued turns and held turns, including stopped holders. A claim skips a locked row, so no turn of the conversation
     * starts while the caller holds them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM MentorTurn t
            WHERE t.workspaceId = :workspaceId AND t.conversationId = :conversationId
              AND (t.status IN (de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.QUEUED,
                                de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING)
                   OR (t.status = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.STOPPED AND""" + HELD_RUN + """
                   ))
            ORDER BY t.id
            """)
    List<MentorTurn> lockActiveByWorkspaceIdAndConversationId(
            @Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    /**
     * The queued turns {@code t} that a worker may start: the head of its conversation's queue, in a conversation
     * where no host holds a turn. The queue of a conversation is in the order of its questions' {@code seq}, which a
     * submission takes under the conversation's lock. A claim and the reason a turn waits read this one rule.
     */
    String CLAIMABLE = """
            FROM mentor_turn t
            JOIN mentor_entry question ON question.turn_id = t.id AND question.kind = 'USER'
            WHERE t.status = 'QUEUED'
              AND NOT EXISTS (
                  SELECT 1 FROM mentor_turn earlier
                  JOIN mentor_entry asked ON asked.turn_id = earlier.id AND asked.kind = 'USER'
                  WHERE earlier.conversation_id = t.conversation_id AND earlier.status = 'QUEUED'
                    AND asked.seq < question.seq)
              AND NOT EXISTS (
                  SELECT 1 FROM mentor_turn held
                  JOIN agent_run held_run ON held_run.id = held.run_id AND held_run.state = 'LEASED'
                  WHERE held.conversation_id = t.conversation_id)
            """;

    /** Of {@code ids}, the turns of the workspace that a worker may start now ({@link #CLAIMABLE}). Locks nothing. */
    @Query(
            value = "SELECT t.id " + CLAIMABLE + " AND t.workspace_id = :workspaceId AND t.id IN (:ids)",
            nativeQuery = true)
    List<UUID> findClaimableIds(@Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * The turns that hold a Hephaestus slot now: those that run, and stopped ones whose host still commits their answer.
     * Every workspace counts, because every workspace shares the workers' Hephaestus slots.
     */
    @WorkspaceAgnostic("Every workspace shares the workers' Hephaestus slots")
    @Query("SELECT COUNT(t) FROM MentorTurn t WHERE " + HELD_RUN)
    long countHeld();

    /** Whether a host holds the turn ({@link #HELD_RUN}). */
    @WorkspaceAgnostic("Reads one turn by its id, which the caller read in its workspace")
    @Query("SELECT COUNT(t) > 0 FROM MentorTurn t WHERE t.id = :id AND " + HELD_RUN)
    boolean isHeld(@Param("id") UUID id);

    /** A queued turn, with what {@link MentorLedgerEvents.TurnChanged} names. */
    interface QueuedTurn {
        Long getWorkspaceId();

        UUID getConversationId();

        UUID getId();

        String getSurface();

        int getAttempt();
    }

    /**
     * The newest {@code limit} turns that a worker may start ({@link #CLAIMABLE}), in every workspace. Locks nothing
     * and loads no entity.
     */
    @WorkspaceAgnostic(
            "Every workspace shares the workers' Hephaestus slots, so a change of them changes every workspace's waits")
    @Query(value = """
            SELECT t.workspace_id AS workspaceId, t.conversation_id AS conversationId, t.id AS id,
                   t.surface AS surface, t.attempt AS attempt
            """ + CLAIMABLE + """
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<QueuedTurn> findClaimable(@Param("limit") int limit);

    /**
     * The database clock at the start of the transaction. A turn's start takes it, because redactions and person
     * erasures stamp the same clock from other processes and are compared with the start.
     */
    @WorkspaceAgnostic("Reads the database clock")
    @Query(value = "SELECT now()", nativeQuery = true)
    Instant databaseNow();

    /** The database clock at this statement, not at the start of the transaction. */
    @WorkspaceAgnostic("Reads the database clock")
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseClock();

    @WorkspaceAgnostic("Fleet-wide queue depth for the mentor.turn.queued gauge")
    long countByStatus(MentorTurn.Status status);

    /**
     * Lets this transaction commit without waiting for its WAL flush. PostgreSQL serialises the commits of every
     * transaction that sent a notification, flush included, so with one flush per partial answer the database
     * commits only about one partial answer per flush interval across all turns. A partial answer is replaced by the
     * next one within 250 ms and by the end of its attempt, so a crash that loses the newest one loses nothing a
     * reader keeps.
     */
    @WorkspaceAgnostic("Sets one transaction-local setting and reads no table")
    @Query(value = "SELECT set_config('synchronous_commit', 'off', true)", nativeQuery = true)
    String commitWithoutFlushWait();

    /** A running attempt, as the read before its partial answer found it. */
    interface RunningAttempt {
        Instant getStartedAt();

        Long getWorkspaceId();

        UUID getConversationId();

        String getSurface();
    }

    /**
     * The running attempt that holds this live claim, read without a lock or a write: a partial answer only checks the
     * claim, because memory alone keeps it ({@link MentorTurnProgress}).
     *
     * @return the attempt, or empty when the attempt no longer holds its live claim
     */
    @WorkspaceAgnostic("The worker holds the attempt's claim; the statement returns the turn's workspace")
    @Query(value = """
            SELECT t.workspace_id AS workspaceId, t.conversation_id AS conversationId, t.surface AS surface,
                   t.started_at AS startedAt
              FROM mentor_turn t
            """ + LIVE_CLAIM + """
             WHERE t.workspace_id = :workspaceId AND t.id = :id AND t.attempt = :epoch AND t.run_id = :runId
               AND t.status = 'RUNNING'
            """, nativeQuery = true)
    Optional<RunningAttempt> findRunningAttempt(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("epoch") int epoch,
            @Param("runId") UUID runId);

    /** The account a turn answers. */
    @Query("""
            SELECT c.accountId FROM MentorTurn t JOIN MentorConversation c
              ON c.id = t.conversationId AND c.workspaceId = t.workspaceId
            WHERE t.id = :id AND t.workspaceId = :workspaceId
            """)
    Optional<Long> findAccountIdByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM MentorTurn x WHERE x.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /** Queues a notification on {@code channel}, which PostgreSQL delivers when the transaction commits. */
    @WorkspaceAgnostic("Sends a notification and reads no table; the payload names its workspace")
    @Query(value = "SELECT 1 FROM (SELECT pg_notify(:channel, :payload)) sent", nativeQuery = true)
    int notifyLedger(@Param("channel") String channel, @Param("payload") String payload);
}
