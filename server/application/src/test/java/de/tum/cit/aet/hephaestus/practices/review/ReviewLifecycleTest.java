package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ReviewLifecycleTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    void shouldStartOnlyAnActiveReview(ReviewStatus before) {
        var review = Review.builder().status(before).build();
        boolean active = before == ReviewStatus.QUEUED || before == ReviewStatus.RUNNING;
        assertThat(review.start(NOW)).isEqualTo(active);
        assertThat(review.getStatus()).isEqualTo(active ? ReviewStatus.RUNNING : before);
    }

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    void shouldRequeueOnlyARunningReview(ReviewStatus before) {
        var review = Review.builder().status(before).build();
        assertThat(review.requeue()).isEqualTo(before == ReviewStatus.RUNNING);
        assertThat(review.getStatus()).isEqualTo(before == ReviewStatus.RUNNING ? ReviewStatus.QUEUED : before);
    }

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    void shouldRequestCancellationOnlyOnceBeforeTheReviewEnds(ReviewStatus before) {
        var review = Review.builder().status(before).build();
        boolean active = before == ReviewStatus.QUEUED || before == ReviewStatus.RUNNING;
        assertThat(review.requestCancel()).isEqualTo(active);
        assertThat(review.isCancelRequested()).isEqualTo(active);
        assertThat(review.requestCancel()).isFalse();
        assertThat(review.start(NOW)).isFalse();
        assertThat(review.getStatus()).isEqualTo(before);
    }

    static Stream<Arguments> ends() {
        return Arrays.stream(ReviewStatus.values())
                .flatMap(before -> Stream.of(
                                ReviewStatus.COMPLETE,
                                ReviewStatus.PARTIAL,
                                ReviewStatus.FAILED,
                                ReviewStatus.CANCELLED)
                        .map(after -> Arguments.of(before, after)));
    }

    @ParameterizedTest
    @MethodSource("ends")
    void shouldEndAnActiveReviewAndRefuseEverySecondEnd(ReviewStatus before, ReviewStatus after) {
        var review = Review.builder().status(before).build();
        var reason = after == ReviewStatus.COMPLETE ? null : ReviewStopReason.EXECUTION_INCOMPLETE;
        if (before == ReviewStatus.QUEUED || before == ReviewStatus.RUNNING) {
            review.end(after, reason, NOW);
            assertThat(review.getStatus()).isEqualTo(after);
            assertThat(review.getStopReason()).isEqualTo(reason);
            assertThat(review.getCompletedAt()).isEqualTo(NOW);
        } else {
            assertThatThrownBy(() -> review.end(after, reason, NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(review.getStatus()).isEqualTo(before);
            assertThat(review.getCompletedAt()).isNull();
        }
    }

    @Test
    void shouldKeepTheFirstStartAndTheWholeEndWhileRefusingLaterMoves() {
        var review = Review.builder().build();
        assertThat(review.start(NOW)).isTrue();
        assertThat(review.requeue()).isTrue();
        assertThat(review.start(NOW.plusSeconds(60))).isTrue();
        assertThat(review.getChecks().path("startedAt").asString()).isEqualTo(NOW.toString());
        review.end(ReviewStatus.COMPLETE, null, NOW.plusSeconds(120));
        assertThat(review.start(NOW.plusSeconds(180))).isFalse();
        assertThat(review.requeue()).isFalse();
        assertThat(review.requestCancel()).isFalse();
        assertThatThrownBy(
                        () -> review.end(ReviewStatus.FAILED, ReviewStopReason.EXECUTION_FAILED, NOW.plusSeconds(180)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.COMPLETE);
        assertThat(review.getCompletedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(review.getStopReason()).isNull();
        assertThat(review.isCancelRequested()).isFalse();
        assertThat(review.getChecks().path("startedAt").asString()).isEqualTo(NOW.toString());
    }

    @Test
    void shouldRequireCancellationToWinOverACompletedResult() {
        var review = Review.builder().build();
        assertThat(review.requestCancel()).isTrue();
        assertThatThrownBy(() -> review.end(ReviewStatus.COMPLETE, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        review.end(ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED, NOW);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.CANCELLED);
    }

    @Test
    void shouldRefuseAnActiveEndOrAnInconsistentReason() {
        var review = Review.builder().build();
        assertThatThrownBy(() -> review.end(ReviewStatus.RUNNING, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> review.end(ReviewStatus.COMPLETE, ReviewStopReason.CANCELLED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> review.end(ReviewStatus.PARTIAL, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        assertThat(review.getStopReason()).isNull();
        assertThat(review.getCompletedAt()).isNull();
    }

    @Test
    void shouldExposeNoSetterForLifecycleFields() {
        assertThat(Review.class.getMethods())
                .extracting(Method::getName)
                .doesNotContain("setStatus", "setStopReason", "setCompletedAt", "setCancelRequested");
    }
}
