package de.tum.cit.aet.hephaestus.practices.feedback;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one writer of a feedback's delivery state. It counts each settled state that feedback reaches, and the time from
 * a review's occasion to its first posted copy, once the transaction commits.
 */
@Component
@ConditionalOnCaptureRole
public class FeedbackOutcomes {

    private static final Set<FeedbackState> SETTLED =
            EnumSet.of(FeedbackState.DELIVERED, FeedbackState.WITHHELD, FeedbackState.FAILED, FeedbackState.REJECTED);

    /** The delivery objective's 30 minutes, with room to see how far a review missed it. */
    private static final Duration[] FIRST_DELIVERY_BOUNDS = {
        Duration.ofMinutes(1),
        Duration.ofMinutes(2),
        Duration.ofMinutes(5),
        Duration.ofMinutes(10),
        Duration.ofMinutes(15),
        Duration.ofMinutes(20),
        Duration.ofMinutes(30),
        Duration.ofMinutes(45),
        Duration.ofHours(1),
        Duration.ofHours(2),
        Duration.ofHours(4),
        Duration.ofHours(8)
    };

    private final MeterRegistry registry;
    private final FeedbackRepository feedback;
    private final ReviewRepository reviews;
    private final FeedbackDecisionRepository decisions;

    /**
     * Registers every settled state and every first delivery at zero. A series that first appears with a count has no
     * earlier sample, so {@code increase()} would miss it after each start.
     */
    public FeedbackOutcomes(
            MeterRegistry registry,
            FeedbackRepository feedback,
            ReviewRepository reviews,
            FeedbackDecisionRepository decisions) {
        this.registry = registry;
        this.feedback = feedback;
        this.reviews = reviews;
        this.decisions = decisions;
        for (FeedbackDestination destination : FeedbackDestination.values()) {
            for (FeedbackState state : SETTLED) {
                if (state != FeedbackState.WITHHELD) settled(destination, state, null);
            }
            for (FeedbackStopReason reason : FeedbackStopReason.values())
                settled(destination, FeedbackState.WITHHELD, reason);
        }
        for (ReviewOrigin trigger : ReviewOrigin.values()) {
            firstDelivery(trigger, false);
            firstDelivery(trigger, true);
        }
    }

    /** Counts the state that new feedback starts in. Call after its row is written. */
    public void created(Feedback row) {
        if (!SETTLED.contains(row.getState())) return;
        Counter counter = settled(row.getDestination(), row.getState(), row.getStopReason());
        afterCommit(counter::increment);
    }

    /** Writes the delivery state of {@code row}, which holds the state that it replaces. */
    public int update(
            Feedback row, FeedbackState state, @Nullable FeedbackStopReason reason, @Nullable Instant deliveredAt) {
        int updated = feedback.updateDelivery(row.getWorkspaceId(), row.getId(), state, reason, deliveredAt);
        if (updated == 1) moved(row, state, reason, deliveredAt);
        return updated;
    }

    /** Records the developer's first read of ready practice-page feedback, which delivers it. */
    public boolean firstRead(Feedback row, long developerId, Instant readAt) {
        boolean read = feedback.recordFirstRead(
                        row.getWorkspaceId(),
                        row.getId(),
                        developerId,
                        readAt,
                        FeedbackDestination.PRACTICE_PAGE,
                        FeedbackState.READY,
                        FeedbackState.DELIVERED)
                == 1;
        if (read) moved(row, FeedbackState.DELIVERED, null, readAt);
        return read;
    }

    /** Withholds conversation feedback that no conversation showed before {@code cutoff}. */
    public int expireConversation(Feedback row, Instant cutoff) {
        int expired = feedback.expireConversation(row.getWorkspaceId(), row.getId(), cutoff);
        if (expired == 1) moved(row, FeedbackState.WITHHELD, FeedbackStopReason.CONVERSATION_EXPIRED, null);
        return expired;
    }

    private void moved(
            Feedback row, FeedbackState state, @Nullable FeedbackStopReason reason, @Nullable Instant deliveredAt) {
        List<Runnable> counts = new ArrayList<>(2);
        if (state != row.getState() && SETTLED.contains(state))
            counts.add(settled(row.getDestination(), state, reason)::increment);
        // A copy is posted on the work or in a conversation. The practice page has no copy: its delivery is a read.
        if (row.getDeliveredAt() == null
                && deliveredAt != null
                && row.getDestination() != FeedbackDestination.PRACTICE_PAGE
                && !feedback.hasOtherPostedFeedback(row.getWorkspaceId(), row.getReviewId(), row.getId())) {
            // Read in the delivery's transaction: after the commit there is no transaction to read in. The reads are
            // by key, once for each review.
            var review = reviews.findByIdAndWorkspaceId(row.getReviewId(), row.getWorkspaceId());
            if (review.isPresent()) {
                boolean approved = decisions
                        .findLatestApproval(row.getWorkspaceId(), row.getId())
                        .map(FeedbackDecision::getAction)
                        .filter(action -> action == FeedbackDecisionAction.APPROVE)
                        .isPresent();
                Timer timer = firstDelivery(review.get().getOrigin(), approved);
                Duration waited = Duration.between(review.get().getOrderedAt(), deliveredAt);
                counts.add(() -> timer.record(waited.isNegative() ? Duration.ZERO : waited));
            }
        }
        if (!counts.isEmpty()) afterCommit(() -> counts.forEach(Runnable::run));
    }

    private Counter settled(FeedbackDestination destination, FeedbackState state, @Nullable FeedbackStopReason reason) {
        return registry.counter(
                PracticeMetrics.FEEDBACK_SETTLED,
                "destination",
                label(destination.name()),
                "state",
                label(state.name()),
                "reason",
                reason == null ? "none" : label(reason.name()));
    }

    private Timer firstDelivery(ReviewOrigin trigger, boolean approved) {
        return Timer.builder(PracticeMetrics.REVIEW_FIRST_DELIVERY)
                .description("From a review's occasion to its first posted copy")
                .tag("trigger", label(trigger.name()))
                .tag("approval", approved ? "approved" : "none")
                .serviceLevelObjectives(FIRST_DELIVERY_BOUNDS)
                .register(registry);
    }

    private static String label(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
