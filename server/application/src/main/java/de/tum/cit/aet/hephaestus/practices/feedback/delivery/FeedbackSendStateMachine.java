package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class FeedbackSendStateMachine {
    private FeedbackSendStateMachine() {}

    public record Projection(
            FeedbackState state,
            @Nullable FeedbackStopReason stopReason,
            @Nullable Instant deliveredAt) {}

    public static Projection project(Feedback feedback, List<FeedbackCopy> copies) {
        return project(feedback, copies, feedback.getStopReason());
    }

    public static Projection project(
            Feedback feedback, List<FeedbackCopy> copies, @Nullable FeedbackStopReason withheldReason) {
        Instant deliveredAt = feedback.getDeliveredAt();
        for (FeedbackCopy copy : copies) {
            Instant postedAt = copy.getPostedAt();
            if (postedAt != null && (deliveredAt == null || postedAt.isBefore(deliveredAt))) {
                deliveredAt = postedAt;
            }
        }
        if (copies.stream().anyMatch(copy -> copy.getState() == FeedbackCopyState.WRITE_STARTED)) {
            return new Projection(FeedbackState.UNCONFIRMED, FeedbackStopReason.DELIVERY_UNCONFIRMED, deliveredAt);
        }
        if (feedback.getState() == FeedbackState.REPLACED || feedback.getState() == FeedbackState.REJECTED) {
            return new Projection(feedback.getState(), feedback.getStopReason(), deliveredAt);
        }
        if (copies.stream().anyMatch(copy -> copy.getState() == FeedbackCopyState.FAILED)) {
            return new Projection(FeedbackState.FAILED, null, deliveredAt);
        }
        if (deliveredAt != null && copies.stream().anyMatch(copy -> copy.getState() == FeedbackCopyState.PLANNED)) {
            return new Projection(FeedbackState.UNCONFIRMED, FeedbackStopReason.DELIVERY_UNCONFIRMED, deliveredAt);
        }
        if (deliveredAt != null) {
            return new Projection(FeedbackState.DELIVERED, null, deliveredAt);
        }
        if ((copies.isEmpty() && withheldReason != null)
                || (!copies.isEmpty()
                        && copies.stream().allMatch(copy -> copy.getState() == FeedbackCopyState.WITHHELD))) {
            @Nullable FeedbackStopReason reason = withheldReason;
            for (FeedbackCopy copy : copies) {
                if (copy.getStopReason() != null) {
                    reason = copy.getStopReason();
                    break;
                }
            }
            if (reason == null) {
                throw new IllegalArgumentException("Withheld feedback needs its recorded reason.");
            }
            return new Projection(FeedbackState.WITHHELD, reason, null);
        }
        return new Projection(feedback.getState(), feedback.getStopReason(), null);
    }
}
