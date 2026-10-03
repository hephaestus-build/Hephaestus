package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

class GitLabReviewReconcilerTest extends BaseUnitTest {

    private static final Instant MERGED_AT = Instant.parse("2026-04-20T08:32:23Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-04-19T20:30:33Z");

    @Mock
    private PullRequestReviewRepository reviewRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GitLabReviewReconciler reconciler;
    private IdentityProvider provider;
    private PullRequest pr;
    private User approver;

    @BeforeEach
    void setUp() {
        reconciler = new GitLabReviewReconciler(reviewRepository, eventPublisher);
        provider = new IdentityProvider();
        provider.setId(7L);
        pr = new PullRequest();
        pr.setNativeId(4242L);
        approver = new User();
        approver.setNativeId(99L);
        approver.setLogin("reviewer");
    }

    private PullRequestReview approval(Instant submittedAt) {
        PullRequestReview review = new PullRequestReview();
        review.setState(PullRequestReview.State.APPROVED);
        review.setSubmittedAt(submittedAt);
        review.setCreatedAt(submittedAt);
        long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(4242L, 99L);
        when(reviewRepository.findByNativeIdAndProviderId(nativeId, 7L)).thenReturn(Optional.of(review));
        return review;
    }

    @Test
    @DisplayName("an old approval note cannot date the current approval")
    void shouldKeepTheCurrentApprovalUndatedWhenAnOldNoteIsReplayed() {
        PullRequestReview current = approval(MERGED_AT);
        current.setSubmittedAt(null);

        PullRequestReview review = reconciler.recordApproval(pr, approver, provider, false);

        assertThat(review).isSameAs(current);
        assertThat(current.getSubmittedAt()).isNull();
        assertThat(current.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("an approval already earlier than the note is left as it stands")
    void shouldKeepAnEarlierApproval() {
        Instant earlier = APPROVED_AT.minusSeconds(60);
        approval(earlier);

        PullRequestReview review = reconciler.recordApproval(pr, approver, provider, false);

        assertThat(review).isNotNull();
        assertThat(review.getSubmittedAt()).isEqualTo(earlier);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("a requested-changes note becomes a CHANGES_REQUESTED review by its author at its time")
    void shouldRecordChangesRequestedFromTheSystemNote() {
        when(reviewRepository.findByNativeIdAndProviderId(any(), any())).thenReturn(Optional.empty());
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Instant requestedAt = Instant.parse("2026-04-15T08:23:58Z");

        PullRequestReview review =
                reconciler.recordChangesRequested(pr, approver, "gid://gitlab/Note/4538627", requestedAt, provider);

        assertThat(review).isNotNull();
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.CHANGES_REQUESTED);
        assertThat(review.getAuthor()).isSameAs(approver);
        assertThat(review.getSubmittedAt()).isEqualTo(requestedAt);
        assertThat(review.getProvider()).isSameAs(provider);
        // Keyed by the note, apart from the approval row and from a discussion's COMMENTED row, so a
        // re-sync finds this row rather than adding another.
        long nativeId = GitLabReviewReconciler.generateChangesRequestedNativeId("gid://gitlab/Note/4538627", 99L);
        assertThat(review.getNativeId()).isEqualTo(nativeId);
        assertThat(nativeId)
                .isNotEqualTo(GitLabMergeRequestProcessor.generateApprovalNativeId(4242L, 99L))
                .isNotEqualTo(GitLabReviewReconciler.generateCommentedReviewNativeId("gid://gitlab/Note/4538627", 99L));
        assertThat(pr.getReviews()).contains(review);
    }

    @Test
    @DisplayName("a re-sync of the same requested-changes note updates the row it made before")
    void shouldReuseTheChangesRequestedRowOnResync() {
        PullRequestReview existing = new PullRequestReview();
        existing.setState(PullRequestReview.State.CHANGES_REQUESTED);
        existing.setSubmittedAt(MERGED_AT);
        long nativeId = GitLabReviewReconciler.generateChangesRequestedNativeId("gid://gitlab/Note/1", 99L);
        when(reviewRepository.findByNativeIdAndProviderId(nativeId, 7L)).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PullRequestReview review =
                reconciler.recordChangesRequested(pr, approver, "gid://gitlab/Note/1", APPROVED_AT, provider);

        assertThat(review).isSameAs(existing);
        assertThat(existing.getSubmittedAt()).isEqualTo(APPROVED_AT);
    }

    @Test
    @DisplayName("an unapproved note dismisses the approval, and a later approved note gives it again")
    void shouldDismissOnUnapprovalAndReapproveOnALaterNote() {
        PullRequestReview review = approval(APPROVED_AT);
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Instant unapprovedAt = APPROVED_AT.plusSeconds(600);
        Instant reapprovedAt = APPROVED_AT.plusSeconds(1_200);

        PullRequestReview dismissed = reconciler.recordUnapproval(pr, approver, unapprovedAt, provider);

        assertThat(dismissed).isSameAs(review);
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        assertThat(review.isDismissed()).isTrue();

        PullRequestReview reapproved = reconciler.recordApproval(pr, approver, provider, true);

        assertThat(reapproved).isSameAs(review);
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        assertThat(review.isDismissed()).isFalse();
        assertThat(review.getSubmittedAt()).isNull();
    }

    @Test
    @DisplayName("an approved note does not revive an approval dismissed for a reason it cannot see")
    void shouldLeaveADismissedApprovalAloneWithoutAnUnapprovalNote() {
        PullRequestReview review = approval(APPROVED_AT);
        // Dismissed by the sync because approvedBy no longer lists the user: a push reset the approvals.
        review.setState(PullRequestReview.State.DISMISSED);
        review.setDismissed(true);

        PullRequestReview result = reconciler.recordApproval(pr, approver, provider, false);

        assertThat(result).isSameAs(review);
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void shouldNotChangeCurrentApprovalMembershipWhenHistoricalWithdrawalAndReapprovalAreReplayed() {
        PullRequestReview review = approval(MERGED_AT);
        review.setSubmittedAt(null);
        for (String body : List.of("unapproved this merge request", "approved this merge request")) {
            assertThat(reconciler.recordSystemNote(
                            pr,
                            approver,
                            new GitLabReviewReconciler.SystemNote(body, APPROVED_AT, "gid://gitlab/Note/1", false),
                            provider))
                    .isFalse();
        }
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        review.setDismissed(true);
        review.setState(PullRequestReview.State.DISMISSED);
        assertThat(reconciler.recordSystemNote(
                        pr,
                        approver,
                        new GitLabReviewReconciler.SystemNote(
                                "approved this merge request", APPROVED_AT, "gid://gitlab/Note/2", false),
                        provider))
                .isFalse();
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        assertThat(review.getSubmittedAt()).isNull();
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("a live approval note creates an undated current approval")
    void shouldCreateTheApprovalFromALiveNoteWhenTheMergeRequestHookHasNotArrived() {
        when(reviewRepository.findByNativeIdAndProviderId(any(), any())).thenReturn(Optional.empty());
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reconciler.recordSystemNote(
                pr,
                approver,
                new GitLabReviewReconciler.SystemNote(
                        "approved this merge request", APPROVED_AT, "gid://gitlab/Note/1", true),
                provider);

        assertThat(pr.getReviews()).singleElement().satisfies(review -> {
            assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(review.getSubmittedAt()).isNull();
            assertThat(review.getAuthor()).isSameAs(approver);
            assertThat(review.getNativeId())
                    .isEqualTo(GitLabMergeRequestProcessor.generateApprovalNativeId(4242L, 99L));
        });
    }

    @Test
    @DisplayName("a live approval note gives a dismissed approval again without an unapproval note")
    void shouldReapproveFromALiveNote() {
        PullRequestReview review = approval(APPROVED_AT);
        review.setState(PullRequestReview.State.DISMISSED);
        review.setDismissed(true);
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reconciler.recordSystemNote(
                pr,
                approver,
                new GitLabReviewReconciler.SystemNote(
                        "approved this merge request", APPROVED_AT.plusSeconds(60), "gid://gitlab/Note/1", true),
                provider);

        assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        assertThat(review.isDismissed()).isFalse();
    }

    @Test
    @DisplayName("a live requested-changes note withdraws its author's approval once, however often it arrives")
    void shouldWithdrawTheRequestersApprovalOnlyForANewRequestForChanges() {
        PullRequestReview review = approval(APPROVED_AT);
        long nativeId = GitLabReviewReconciler.generateChangesRequestedNativeId("gid://gitlab/Note/5", 99L);
        PullRequestReview recorded = new PullRequestReview();
        recorded.setState(PullRequestReview.State.CHANGES_REQUESTED);
        when(reviewRepository.findByNativeIdAndProviderId(nativeId, 7L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(recorded));
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GitLabReviewReconciler.SystemNote note = new GitLabReviewReconciler.SystemNote(
                "requested changes", APPROVED_AT.plusSeconds(60), "gid://gitlab/Note/5", true);

        assertThat(reconciler.recordSystemNote(pr, approver, note, provider)).isTrue();
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.DISMISSED);

        review.setState(PullRequestReview.State.APPROVED);
        review.setDismissed(false);
        assertThat(reconciler.recordSystemNote(pr, approver, note, provider)).isFalse();
        assertThat(review.getState())
                .as("an approval given after the request for changes outlives the note's redelivery")
                .isEqualTo(PullRequestReview.State.APPROVED);
    }

    @Test
    @DisplayName("a note by a user with no recorded approval records nothing")
    void shouldRecordNothingWithoutAnApproval() {
        when(reviewRepository.findByNativeIdAndProviderId(any(), any())).thenReturn(Optional.empty());

        assertThat(reconciler.recordApproval(pr, approver, provider, false)).isNull();
        verify(reviewRepository, never()).save(any());
    }
}
