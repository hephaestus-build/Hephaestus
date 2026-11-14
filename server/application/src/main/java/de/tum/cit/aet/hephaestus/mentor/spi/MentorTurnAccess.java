package de.tum.cit.aet.hephaestus.mentor.spi;

import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Snapshots of the turns of the mentor ledger; no returned value is a managed entity. */
public interface MentorTurnAccess {
    Optional<TurnState> find(long workspaceId, UUID turnId);

    /** The account whose conversation the turn answers. */
    Optional<Long> findAccountId(long workspaceId, UUID turnId);

    /**
     * The attempt whose run lease is current on the database clock, running or stopped, with its run's deadline.
     *
     * @return empty when the claim lost the run or its turn ended
     */
    Optional<RunAttempt> runAttempt(long workspaceId, UUID runId, int epoch);

    /** Whether the attempt holds its live lease and still runs, on the database clock. */
    boolean hasLiveLease(long workspaceId, UUID turnId, int epoch, UUID runId);

    /**
     * Counts one load of the attempt's context folder, on the database clock, while the attempt holds its live lease
     * and has loaded fewer than {@code max} items. Joins the caller's transaction, or opens its own without one.
     *
     * @return whether the load counts
     */
    boolean countContextLoad(long workspaceId, UUID turnId, int epoch, UUID runId, int max);

    /** The queued turns of every workspace that a person asked, not the system. */
    long countQueuedQuestions();

    /** @param admission a copy of what the current attempt was admitted with; {@code null} before its admission */
    record TurnState(
            UUID id,
            UUID conversationId,
            int attempt,
            MentorTurn.Status status,
            MentorTurn.Surface surface,
            Instant createdAt,
            @Nullable JsonNode admission) {}

    record RunAttempt(
            UUID id,
            UUID conversationId,
            long accountId,
            MentorTurn.Status status,
            @Nullable Instant deadline) {}
}
