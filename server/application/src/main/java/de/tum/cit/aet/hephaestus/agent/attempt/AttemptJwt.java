package de.tum.cit.aet.hephaestus.agent.attempt;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The credential of one attempt: of a review job, or of a Hephaestus turn. It names the work, its run, the claim of the run
 * (its epoch) and its workspace. The work's row holds everything else, such as a turn's conversation and account, so a
 * credential cannot claim them.
 *
 * @param id the {@code review_job} id, or the {@code conversation_turn} id
 * @param epoch the claim of the run that the attempt holds: the {@code agent_run.lease_epoch} it was issued under
 * @param runId the {@code agent_run} that the attempt holds
 * @param workDeadline when a review attempt's work ends, as its executor set it; {@code null} for a turn. The credential
 *     itself stays valid until {@code expiresAt}, so the attempt can still return its result after its work time.
 */
public record AttemptJwt(
        Kind kind,
        UUID id,
        Long workspaceId,
        int epoch,
        UUID runId,
        Set<String> scopes,
        String jti,
        Instant issuedAt,
        Instant expiresAt,
        Instant workDeadline) {

    /** What the attempt runs. */
    public enum Kind {
        REVIEW,
        TURN
    }

    public AttemptJwt {
        Objects.requireNonNull(runId, "runId");
        scopes = Set.copyOf(scopes);
        // A run's first claim takes epoch 1.
        if (epoch < 1) throw new IllegalArgumentException("a claim's epoch counts from 1");
        // Order only: a passed work deadline still authenticates, so the attempt can return its result or end.
        if (workDeadline == null || workDeadline.isBefore(issuedAt) || workDeadline.isAfter(expiresAt))
            throw new IllegalArgumentException("work deadline must not be before issue or after expiry");
    }
}
