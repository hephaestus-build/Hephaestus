package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import java.time.Instant;
import java.util.UUID;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * A feedback's newest send, read from its newest action in the outbox ({@link FeedbackSendQueue}). The outbox owns the
 * send's lease and attempts; this view names them in the feedback's vocabulary.
 */
@Value
public class FeedbackSend {
    UUID feedbackId;
    long workspaceId;
    FeedbackSendAction action;
    FeedbackSendState state;
    int attempts;
    Instant nextAttemptAt;

    @Nullable
    String lastError;

    Instant createdAt;
    Instant updatedAt;

    static FeedbackSend of(UUID feedbackId, long workspaceId, LaneAction action) {
        return new FeedbackSend(
                feedbackId,
                workspaceId,
                FeedbackSendQueue.actionOf(action.idempotencyKey()),
                state(action),
                action.attempts(),
                action.nextAttemptAt(),
                action.rereading() || action.state() == ActionState.UNCERTAIN
                        ? "Provider visibility is unknown."
                        : action.state() == ActionState.FAILED ? String.valueOf(action.stopReason()) : null,
                action.createdAt(),
                action.updatedAt());
    }

    /** A send that waits to read the provider back is uncertain; an ended action ended the send. */
    private static FeedbackSendState state(LaneAction action) {
        return switch (action.state()) {
            case PROPOSED, ADMITTED -> FeedbackSendState.PENDING;
            case EXECUTING -> action.rereading() ? FeedbackSendState.UNCERTAIN : FeedbackSendState.SENDING;
            case DONE, UNCERTAIN, FAILED, CANCELLED -> FeedbackSendState.DONE;
        };
    }
}
