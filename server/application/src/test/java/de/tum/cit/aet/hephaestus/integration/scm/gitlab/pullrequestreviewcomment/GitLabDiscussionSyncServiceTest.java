package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * The review decisions GitLab writes as system notes, read by the discussion sync. The GraphQL page
 * loop is not driven here; the per-note reading is, with the notes' bodies as GitLab writes them.
 */
class GitLabDiscussionSyncServiceTest extends BaseUnitTest {

    private static final long PROVIDER_ID = 2L;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private GitLabPullRequestReviewThreadProcessor threadProcessor;

    @Mock
    private GitLabPullRequestReviewCommentProcessor reviewCommentProcessor;

    @Mock
    private GitLabIssueCommentProcessor issueCommentProcessor;

    @Mock
    private GitLabReviewReconciler reviewReconciler;

    @Mock
    private GitLabProperties gitLabProperties;

    private GitLabDiscussionSyncService service;
    private IdentityProvider provider;
    private PullRequest pr;
    private User reviewer;

    @BeforeEach
    void setUp() {
        service = new GitLabDiscussionSyncService(
                graphQlClientProvider,
                responseHandler,
                threadProcessor,
                reviewCommentProcessor,
                issueCommentProcessor,
                reviewReconciler,
                gitLabProperties);
        provider = new IdentityProvider();
        provider.setId(PROVIDER_ID);
        pr = new PullRequest();
        pr.setNativeId(343218L);
        pr.setProvider(provider);
        reviewer = new User();
        reviewer.setNativeId(31315L);
        reviewer.setLogin("simon.christian.winter");
        lenient()
                .when(issueCommentProcessor.findOrCreateUser(any(), eq(PROVIDER_ID)))
                .thenReturn(reviewer);
    }

    private static Map<String, Object> systemNote(String id, String body, String createdAt) {
        return Map.of(
                "id",
                "gid://gitlab/Note/" + id,
                "body",
                body,
                "system",
                true,
                "createdAt",
                createdAt,
                "author",
                Map.of(
                        "id", "gid://gitlab/User/31315",
                        "username", "simon.christian.winter",
                        "name", "Simon Winter"));
    }

    @Test
    void shouldHandARequestedChangesNoteToTheReconcilerAsAReplayedNote() {
        service.recordReviewDecisionFromSystemNote(
                systemNote("4538627", "requested changes", "2026-04-15T10:23:58.766+02:00"),
                pr,
                PROVIDER_ID,
                new HashSet<>());

        verify(reviewReconciler)
                .recordSystemNote(
                        eq(pr),
                        eq(reviewer),
                        eq(new GitLabReviewReconciler.SystemNote(
                                "requested changes",
                                Instant.parse("2026-04-15T08:23:58.766Z"),
                                "gid://gitlab/Note/4538627",
                                false)),
                        eq(provider),
                        any());
    }

    @Test
    void shouldHandEveryDecisionNoteOfOnePassTheSameUnapprovalSet() {
        Set<Long> unapproved = new HashSet<>();

        service.recordReviewDecisionFromSystemNote(
                systemNote("1", "approved this merge request", "2026-04-15T10:00:00+02:00"),
                pr,
                PROVIDER_ID,
                unapproved);
        service.recordReviewDecisionFromSystemNote(
                systemNote("2", "unapproved this merge request", "2026-04-15T11:00:00+02:00"),
                pr,
                PROVIDER_ID,
                unapproved);

        // Replayed, never live: the reconciler decides re-approval from the set it is handed.
        verify(reviewReconciler)
                .recordSystemNote(
                        eq(pr),
                        eq(reviewer),
                        eq(new GitLabReviewReconciler.SystemNote(
                                "approved this merge request",
                                Instant.parse("2026-04-15T08:00:00Z"),
                                "gid://gitlab/Note/1",
                                false)),
                        eq(provider),
                        same(unapproved));
        verify(reviewReconciler)
                .recordSystemNote(
                        eq(pr),
                        eq(reviewer),
                        eq(new GitLabReviewReconciler.SystemNote(
                                "unapproved this merge request",
                                Instant.parse("2026-04-15T09:00:00Z"),
                                "gid://gitlab/Note/2",
                                false)),
                        eq(provider),
                        same(unapproved));
    }

    @Test
    void shouldIgnoreASystemNoteThatIsNotAReviewDecision() {
        service.recordReviewDecisionFromSystemNote(
                systemNote("5", "requested review from @jennifer.wagner", "2026-04-15T10:00:00+02:00"),
                pr,
                PROVIDER_ID,
                new HashSet<>());

        verifyNoInteractions(reviewReconciler);
    }
}
