package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /** Locks the row, so a state change reads the proxy's latest totals and the proxy waits for it. */
    @WorkspaceAgnostic("Locks by primary key; callers hold the id from a workspace-scoped read or a lease")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MentorTurn t WHERE t.id = :id")
    Optional<MentorTurn> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MentorTurn t WHERE t.id = :id AND t.workspaceId = :workspaceId")
    Optional<MentorTurn> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    @Query("""
            SELECT COUNT(t) FROM MentorTurn t
            WHERE t.workspaceId = :workspaceId AND t.conversationId = :conversationId
              AND t.status = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.QUEUED
            """)
    long countQueuedByWorkspaceIdAndConversationId(
            @Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    /**
     * Locks the queued and running turns of a conversation. A claim skips a locked row, so no turn of the conversation
     * starts while the caller holds them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM MentorTurn t
            WHERE t.workspaceId = :workspaceId AND t.conversationId = :conversationId
              AND t.status IN (de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.QUEUED,
                               de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING)
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
                  WHERE held.conversation_id = t.conversation_id
                    AND (held.status = 'RUNNING' OR (held.status = 'STOPPED' AND held.leased_until IS NOT NULL)))
            """;

    /**
     * The oldest turn a worker may start ({@link #CLAIMABLE}), locked. Skips rows another claim has locked, so
     * concurrent workers never start the same turn, and the head rule keeps them from starting two turns of one
     * conversation. The creation time comes from the clock of the server that took the question, so it orders only
     * the turns of different conversations.
     */
    @WorkspaceAgnostic("Workers claim queued turns across tenants")
    @Query(value = "SELECT t.* " + CLAIMABLE + """
            ORDER BY t.created_at, t.id
            LIMIT 1
            FOR UPDATE OF t SKIP LOCKED
            """, nativeQuery = true)
    Optional<MentorTurn> lockClaimable();

    /** Queued questions past their start budget; held conversations stay with their running turn. */
    @WorkspaceAgnostic("Server maintenance ends overdue queued questions across tenants")
    @Query(value = "SELECT t.id " + CLAIMABLE + """
            AND t.queued_at <= :cutoff
            ORDER BY t.queued_at, t.id
            LIMIT 100
            """, nativeQuery = true)
    List<UUID> findOverdueQueued(@Param("cutoff") Instant cutoff);

    /** Of {@code ids}, the turns of the workspace that a worker may start now ({@link #CLAIMABLE}). Locks nothing. */
    @Query(
            value = "SELECT t.id " + CLAIMABLE + " AND t.workspace_id = :workspaceId AND t.id IN (:ids)",
            nativeQuery = true)
    List<UUID> findClaimableIds(@Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * The turns that hold a Heph slot now: those that run, and stopped ones whose host still commits their answer.
     * Every workspace counts, because every workspace shares the workers' Heph slots.
     */
    @WorkspaceAgnostic("Every workspace shares the workers' Heph slots")
    @Query("""
            SELECT COUNT(t) FROM MentorTurn t
            WHERE t.status = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING
               OR (t.status = de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.STOPPED AND t.leasedUntil IS NOT NULL)
            """)
    long countHeld();

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
            "Every workspace shares the workers' Heph slots, so a change of them changes every workspace's waits")
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

    @WorkspaceAgnostic("The lease reaper sweeps expired leases across tenants")
    @Query("""
            SELECT t.id FROM MentorTurn t
            WHERE t.leasedUntil < :now
              AND t.status IN (de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING,
                               de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.STOPPED)
            ORDER BY t.leasedUntil
            """)
    List<UUID> findExpiredLeases(@Param("now") Instant now);

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

    /** A running attempt, as one partial-answer write found it. */
    interface RunningAttempt {
        Long getWorkspaceId();

        UUID getConversationId();

        String getSurface();
    }

    /**
     * Replaces the partial answer of a running attempt that this worker holds, in one statement: no lock is taken
     * before it, and the row's own lock is held only while it runs.
     *
     * @param partial the snapshot as JSON text
     * @return the attempt, or empty when it no longer runs on {@code workerId}
     */
    @WorkspaceAgnostic("The worker holds the attempt's lease; the statement returns the turn's workspace")
    @Query(value = """
            UPDATE mentor_turn SET partial = CAST(:partial AS jsonb)
             WHERE id = :id AND attempt = :attempt AND worker_id = :workerId
               AND status = 'RUNNING' AND leased_until IS NOT NULL
            RETURNING workspace_id AS workspaceId, conversation_id AS conversationId, surface AS surface
            """, nativeQuery = true)
    Optional<RunningAttempt> writePartial(
            @Param("id") UUID id,
            @Param("attempt") int attempt,
            @Param("workerId") String workerId,
            @Param("partial") String partial);

    /**
     * Adds one proxied call to the attempt that made it. The fence drops a call that arrives after the attempt
     * ended: that attempt is already billed, and a later attempt must not pay for it.
     *
     * @return 1 if the attempt still holds its lease, 0 otherwise
     */
    @WorkspaceAgnostic("The proxy credential already resolved the turn and its workspace")
    @Modifying
    @Query("""
            UPDATE MentorTurn t
               SET t.llmCalls = t.llmCalls + :#{#call.calls()},
                   t.inputTokens = t.inputTokens + :#{#call.inputTokens()},
                   t.outputTokens = t.outputTokens + :#{#call.outputTokens()},
                   t.reasoningTokens = t.reasoningTokens + :#{#call.reasoningTokens()},
                   t.cachedTokens = t.cachedTokens + :#{#call.cacheReadTokens()},
                   t.cacheWriteTokens = t.cacheWriteTokens + :#{#call.cacheWriteTokens()}
             WHERE t.id = :id AND t.attempt = :attempt AND t.leasedUntil IS NOT NULL
               AND t.status IN (de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.RUNNING,
                                de.tum.cit.aet.hephaestus.mentor.MentorTurn.Status.STOPPED)
            """)
    int accumulateUsage(@Param("id") UUID id, @Param("attempt") int attempt, @Param("call") MentorTurnUsage call);

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
