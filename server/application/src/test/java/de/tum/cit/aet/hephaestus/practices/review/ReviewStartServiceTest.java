package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import tools.jackson.databind.node.JsonNodeFactory;

class ReviewStartServiceTest extends BaseUnitTest {
    private static final long WORKSPACE = 17;
    private static final UUID REVIEW = UUID.fromString("b43146d6-0e12-4775-8a2d-4459b4a279a1");
    private static final Instant NOW = Instant.parse("2026-10-31T19:00:00Z");

    @Mock
    private ReviewRepository reviews;

    @Test
    void shouldStartAQueuedReviewAndKeepItsExistingChecks() {
        var checks = JsonNodeFactory.instance.objectNode().put("selectionComplete", true);
        var review = Review.builder()
                .id(REVIEW)
                .workspaceId(WORKSPACE)
                .checks(checks)
                .build();
        when(reviews.lockByIdAndWorkspaceId(REVIEW, WORKSPACE)).thenReturn(Optional.of(review));

        assertThat(starts().start(WORKSPACE, REVIEW)).isTrue();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.RUNNING);
        assertThat(review.getChecks().path("startedAt").asString()).isEqualTo(NOW.toString());
        assertThat(review.getChecks().path("selectionComplete").asBoolean()).isTrue();
    }

    @Test
    void shouldRetainTheFirstStartTimeForAnAlreadyRunningReview() {
        var checks = JsonNodeFactory.instance
                .objectNode()
                .put("startedAt", NOW.minusSeconds(60).toString());
        var review = Review.builder()
                .id(REVIEW)
                .workspaceId(WORKSPACE)
                .status(ReviewStatus.RUNNING)
                .checks(checks)
                .build();
        when(reviews.lockByIdAndWorkspaceId(REVIEW, WORKSPACE)).thenReturn(Optional.of(review));

        assertThat(starts().start(WORKSPACE, REVIEW)).isTrue();
        assertThat(review.getChecks()).isEqualTo(checks);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewStatus.class,
            names = {"COMPLETE", "PARTIAL", "FAILED", "CANCELLED"})
    void shouldNeverRestartATerminalReview(ReviewStatus status) {
        var review = Review.builder()
                .id(REVIEW)
                .workspaceId(WORKSPACE)
                .status(status)
                .build();
        when(reviews.lockByIdAndWorkspaceId(REVIEW, WORKSPACE)).thenReturn(Optional.of(review));

        assertThat(starts().start(WORKSPACE, REVIEW)).isFalse();
        assertThat(review.getStatus()).isEqualTo(status);
        assertThat(review.getChecks().has("startedAt")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewStatus.class,
            names = {"QUEUED", "RUNNING"})
    void shouldLeaveACancelRequestedReviewForSettlement(ReviewStatus status) {
        var review = Review.builder()
                .id(REVIEW)
                .workspaceId(WORKSPACE)
                .status(status)
                .checks(JsonNodeFactory.instance.objectNode().put("cancellationRequested", true))
                .build();
        when(reviews.lockByIdAndWorkspaceId(REVIEW, WORKSPACE)).thenReturn(Optional.of(review));

        assertThat(starts().start(WORKSPACE, REVIEW)).isFalse();
        assertThat(review.getStatus()).isEqualTo(status);
    }

    @Test
    void shouldNotStartAReviewOutsideTheWorkspace() {
        when(reviews.lockByIdAndWorkspaceId(REVIEW, WORKSPACE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> starts().start(WORKSPACE, REVIEW)).isInstanceOf(NoSuchElementException.class);
    }

    private ReviewStartService starts() {
        return new ReviewStartService(reviews, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
