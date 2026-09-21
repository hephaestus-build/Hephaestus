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
    @DisplayName("an approval stamped with the merge time is moved back to the system note's time")
    void shouldMoveApprovalBackToTheSystemNote() {
        approval(MERGED_AT);
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PullRequestReview review = reconciler.recordApprovalTime(pr, approver, APPROVED_AT, provider);

        assertThat(review).isNotNull();
        assertThat(review.getSubmittedAt()).isEqualTo(APPROVED_AT);
        assertThat(review.getCreatedAt()).isEqualTo(APPROVED_AT);
        verify(reviewRepository).save(review);
    }

    @Test
    @DisplayName("an approval already earlier than the note is left as it stands")
    void shouldKeepAnEarlierApproval() {
        Instant earlier = APPROVED_AT.minusSeconds(60);
        approval(earlier);

        PullRequestReview review = reconciler.recordApprovalTime(pr, approver, APPROVED_AT, provider);

        assertThat(review).isNotNull();
        assertThat(review.getSubmittedAt()).isEqualTo(earlier);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("a note by a user with no recorded approval records nothing")
    void shouldRecordNothingWithoutAnApproval() {
        when(reviewRepository.findByNativeIdAndProviderId(any(), any())).thenReturn(Optional.empty());

        assertThat(reconciler.recordApprovalTime(pr, approver, APPROVED_AT, provider))
                .isNull();
        verify(reviewRepository, never()).save(any());
    }
}
