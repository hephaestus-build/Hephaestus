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

    /** The approver's stored approval, the row a note would change if notes changed approvals. */
    private PullRequestReview approval(Instant submittedAt) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(GitLabMergeRequestProcessor.generateApprovalNativeId(4242L, 99L));
        review.setState(PullRequestReview.State.APPROVED);
        review.setSubmittedAt(submittedAt);
        review.setCreatedAt(submittedAt);
        review.setAuthor(approver);
        return review;
    }

    private boolean note(String body, Instant at, String globalId, boolean live) {
        return reconciler.recordSystemNote(
                pr, approver, new GitLabReviewReconciler.SystemNote(body, at, globalId, live), provider);
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
    @DisplayName("replayed approval and withdrawal notes change no approval and ask for no read")
    void shouldNotChangeCurrentApprovalMembershipWhenHistoricalWithdrawalAndReapprovalAreReplayed() {
        PullRequestReview review = approval(MERGED_AT);
        review.setSubmittedAt(null);
        for (String body : List.of("unapproved this merge request", "approved this merge request")) {
            assertThat(note(body, APPROVED_AT, "gid://gitlab/Note/1", false)).isFalse();
        }
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        review.setDismissed(true);
        review.setState(PullRequestReview.State.DISMISSED);
        assertThat(note("approved this merge request", APPROVED_AT, "gid://gitlab/Note/2", false))
                .isFalse();
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        assertThat(review.getSubmittedAt()).isNull();
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("a live approval note creates no approval: it only asks for GitLab's approvals to be read again")
    void shouldCreateNoApprovalFromALiveNote() {
        assertThat(note("approved this merge request", APPROVED_AT, "gid://gitlab/Note/1", true))
                .isTrue();

        assertThat(pr.getReviews()).isEmpty();
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("live notes neither give a dismissed approval again nor dismiss a standing one")
    void shouldLeaveApprovalMembershipToTheReadWhenLiveNotesArrive() {
        PullRequestReview review = approval(APPROVED_AT);
        review.setState(PullRequestReview.State.DISMISSED);
        review.setDismissed(true);

        assertThat(note("approved this merge request", APPROVED_AT.plusSeconds(60), "gid://gitlab/Note/1", true))
                .isTrue();
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.DISMISSED);

        review.setState(PullRequestReview.State.APPROVED);
        review.setDismissed(false);
        assertThat(note("unapproved this merge request", APPROVED_AT.plusSeconds(120), "gid://gitlab/Note/2", true))
                .isTrue();
        assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        assertThat(review.isDismissed()).isFalse();
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("a live requested-changes note is recorded and asks for a read once, however often it arrives")
    void shouldAskForAReadOnlyForANewRequestForChanges() {
        PullRequestReview review = approval(APPROVED_AT);
        long nativeId = GitLabReviewReconciler.generateChangesRequestedNativeId("gid://gitlab/Note/5", 99L);
        PullRequestReview recorded = new PullRequestReview();
        recorded.setState(PullRequestReview.State.CHANGES_REQUESTED);
        when(reviewRepository.findByNativeIdAndProviderId(nativeId, 7L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(recorded));
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(note("requested changes", APPROVED_AT.plusSeconds(60), "gid://gitlab/Note/5", true))
                .isTrue();
        assertThat(review.getState())
                .as("the approval the request may have withdrawn is GitLab's approver list's to say")
                .isEqualTo(PullRequestReview.State.APPROVED);
        assertThat(pr.getReviews())
                .anySatisfy(row -> assertThat(row.getState()).isEqualTo(PullRequestReview.State.CHANGES_REQUESTED));

        assertThat(note("requested changes", APPROVED_AT.plusSeconds(60), "gid://gitlab/Note/5", true))
                .isFalse();
    }

    @Test
    @DisplayName("a delayed live approval note does not date the approval that stands")
    void shouldNotDateAStandingApprovalFromALiveNote() {
        PullRequestReview review = approval(APPROVED_AT);
        review.setSubmittedAt(null);

        note("approved this merge request", APPROVED_AT.minusSeconds(600), "gid://gitlab/Note/1", true);

        assertThat(review.getSubmittedAt()).isNull();
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("GitLab's own approval date before the merge stands when its note is recorded after the merge")
    void shouldKeepTheNativeApprovalDateAgainstANoteRecordedAfterTheMerge() {
        // The approval worker writes its note later, from the reloaded merge request, without the approval's time.
        PullRequestReview review = approval(MERGED_AT.minusSeconds(31));

        note("approved this merge request", MERGED_AT.plusSeconds(5), "gid://gitlab/Note/1", true);

        assertThat(review.getSubmittedAt()).isEqualTo(MERGED_AT.minusSeconds(31));
        assertThat(review.getCommitId()).isNull();
        verify(reviewRepository, never()).save(any());
    }
}
