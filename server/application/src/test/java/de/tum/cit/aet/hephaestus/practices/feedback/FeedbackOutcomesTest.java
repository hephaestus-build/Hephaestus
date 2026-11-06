package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FeedbackOutcomesTest {

    private static final Instant ORDERED = Instant.parse("2026-11-03T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FeedbackRepository feedback = mock(FeedbackRepository.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final FeedbackDecisionRepository decisions = mock(FeedbackDecisionRepository.class);
    private final FeedbackOutcomes outcomes = new FeedbackOutcomes(registry, feedback, reviews, decisions);

    @Test
    void shouldObserveTheReviewsFirstDeliveryFromItsOccasionWhenItsFirstCopyIsPosted() {
        var row = row(FeedbackState.UNCONFIRMED, null);
        when(feedback.updateDelivery(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(reviews.findByIdAndWorkspaceId(row.getReviewId(), 1))
                .thenReturn(Optional.of(Review.builder()
                        .origin(ReviewOrigin.EVENT)
                        .orderedAt(ORDERED)
                        .build()));

        outcomes.update(row, FeedbackState.DELIVERED, null, ORDERED.plus(Duration.ofMinutes(20)));

        assertThat(settled("delivered", "none")).isEqualTo(1);
        Timer delay = registry.get(PracticeMetrics.REVIEW_FIRST_DELIVERY)
                .tags("trigger", "event", "approval", "none")
                .timer();
        assertThat(delay.count()).isEqualTo(1);
        assertThat(Arrays.stream(delay.takeSnapshot().histogramCounts())
                        .filter(bucket -> bucket.bucket(TimeUnit.MINUTES) == 30)
                        .findFirst()
                        .orElseThrow()
                        .count())
                .as("the delivery falls within the 30-minute objective")
                .isEqualTo(1);
    }

    @Test
    void shouldCountNothingWhenTheWriteChangedNoRow() {
        var row = row(FeedbackState.READY, null);

        outcomes.update(row, FeedbackState.DELIVERED, null, ORDERED);

        assertThat(settled("delivered", "none")).isZero();
        assertThat(firstDeliveries()).isZero();
    }

    @Test
    void shouldObserveAReviewOnceWhenAnotherOfItsFeedbackWasPostedFirst() {
        var row = row(FeedbackState.UNCONFIRMED, null);
        when(feedback.updateDelivery(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(feedback.hasOtherPostedFeedback(1, row.getReviewId(), row.getId())).thenReturn(true);
        review(row, ReviewOrigin.EVENT);

        outcomes.update(row, FeedbackState.DELIVERED, null, ORDERED.plusSeconds(60));

        assertThat(settled("delivered", "none")).isEqualTo(1);
        assertThat(firstDeliveries()).isZero();
    }

    @Test
    void shouldLogAMoveToASettledStateOnceAndARepeatedStateNever() {
        when(feedback.updateDelivery(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(feedback.hasOtherPostedFeedback(anyLong(), any(), any())).thenReturn(true);
        var ready = row(FeedbackState.READY, null);
        var delivered = row(FeedbackState.DELIVERED, ORDERED);

        try (var logs = new LogCapture(FeedbackOutcomes.class)) {
            outcomes.update(ready, FeedbackState.DELIVERED, null, ORDERED);
            assertThat(logs.events("feedback.copy.settled")).singleElement().satisfies(fields -> {
                assertThat(fields).containsEntry("feedback.id", ready.getId().toString());
                assertThat(fields).containsEntry("feedback.state", "delivered");
            });

            outcomes.update(delivered, FeedbackState.DELIVERED, null, ORDERED);
            assertThat(logs.events("feedback.copy.settled")).hasSize(1);
        }
    }

    @Test
    void shouldNotCountAgainAStateAndADeliveryThatTheFeedbackAlreadyHas() {
        var row = row(FeedbackState.DELIVERED, ORDERED);
        when(feedback.updateDelivery(anyLong(), any(), any(), any(), any())).thenReturn(1);
        review(row, ReviewOrigin.EVENT);

        outcomes.update(row, FeedbackState.DELIVERED, null, ORDERED.plusSeconds(60));

        assertThat(settled("delivered", "none")).isZero();
        assertThat(firstDeliveries()).isZero();
    }

    @Test
    void shouldLogSettledFeedbackOnceWithItsDestinationAndReason() {
        var withheld = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .destination(FeedbackDestination.CONVERSATION)
                .state(FeedbackState.WITHHELD)
                .stopReason(FeedbackStopReason.COMPOSER_WITHHELD)
                .build();

        try (var logs = new LogCapture(FeedbackOutcomes.class)) {
            outcomes.created(withheld);
            outcomes.created(row(FeedbackState.READY, null));

            assertThat(logs.events("feedback.copy.settled")).singleElement().satisfies(fields -> {
                assertThat(fields).containsEntry("feedback.id", withheld.getId().toString());
                assertThat(fields).containsEntry("feedback.destination", "conversation");
                assertThat(fields).containsEntry("feedback.state", "withheld");
                assertThat(fields).containsEntry("feedback.reason", "composer_withheld");
            });
        }
    }

    @Test
    void shouldCountFeedbackThatIsWithheldWhenItIsCreatedWithItsReason() {
        var withheld = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .destination(FeedbackDestination.CONVERSATION)
                .state(FeedbackState.WITHHELD)
                .stopReason(FeedbackStopReason.COMPOSER_WITHHELD)
                .build();

        outcomes.created(withheld);
        outcomes.created(row(FeedbackState.READY, null));

        assertThat(registry.get(PracticeMetrics.FEEDBACK_SETTLED)
                        .tags("destination", "conversation", "state", "withheld", "reason", "composer_withheld")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(PracticeMetrics.FEEDBACK_SETTLED).counters().stream()
                        .mapToDouble(counter -> counter.count())
                        .sum())
                .isEqualTo(1);
    }

    @Test
    void shouldDeliverPracticePageFeedbackAtItsFirstReadWithoutAPostedCopy() {
        var row = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .destination(FeedbackDestination.PRACTICE_PAGE)
                .state(FeedbackState.READY)
                .build();
        when(feedback.recordFirstRead(anyLong(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(1);
        review(row, ReviewOrigin.REQUEST);

        assertThat(outcomes.firstRead(row, 7, ORDERED.plusSeconds(5))).isTrue();

        assertThat(registry.get(PracticeMetrics.FEEDBACK_SETTLED)
                        .tags("destination", "practice_page", "state", "delivered", "reason", "none")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(firstDeliveries()).as("a read posts no copy").isZero();
    }

    @Test
    void shouldLabelAnApprovedConversationDeliveryAndCountAPostBeforeItsOccasionAsNoWait() {
        var row = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .destination(FeedbackDestination.CONVERSATION)
                .state(FeedbackState.UNCONFIRMED)
                .build();
        when(feedback.updateDelivery(anyLong(), any(), any(), any(), any())).thenReturn(1);
        review(row, ReviewOrigin.REQUEST);
        when(decisions.findLatestApproval(1, row.getId()))
                .thenReturn(Optional.of(FeedbackDecision.builder()
                        .action(FeedbackDecisionAction.APPROVE)
                        .build()));

        outcomes.update(row, FeedbackState.DELIVERED, null, ORDERED.minusSeconds(5));

        Timer delay = registry.get(PracticeMetrics.REVIEW_FIRST_DELIVERY)
                .tags("trigger", "request", "approval", "approved")
                .timer();
        assertThat(delay.count()).isEqualTo(1);
        assertThat(delay.totalTime(TimeUnit.SECONDS)).isZero();
    }

    @Test
    void shouldCountExpiredConversationFeedbackAsWithheld() {
        var row = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .destination(FeedbackDestination.CONVERSATION)
                .state(FeedbackState.READY)
                .build();
        when(feedback.expireConversation(anyLong(), any(), any())).thenReturn(1);

        outcomes.expireConversation(row, ORDERED);

        assertThat(registry.get(PracticeMetrics.FEEDBACK_SETTLED)
                        .tags("destination", "conversation", "state", "withheld", "reason", "conversation_expired")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private void review(Feedback row, ReviewOrigin origin) {
        when(reviews.findByIdAndWorkspaceId(row.getReviewId(), 1))
                .thenReturn(Optional.of(
                        Review.builder().origin(origin).orderedAt(ORDERED).build()));
    }

    private long firstDeliveries() {
        return registry.get(PracticeMetrics.REVIEW_FIRST_DELIVERY).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }

    private static Feedback row(FeedbackState state, @Nullable Instant deliveredAt) {
        return Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .destination(FeedbackDestination.ON_WORK)
                .state(state)
                .deliveredAt(deliveredAt)
                .build();
    }

    private double settled(String state, String reason) {
        return registry.get(PracticeMetrics.FEEDBACK_SETTLED)
                .tags("destination", "on_work", "state", state, "reason", reason)
                .counter()
                .count();
    }
}
