package de.tum.cit.aet.hephaestus.practices.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReplySink;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRequestOutcomeDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A person who asks for a review by a comment always gets an answer on the work. */
class ReviewCommandListenerTest extends BaseUnitTest {
    private static final String ADDRESS = "gid://gitlab/MergeRequest/7 gid://gitlab/Discussion/abc";

    private final ReviewRequestService requests = mock(ReviewRequestService.class);
    private final ScmCommentReplySink sink = mock(ScmCommentReplySink.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewItemRepository memberships = mock(ReviewItemRepository.class);
    private final ReviewCommandListener listener =
            new ReviewCommandListener(requests, List.of(sink), reviews, memberships);

    @BeforeEach
    void gitLab() {
        lenient().when(sink.kind()).thenReturn(IntegrationKind.GITLAB);
    }

    private static BotCommandReceivedEvent command(@Nullable String address) {
        return command("/hephaestus review", address);
    }

    private static BotCommandReceivedEvent command(String body, @Nullable String address) {
        return new BotCommandReceivedEvent(IntegrationKind.GITLAB, 11L, 22L, body, "someone", 1L, 33L, 44L, address);
    }

    @Test
    void shouldStartNoReviewWhenTheCommentHoldsMoreThanTheCommand() {
        listener.on(command("/hephaestus review please check the tests", ADDRESS));

        verifyNoInteractions(requests, sink);
    }

    @Test
    void shouldStartAReviewWhenTheCommentHoldsOnlyTheCommandInAnyCase() {
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new ReviewRequestOutcomeDTO(ReviewRequestStatus.SUBMITTED, UUID.randomUUID(), null));

        listener.on(command("  /Hephaestus Review \n", ADDRESS));

        verify(requests).requestByComment(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void shouldAnswerARefusedRequestWithItsReason() {
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new ReviewRequestOutcomeDTO(
                        ReviewRequestStatus.REFUSED, null, ReviewRequestReason.REQUEST_COOLDOWN_ACTIVE));

        listener.on(command(ADDRESS));

        verify(sink)
                .reply(
                        11L,
                        22L,
                        ADDRESS,
                        "Hephaestus did not start a review: a review of this merge request was requested a short time"
                                + " ago. Ask again later.");
    }

    @Test
    void shouldNameAPullRequestAndAGitHubAccountWhenTheCommandCameFromGitHub() {
        var gitHub = mock(ScmCommentReplySink.class);
        when(gitHub.kind()).thenReturn(IntegrationKind.GITHUB);
        var listening = new ReviewCommandListener(requests, List.of(sink, gitHub), reviews, memberships);
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new AccessForbiddenException("not linked"));

        listening.on(new BotCommandReceivedEvent(
                IntegrationKind.GITHUB, 11L, 22L, "/hephaestus review", "someone", 1L, 33L, 44L, "acme/widgets#7"));

        verify(gitHub)
                .reply(
                        11L,
                        22L,
                        "acme/widgets#7",
                        "Hephaestus did not start a review: only the author or an assignee of this pull request, or a"
                                + " workspace admin, can ask for a review, and only from a GitHub account that is linked"
                                + " to their Hephaestus account.");
        verify(sink, never()).reply(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void shouldAnswerARequestThatThePersonMayNotMake() {
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new AccessForbiddenException("not linked"));

        listener.on(command(ADDRESS));

        verify(sink).reply(eq(11L), eq(22L), eq(ADDRESS), startsWith("Hephaestus did not start a review: only the"));
    }

    @Test
    void shouldLeaveAnAcceptedRequestToItsFeedback() {
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new ReviewRequestOutcomeDTO(ReviewRequestStatus.SUBMITTED, UUID.randomUUID(), null));

        listener.on(command(ADDRESS));

        verify(sink, never()).reply(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void shouldNotAnswerAgainForACommentDeliveredAgain() {
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new ReviewRequestOutcomeDTO(
                        ReviewRequestStatus.REFUSED, null, ReviewRequestReason.CONCURRENT_DUPLICATE));
        when(reviews.findByWorkspaceIdAndRequestKey(eq(11L), any())).thenReturn(Optional.of(mock(Review.class)));

        listener.on(command(ADDRESS));

        verify(sink, never()).reply(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void shouldAnswerWhenTheReviewKeptEveryResult() {
        var review = requested(true, ReviewStatus.COMPLETE);

        listener.on(ended(review, 0, 3, 0));

        verify(sink)
                .reply(
                        eq(11L),
                        eq(22L),
                        eq(ADDRESS),
                        startsWith("Nothing that the practices read has changed since the last review"));
    }

    @Test
    void shouldSayItCouldNotReviewWhenAPracticeWasWithheldAndNoneMeasured() {
        listener.on(ended(requested(true, ReviewStatus.PARTIAL), 0, 2, 1));

        verify(sink).reply(eq(11L), eq(22L), eq(ADDRESS), startsWith("Hephaestus could not review this merge request"));
    }

    @Test
    void shouldSayItCouldNotReviewWhenTheReviewEndedWithNothing() {
        listener.on(ended(requested(true, ReviewStatus.FAILED), 0, 0, 0));

        verify(sink).reply(eq(11L), eq(22L), eq(ADDRESS), startsWith("Hephaestus could not review this merge request"));
    }

    @Test
    void shouldNotAnswerAReviewThatMeasuredAnything() {
        listener.on(ended(requested(true, ReviewStatus.COMPLETE), 1, 2, 0));

        verify(sink, never()).reply(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void shouldNotAnswerAReviewThatNoCommentRequested() {
        listener.on(ended(requested(false, ReviewStatus.COMPLETE), 0, 3, 0));

        verify(sink, never()).reply(anyLong(), anyLong(), anyString(), anyString());
    }

    private Review requested(boolean byComment, ReviewStatus status) {
        var checks = new JsonMapper().createObjectNode();
        if (byComment) checks.putObject("commentAnswer").put("kind", "GITLAB").put("address", ADDRESS);
        var review = Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(11L)
                .itemId(22L)
                .kind(ReviewKind.ITEM)
                .status(status)
                .checks(checks)
                .build();
        when(reviews.findByIdAndWorkspaceId(review.getId(), 11L)).thenReturn(Optional.of(review));
        return review;
    }

    private CommentRequestedReviewEnded ended(Review review, long judged, long reused, long withheld) {
        var counts = mock(ReviewItemRepository.StateCounts.class);
        lenient().when(counts.getJudged()).thenReturn(judged);
        lenient().when(counts.getReused()).thenReturn(reused);
        lenient().when(counts.getWithheld()).thenReturn(withheld);
        lenient().when(counts.getListed()).thenReturn(judged + reused + withheld);
        lenient().when(memberships.stateCounts(11L, review.getId())).thenReturn(counts);
        return new CommentRequestedReviewEnded(review.getId(), 11L);
    }
}
