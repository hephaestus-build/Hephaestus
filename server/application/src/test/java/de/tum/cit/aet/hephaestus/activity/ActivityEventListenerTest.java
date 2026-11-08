package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.TransientDataAccessResourceException;

@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityEventListenerTest extends BaseUnitTest {

    @Mock
    private ActivityEventService activityEventService;

    @Mock
    private ActivityFactDelivery activityFacts;

    @Mock
    private PullRequestReviewThreadRepository reviewThreadRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    private ActivityEventListener listener;

    private User testUser;
    private Repository testRepository;

    @BeforeEach
    void setUp() {
        listener = new ActivityEventListener(
                activityEventService, activityFacts, reviewThreadRepository, userRepository, repositoryRepository);

        testUser = new User();
        testUser.setId(100L);
        testUser.setLogin("testuser");

        testRepository = new Repository();
        testRepository.setId(200L);
        testRepository.setName("test-repo");

        // Set up getReferenceById mocks - these return proxy references without DB queries
        when(userRepository.getReferenceById(100L)).thenReturn(testUser);
        when(repositoryRepository.getReferenceById(200L)).thenReturn(testRepository);
        when(userRepository.findById(100L)).thenReturn(Optional.of(testUser));
    }

    @Nested
    class PullRequestCreatedTests {

        @Test
        void recordsPullRequestOpened() {
            PullRequest pullRequest = createPullRequest(1L);

            var event = new ScmDomainEvent.PullRequestCreated(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestCreated(event);

            assertRecorded(
                    42L,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    1L,
                    null);
            verify(userRepository).getReferenceById(100L);
            verify(repositoryRepository).getReferenceById(200L);
        }

        @Test
        void shouldRollBackSourceWhenFactPublicationFails() {
            var failure = new TransientDataAccessResourceException("database unavailable");
            doThrow(failure).when(activityFacts).publish(any());
            var event = new ScmDomainEvent.PullRequestCreated(
                    createPullRequestData(createPullRequest(1L)), createContext());

            assertThatThrownBy(() -> listener.onPullRequestCreated(event)).isSameAs(failure);
        }

        @Test
        void noOpWhenAuthorIdMissing() {
            PullRequest pullRequest = createPullRequest(1L);
            pullRequest.setAuthor(null); // No author

            var event = new ScmDomainEvent.PullRequestCreated(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestCreated(event);

            verifyNoInteractions(activityFacts);
        }
    }

    @Nested
    class PullRequestMergedTests {

        @Test
        @DisplayName("attributes the merge to the pull request author, not the merger")
        void recordsPullRequestMergedWithAuthorAsActor() {
            User merger = new User();
            merger.setId(999L);
            merger.setLogin("merger");
            PullRequest pullRequest = createPullRequest(2L);
            pullRequest.setMergedAt(Instant.now());
            pullRequest.setMergedBy(merger);

            var event = new ScmDomainEvent.PullRequestMerged(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestMerged(event);

            assertRecorded(
                    42L,
                    ActivityEventType.PULL_REQUEST_MERGED,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    2L,
                    null);
            verify(userRepository, never()).getReferenceById(999L);
        }

        @Test
        void noOpWhenAuthorIdMissing() {
            User merger = new User();
            merger.setId(999L);
            merger.setLogin("merger");
            PullRequest pullRequest = createPullRequest(3L);
            pullRequest.setAuthor(null);
            pullRequest.setMergedAt(Instant.now());
            pullRequest.setMergedBy(merger);

            var event = new ScmDomainEvent.PullRequestMerged(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestMerged(event);

            verifyNoInteractions(activityFacts);
        }
    }

    @Nested
    class PullRequestClosedTests {

        @Test
        void recordsPullRequestClosed() {
            PullRequest pullRequest = createPullRequest(3L);
            pullRequest.setClosedAt(Instant.now());

            var event =
                    new ScmDomainEvent.PullRequestClosed(createPullRequestData(pullRequest), false, createContext());

            listener.onPullRequestClosed(event);

            assertRecorded(
                    42L,
                    ActivityEventType.PULL_REQUEST_CLOSED,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    3L,
                    null);
        }

        @Test
        @DisplayName("does nothing when pull request was merged (handled by onPullRequestMerged)")
        void noOpWhenMerged() {
            var event = new ScmDomainEvent.PullRequestClosed(
                    createPullRequestData(createPullRequest(4L)), true, createContext());

            listener.onPullRequestClosed(event);

            verifyNoInteractions(activityFacts);
        }
    }

    @Nested
    class PullRequestReopenedTests {

        @Test
        void recordsPullRequestReopened() {
            PullRequest pullRequest = createPullRequest(5L);

            var event = new ScmDomainEvent.PullRequestReopened(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestReopened(event);

            assertRecorded(
                    42L,
                    ActivityEventType.PULL_REQUEST_REOPENED,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    5L,
                    null);
        }
    }

    @Nested
    class PullRequestReadyTests {

        @Test
        void recordsPullRequestReady() {
            PullRequest pullRequest = createPullRequest(6L);

            var event = new ScmDomainEvent.PullRequestReady(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestReady(event);

            assertRecorded(
                    42L,
                    ActivityEventType.PULL_REQUEST_READY,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    6L,
                    null);
        }
    }

    @Nested
    class ReviewSubmittedTests {
        @Test
        void shouldNotRecordAnUndatedApprovalOrItsEditAsActivityToday() {
            PullRequestReview review = createReview(18L, createPullRequest(19L));
            review.setState(PullRequestReview.State.APPROVED);
            review.setSubmittedAt(null);

            listener.onReviewSubmitted(new ScmDomainEvent.ReviewSubmitted(createReviewData(review), createContext()));
            listener.onReviewEdited(
                    new ScmDomainEvent.ReviewEdited(createReviewData(review), Set.of("body"), createContext()));

            verifyNoInteractions(activityFacts);
        }

        @Test
        @DisplayName("maps APPROVED review state to REVIEW_APPROVED event type using event data")
        void recordsReviewApproved() {
            PullRequest pullRequest = createPullRequest(10L);
            PullRequestReview review = createReview(5L, pullRequest);
            review.setState(PullRequestReview.State.APPROVED);

            var event = new ScmDomainEvent.ReviewSubmitted(createReviewData(review), createContext());

            listener.onReviewSubmitted(event);

            assertRecorded(
                    42L,
                    ActivityEventType.REVIEW_APPROVED,
                    testUser,
                    testRepository,
                    ActivityTargetType.REVIEW,
                    5L,
                    null);
        }

        @Test
        void handlesMissingAuthor() {
            PullRequest pullRequest = createPullRequest(1L);
            PullRequestReview review = createReview(6L, pullRequest);
            review.setAuthor(null); // No author

            var event = new ScmDomainEvent.ReviewSubmitted(createReviewData(review), createContext());

            listener.onReviewSubmitted(event);

            verifyNoInteractions(activityFacts);
        }
    }

    @Nested
    class IssueCreatedTests {

        @Test
        void recordsIssueCreated() {
            Issue issue = createIssue(10L);

            var event = new ScmDomainEvent.IssueCreated(createIssueData(issue), createContext());

            listener.onIssueCreated(event);

            assertRecorded(
                    42L,
                    ActivityEventType.ISSUE_CREATED,
                    testUser,
                    testRepository,
                    ActivityTargetType.ISSUE,
                    10L,
                    null);
        }

        @Test
        void skipsPullRequests() {
            PullRequest pullRequest = createPullRequest(11L);

            var event = new ScmDomainEvent.IssueCreated(ScmEventPayload.IssueData.from(pullRequest), createContext());

            listener.onIssueCreated(event);

            verifyNoInteractions(activityFacts);
        }

        @Test
        void recordsIssueWithNullAuthor() {
            // Create issue WITHOUT author - simulates deleted GitHub user or bot
            Issue issue = createIssue(14L);
            issue.setAuthor(null);

            var event = new ScmDomainEvent.IssueCreated(createIssueData(issue), createContext());

            listener.onIssueCreated(event);

            // Recorded for the audit trail, with no actor
            assertRecorded(
                    42L, ActivityEventType.ISSUE_CREATED, null, testRepository, ActivityTargetType.ISSUE, 14L, null);
        }
    }

    @Nested
    class IssueClosedTests {

        @Test
        void recordsIssueClosed() {
            Issue issue = createIssue(12L);
            issue.setClosedAt(Instant.now());

            var event = new ScmDomainEvent.IssueClosed(createIssueData(issue), null, createContext());

            listener.onIssueClosed(event);

            assertRecorded(
                    42L, ActivityEventType.ISSUE_CLOSED, testUser, testRepository, ActivityTargetType.ISSUE, 12L, null);
        }

        @Test
        void skipsPullRequests() {
            PullRequest pullRequest = createPullRequest(13L);

            var event =
                    new ScmDomainEvent.IssueClosed(ScmEventPayload.IssueData.from(pullRequest), null, createContext());

            listener.onIssueClosed(event);

            verifyNoInteractions(activityFacts);
        }

        @Test
        void recordsIssueClosedWithNullAuthor() {
            // Create issue WITHOUT author - simulates deleted GitHub user or bot
            Issue issue = createIssue(15L);
            issue.setAuthor(null);
            issue.setClosedAt(Instant.now());

            var event = new ScmDomainEvent.IssueClosed(createIssueData(issue), null, createContext());

            listener.onIssueClosed(event);

            // Recorded for the audit trail, with no actor
            assertRecorded(
                    42L, ActivityEventType.ISSUE_CLOSED, null, testRepository, ActivityTargetType.ISSUE, 15L, null);
        }
    }

    @Nested
    class CommitCreatedTests {

        @Test
        void recordsCommitCreated() {
            Commit commit = createCommit(20L);

            var event = new ScmDomainEvent.CommitCreated(createCommitData(commit), createContext());

            listener.onCommitCreated(event);

            assertRecorded(
                    42L,
                    ActivityEventType.COMMIT_CREATED,
                    testUser,
                    testRepository,
                    ActivityTargetType.COMMIT,
                    20L,
                    null);
        }

        @Test
        void recordsCommitWithNullAuthor() {
            Commit commit = createCommit(21L);
            commit.setAuthor(null);

            var event = new ScmDomainEvent.CommitCreated(createCommitData(commit), createContext());

            listener.onCommitCreated(event);

            // Recorded for the audit trail, with no actor
            assertRecorded(
                    42L, ActivityEventType.COMMIT_CREATED, null, testRepository, ActivityTargetType.COMMIT, 21L, null);
        }

        @Test
        void skipsCommitWhenScopeIdIsNull() {
            Commit commit = createCommit(22L);

            RepositoryRef repoRef = new RepositoryRef(testRepository.getId(), testRepository.getName(), "test");
            EventContext contextWithNullScope = new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    null, // null scopeId
                    repoRef,
                    DataSource.WEBHOOK,
                    null,
                    UUID.randomUUID().toString(),
                    null);
            var event = new ScmDomainEvent.CommitCreated(createCommitData(commit), contextWithNullScope);

            listener.onCommitCreated(event);

            verifyNoInteractions(activityFacts);
        }
    }

    @Nested
    class ReviewCommentCreatedTests {

        @Test
        @DisplayName("records a standalone review comment (no linked review) like a linked one")
        void recordsStandaloneReviewComment() {
            var commentData = new ScmEventPayload.ReviewCommentData(
                    77L, // id
                    "This is a standalone diff note", // body
                    "src/Main.java", // path
                    42, // line
                    "https://github.com/test/test-repo/pull/1#discussion_r77", // htmlUrl
                    null, // reviewId - null = standalone
                    100L, // authorId
                    Instant.now(), // createdAt
                    null, // updatedAt
                    50L, // pullRequestId
                    200L // repositoryId
                    );
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, createContext());

            listener.onReviewCommentCreated(event);

            assertRecorded(
                    42L,
                    ActivityEventType.REVIEW_COMMENT_CREATED,
                    testUser,
                    testRepository,
                    ActivityTargetType.REVIEW_COMMENT,
                    77L,
                    null);
        }

        @Test
        void recordsReviewCommentLinkedToReview() {
            var commentData = new ScmEventPayload.ReviewCommentData(
                    78L, // id
                    "Some comment", // body
                    "src/Main.java", // path
                    10, // line
                    "https://github.com/test/test-repo/pull/1#discussion_r78", // htmlUrl
                    42L, // reviewId - linked to review
                    100L, // authorId
                    Instant.now(), // createdAt
                    null, // updatedAt
                    50L, // pullRequestId
                    200L // repositoryId
                    );
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, createContext());

            listener.onReviewCommentCreated(event);

            assertRecorded(
                    42L,
                    ActivityEventType.REVIEW_COMMENT_CREATED,
                    testUser,
                    testRepository,
                    ActivityTargetType.REVIEW_COMMENT,
                    78L,
                    null);
        }

        @Test
        void skipsWhenScopeIdIsNull() {
            var commentData = new ScmEventPayload.ReviewCommentData(
                    79L,
                    "body",
                    "path.java",
                    1,
                    "https://example.com/comment",
                    null,
                    100L,
                    Instant.now(),
                    null,
                    50L,
                    200L);
            RepositoryRef repoRef = new RepositoryRef(testRepository.getId(), testRepository.getName(), "test");
            EventContext contextWithNullScope = new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    null, // null scopeId
                    repoRef,
                    DataSource.WEBHOOK,
                    null,
                    UUID.randomUUID().toString(),
                    null);
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, contextWithNullScope);

            listener.onReviewCommentCreated(event);

            verifyNoInteractions(activityFacts);
        }

        @Test
        void skipsWhenAuthorIdIsNull() {
            var commentData = new ScmEventPayload.ReviewCommentData(
                    80L,
                    "body",
                    "path.java",
                    1,
                    "https://example.com/comment",
                    null, // reviewId
                    null, // authorId - null
                    Instant.now(),
                    null,
                    50L,
                    200L);
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, createContext());

            listener.onReviewCommentCreated(event);

            verifyNoInteractions(activityFacts);
        }
    }

    @Nested
    class CommitAuthorsReconciledTests {

        @Test
        void backfillsCommitActorsOnReconciliation() {
            when(activityEventService.backfillCommitActors(200L)).thenReturn(3);

            var event = new ScmDomainEvent.CommitAuthorsReconciled(200L, createContext());

            listener.onCommitAuthorsReconciled(event);

            verify(activityEventService).backfillCommitActors(200L);
        }

        @Test
        void noOpWhenRepositoryIdMissing() {
            var event = new ScmDomainEvent.CommitAuthorsReconciled(null, createContext());

            listener.onCommitAuthorsReconciled(event);

            verify(activityEventService, never()).backfillCommitActors(anyLong());
        }

        @Test
        void swallowsBackfillExceptions() {
            when(activityEventService.backfillCommitActors(200L)).thenThrow(new RuntimeException("db outage"));

            var event = new ScmDomainEvent.CommitAuthorsReconciled(200L, createContext());

            listener.onCommitAuthorsReconciled(event);

            verify(activityEventService).backfillCommitActors(200L);
        }
    }

    private void assertRecorded(
            Long workspaceId,
            ActivityEventType type,
            @Nullable User actor,
            @Nullable Repository repository,
            ActivityTargetType targetType,
            Long targetId,
            @Nullable String sourceKey) {
        var captured = ArgumentCaptor.forClass(ActivityFact.class);
        verify(activityFacts).publish(captured.capture());
        var fact = captured.getValue();
        assertThat(fact)
                .extracting(
                        ActivityFact::workspaceId,
                        ActivityFact::eventType,
                        ActivityFact::actorId,
                        ActivityFact::repositoryId,
                        ActivityFact::targetType,
                        ActivityFact::targetId,
                        ActivityFact::sourceKey)
                .containsExactly(
                        workspaceId,
                        type,
                        actor != null ? actor.getId() : null,
                        repository != null ? repository.getId() : null,
                        targetType,
                        targetId,
                        sourceKey);
        assertThat(fact.occurredAt()).isNotNull();
    }

    private Commit createCommit(Long id) {
        Commit commit = new Commit();
        commit.setId(id);
        commit.setSha("abc123def456789012345678901234567890abcd");
        commit.setMessage("Test commit message");
        commit.setAuthoredAt(Instant.now());
        commit.setCommittedAt(Instant.now());
        commit.setAuthor(testUser);
        commit.setRepository(testRepository);
        return commit;
    }

    private ScmEventPayload.CommitData createCommitData(Commit commit) {
        return ScmEventPayload.CommitData.from(commit);
    }

    private PullRequest createPullRequest(Long id) {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(id);
        pullRequest.setNumber(1);
        pullRequest.setTitle("Test Pull Request");
        pullRequest.setState(PullRequest.State.OPEN);
        pullRequest.setAuthor(testUser);
        pullRequest.setRepository(testRepository);
        pullRequest.setCreatedAt(Instant.now());
        pullRequest.setUpdatedAt(Instant.now());
        pullRequest.setHtmlUrl("https://github.com/test/test-repo/pull/1");
        return pullRequest;
    }

    private Issue createIssue(Long id) {
        Issue issue = new Issue();
        issue.setId(id);
        issue.setNumber(100);
        issue.setTitle("Test Issue");
        issue.setState(Issue.State.OPEN);
        issue.setAuthor(testUser);
        issue.setRepository(testRepository);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setHtmlUrl("https://github.com/test/test-repo/issues/100");
        return issue;
    }

    private PullRequestReview createReview(Long id, PullRequest pullRequest) {
        PullRequestReview review = new PullRequestReview();
        review.setId(id);
        review.setAuthor(testUser);
        review.setPullRequest(pullRequest);
        review.setSubmittedAt(Instant.now());
        review.setState(PullRequestReview.State.APPROVED);
        return review;
    }

    private ScmEventPayload.PullRequestData createPullRequestData(PullRequest pullRequest) {
        return ScmEventPayload.PullRequestData.from(pullRequest);
    }

    private ScmEventPayload.ReviewData createReviewData(PullRequestReview review) {
        return ScmEventPayload.ReviewData.from(review).orElseThrow();
    }

    private ScmEventPayload.IssueData createIssueData(Issue issue) {
        return ScmEventPayload.IssueData.from(issue);
    }

    private EventContext createContext() {
        RepositoryRef repoRef = new RepositoryRef(testRepository.getId(), testRepository.getName(), "test");
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                42L,
                repoRef,
                DataSource.WEBHOOK,
                null,
                UUID.randomUUID().toString(),
                null);
    }
}
