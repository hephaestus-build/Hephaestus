package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The attempt whose model calls the gateway routes and bills: a review job's or a Hephaestus turn's. An attempt token names
 * it, and so does a sandbox's run through its kind ({@code SandboxRunKind#attempt}). The attempt's row holds everything
 * else, so a caller cannot claim more.
 *
 * @param id the {@code review_job} id, or the {@code conversation_turn} id
 * @param epoch the claim of the run that the attempt holds ({@code lease_epoch})
 * @param runId the attempt's run: a review job's, or a turn's
 * @param workDeadline when a review attempt's work ends; null for a turn
 */
public record ProxyAttempt(
        Caller caller,
        UUID id,
        long workspaceId,
        int epoch,
        UUID runId,
        @Nullable Instant workDeadline) {

    /** Whose attempt it is. */
    public enum Caller {
        REVIEW,
        TURN
    }

    static ProxyAttempt of(AttemptJwt token) {
        return new ProxyAttempt(
                switch (token.kind()) {
                    case REVIEW -> Caller.REVIEW;
                    case TURN -> Caller.TURN;
                },
                token.id(),
                token.workspaceId(),
                token.epoch(),
                token.runId(),
                token.workDeadline());
    }
}
