package de.tum.cit.aet.hephaestus.practices.review;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Counts each review, and each of its items, once when the review's end commits. */
@Component
@ConditionalOnServerRole
class ReviewOutcomeMeters {

    /**
     * A recorded withholding reason is a code constant, but no single enum holds them all. A value of any other shape
     * counts as {@code other}, so stored text never becomes a label.
     */
    private static final Pattern CONSTANT = Pattern.compile("[A-Za-z][A-Za-z_]{0,47}");

    private final MeterRegistry registry;
    private final ReviewItemRepository memberships;

    /**
     * Registers every review end at zero. A counter that first appears at 1 has no earlier sample, so
     * {@code increase()} would miss the first end of each status and reason after each start.
     */
    ReviewOutcomeMeters(MeterRegistry registry, ReviewItemRepository memberships) {
        this.registry = registry;
        this.memberships = memberships;
        for (ReviewKind kind : ReviewKind.values()) {
            ended(kind, ReviewStatus.COMPLETE, null);
            for (ReviewStatus status : ReviewStatus.values()) {
                if (!status.isTerminal() || status == ReviewStatus.COMPLETE) continue;
                for (ReviewStopReason reason : ReviewStopReason.values()) ended(kind, status, reason);
            }
        }
    }

    /** Call in the transaction that gives the review its terminal status, after its items are final. */
    void ended(Review review) {
        String kind = label(review.getKind().name());
        Counter ended = ended(review.getKind(), review.getStatus(), review.getStopReason());
        var items = memberships.endStates(review.getWorkspaceId(), review.getId());
        afterCommit(() -> {
            ended.increment();
            items.forEach(item -> registry.counter(
                            PracticeMetrics.REVIEW_ITEMS_ENDED,
                            "kind",
                            kind,
                            "state",
                            label(item.getState()),
                            "reason",
                            reason(item.getReason()))
                    .increment((double) item.getItems()));
        });
    }

    private Counter ended(ReviewKind kind, ReviewStatus status, @Nullable ReviewStopReason reason) {
        return registry.counter(
                PracticeMetrics.REVIEW_ENDED,
                "kind",
                label(kind.name()),
                "status",
                label(status.name()),
                "reason",
                reason == null ? "none" : label(reason.name()));
    }

    static String reason(@Nullable String recorded) {
        if (recorded == null) return "none";
        return CONSTANT.matcher(recorded).matches() ? label(recorded) : "other";
    }

    private static String label(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
