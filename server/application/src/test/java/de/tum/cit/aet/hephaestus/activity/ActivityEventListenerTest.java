package de.tum.cit.aet.hephaestus.activity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.Discussion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussioncomment.DiscussionComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for ActivityEventListener.
 *
 * <p>Tests verify that activity events are correctly recorded using event payload data
 * and getReferenceById() for entity references (no N+1 queries).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityEventListenerTest extends BaseUnitTest {

    @Mock
    private ActivityEventService activityEventService;

    @Mock
    private ActivityEventRepository activityEventRepository;

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
                activityEventService,
                activityEventRepository,
                reviewThreadRepository,
                userRepository,
                repositoryRepository);

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

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.PULL_REQUEST_OPENED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.PULL_REQUEST),
                            eq(1L));
            // Verify no findById was called (N+1 fix)
            verify(userRepository).getReferenceById(100L);
            verify(repositoryRepository).getReferenceById(200L);
        }

        @Test
        void noOpWhenAuthorIdMissing() {
            PullRequest pullRequest = createPullRequest(1L);
            pullRequest.setAuthor(null); // No author

            var event = new ScmDomainEvent.PullRequestCreated(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestCreated(event);

            verifyNoInteractions(activityEventService);
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

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.PULL_REQUEST_MERGED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.PULL_REQUEST),
                            eq(2L));
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

            verifyNoInteractions(activityEventService);
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

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.PULL_REQUEST_CLOSED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.PULL_REQUEST),
                            eq(3L));
        }

        @Test
        @DisplayName("does nothing when pull request was merged (handled by onPullRequestMerged)")
        void noOpWhenMerged() {
            var event = new ScmDomainEvent.PullRequestClosed(
                    createPullRequestData(createPullRequest(4L)), true, createContext());

            listener.onPullRequestClosed(event);

            verifyNoInteractions(activityEventService);
        }
    }

    @Nested
    class PullRequestReopenedTests {

        @Test
        void recordsPullRequestReopened() {
            PullRequest pullRequest = createPullRequest(5L);

            var event = new ScmDomainEvent.PullRequestReopened(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestReopened(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.PULL_REQUEST_REOPENED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.PULL_REQUEST),
                            eq(5L));
        }
    }

    @Nested
    class PullRequestReadyTests {

        @Test
        void recordsPullRequestReady() {
            PullRequest pullRequest = createPullRequest(6L);

            var event = new ScmDomainEvent.PullRequestReady(createPullRequestData(pullRequest), createContext());

            listener.onPullRequestReady(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.PULL_REQUEST_READY),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.PULL_REQUEST),
                            eq(6L));
        }
    }

    @Nested
    class ReviewSubmittedTests {

        @Test
        @DisplayName("maps APPROVED review state to REVIEW_APPROVED event type using event data")
        void recordsReviewApproved() {
            PullRequest pullRequest = createPullRequest(10L);
            PullRequestReview review = createReview(5L, pullRequest);
            review.setState(PullRequestReview.State.APPROVED);

            var event = new ScmDomainEvent.ReviewSubmitted(createReviewData(review), createContext());

            listener.onReviewSubmitted(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.REVIEW_APPROVED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.REVIEW),
                            eq(5L));
        }

        @Test
        void handlesMissingAuthor() {
            PullRequest pullRequest = createPullRequest(1L);
            PullRequestReview review = createReview(6L, pullRequest);
            review.setAuthor(null); // No author

            var event = new ScmDomainEvent.ReviewSubmitted(createReviewData(review), createContext());

            listener.onReviewSubmitted(event);

            verifyNoInteractions(activityEventService);
        }
    }

    @Nested
    class IssueCreatedTests {

        @Test
        void recordsIssueCreated() {
            Issue issue = createIssue(10L);

            var event = new ScmDomainEvent.IssueCreated(createIssueData(issue), createContext());

            listener.onIssueCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.ISSUE_CREATED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.ISSUE),
                            eq(10L));
        }

        @Test
        void skipsPullRequests() {
            PullRequest pullRequest = createPullRequest(11L);

            var event = new ScmDomainEvent.IssueCreated(ScmEventPayload.IssueData.from(pullRequest), createContext());

            listener.onIssueCreated(event);

            verifyNoInteractions(activityEventService);
        }

        @Test
        void recordsIssueWithNullAuthor() {
            // Create issue WITHOUT author - simulates deleted GitHub user or bot
            Issue issue = createIssue(14L);
            issue.setAuthor(null);

            var event = new ScmDomainEvent.IssueCreated(createIssueData(issue), createContext());

            listener.onIssueCreated(event);

            // Recorded for the audit trail, with no actor
            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.ISSUE_CREATED),
                            any(Instant.class),
                            isNull(), // null actor - user deleted or bot
                            eq(testRepository),
                            eq(ActivityTargetType.ISSUE),
                            eq(14L));
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

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.ISSUE_CLOSED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.ISSUE),
                            eq(12L));
        }

        @Test
        void skipsPullRequests() {
            PullRequest pullRequest = createPullRequest(13L);

            var event =
                    new ScmDomainEvent.IssueClosed(ScmEventPayload.IssueData.from(pullRequest), null, createContext());

            listener.onIssueClosed(event);

            verifyNoInteractions(activityEventService);
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
            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.ISSUE_CLOSED),
                            any(Instant.class),
                            isNull(), // null actor - user deleted or bot
                            eq(testRepository),
                            eq(ActivityTargetType.ISSUE),
                            eq(15L));
        }
    }

    @Nested
    class CommitCreatedTests {

        @Test
        void recordsCommitCreated() {
            Commit commit = createCommit(20L);

            var event = new ScmDomainEvent.CommitCreated(createCommitData(commit), createContext());

            listener.onCommitCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.COMMIT_CREATED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.COMMIT),
                            eq(20L));
        }

        @Test
        void recordsCommitWithNullAuthor() {
            Commit commit = createCommit(21L);
            commit.setAuthor(null);

            var event = new ScmDomainEvent.CommitCreated(createCommitData(commit), createContext());

            listener.onCommitCreated(event);

            // Recorded for the audit trail, with no actor
            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.COMMIT_CREATED),
                            any(Instant.class),
                            isNull(), // null actor - user deleted or bot
                            eq(testRepository),
                            eq(ActivityTargetType.COMMIT),
                            eq(21L));
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

            verifyNoInteractions(activityEventService);
        }
    }

    @Nested
    class DiscussionCreatedTests {

        @Test
        void recordsDiscussionCreated() {
            Discussion discussion = createDiscussion(30L);

            var event = new ScmDomainEvent.DiscussionCreated(createDiscussionData(discussion), createContext());

            listener.onDiscussionCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_CREATED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(30L));
        }

        @Test
        void recordsDiscussionWithNullAuthor() {
            Discussion discussion = createDiscussion(31L);
            discussion.setAuthor(null);

            var event = new ScmDomainEvent.DiscussionCreated(createDiscussionData(discussion), createContext());

            listener.onDiscussionCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_CREATED),
                            any(Instant.class),
                            isNull(),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(31L));
        }
    }

    @Nested
    class DiscussionClosedTests {

        @Test
        void recordsDiscussionClosed() {
            Discussion discussion = createDiscussion(32L);
            discussion.setClosedAt(Instant.now());

            var event =
                    new ScmDomainEvent.DiscussionClosed(createDiscussionData(discussion), "resolved", createContext());

            listener.onDiscussionClosed(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_CLOSED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(32L));
        }
    }

    @Nested
    class DiscussionReopenedTests {

        @Test
        void recordsDiscussionReopened() {
            Discussion discussion = createDiscussion(33L);

            var event = new ScmDomainEvent.DiscussionReopened(createDiscussionData(discussion), createContext());

            listener.onDiscussionReopened(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_REOPENED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(33L));
        }
    }

    @Nested
    class DiscussionAnsweredTests {

        @Test
        void recordsDiscussionAnswered() {
            Discussion discussion = createDiscussion(34L);
            discussion.setAnswerChosenAt(Instant.now());

            var event = new ScmDomainEvent.DiscussionAnswered(createDiscussionData(discussion), 999L, createContext());

            listener.onDiscussionAnswered(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_ANSWERED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(34L));
        }

        @Test
        void recordsDiscussionAnsweredWithNullAuthor() {
            Discussion discussion = createDiscussion(35L);
            discussion.setAuthor(null);
            discussion.setAnswerChosenAt(Instant.now());

            var event = new ScmDomainEvent.DiscussionAnswered(createDiscussionData(discussion), 999L, createContext());

            listener.onDiscussionAnswered(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_ANSWERED),
                            any(Instant.class),
                            isNull(),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(35L));
        }
    }

    @Nested
    class DiscussionDeletedTests {

        @Test
        void recordsDiscussionDeleted() {
            var event = new ScmDomainEvent.DiscussionDeleted(36L, createContext());

            listener.onDiscussionDeleted(event);

            verify(activityEventService)
                    .recordDeleted(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_DELETED),
                            any(Instant.class),
                            eq(ActivityTargetType.DISCUSSION),
                            eq(36L));
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
                    50L, // pullRequestId
                    200L // repositoryId
                    );
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, createContext());

            listener.onReviewCommentCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.REVIEW_COMMENT_CREATED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.REVIEW_COMMENT),
                            eq(77L));
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
                    50L, // pullRequestId
                    200L // repositoryId
                    );
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, createContext());

            listener.onReviewCommentCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.REVIEW_COMMENT_CREATED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.REVIEW_COMMENT),
                            eq(78L));
        }

        @Test
        void skipsWhenScopeIdIsNull() {
            var commentData = new ScmEventPayload.ReviewCommentData(
                    79L, "body", "path.java", 1, "https://example.com/comment", null, 100L, Instant.now(), 50L, 200L);
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

            verifyNoInteractions(activityEventService);
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
                    50L,
                    200L);
            var event = new ScmDomainEvent.ReviewCommentCreated(commentData, 50L, createContext());

            listener.onReviewCommentCreated(event);

            verifyNoInteractions(activityEventService);
        }
    }

    @Nested
    class DiscussionCommentCreatedTests {

        @Test
        void recordsDiscussionCommentCreated() {
            DiscussionComment comment = createDiscussionComment(37L);

            var event = new ScmDomainEvent.DiscussionCommentCreated(
                    createDiscussionCommentData(comment),
                    30L, // discussionId
                    createContext());

            listener.onDiscussionCommentCreated(event);

            verify(activityEventService)
                    .record(
                            eq(42L),
                            eq(ActivityEventType.DISCUSSION_COMMENT_CREATED),
                            any(Instant.class),
                            eq(testUser),
                            eq(testRepository),
                            eq(ActivityTargetType.DISCUSSION_COMMENT),
                            eq(37L));
        }

        @Test
        void skipsCommentWhenAuthorIsNull() {
            DiscussionComment comment = createDiscussionComment(38L);
            comment.setAuthor(null);

            var event = new ScmDomainEvent.DiscussionCommentCreated(
                    createDiscussionCommentData(comment), 30L, createContext());

            listener.onDiscussionCommentCreated(event);

            verifyNoInteractions(activityEventService);
        }
    }

    @Nested
    class CommitAuthorsReconciledTests {

        @Test
        void backfillsCommitActorsOnReconciliation() {
            when(activityEventRepository.backfillCommitActors(200L)).thenReturn(3);

            var event = new ScmDomainEvent.CommitAuthorsReconciled(200L, createContext());

            listener.onCommitAuthorsReconciled(event);

            verify(activityEventRepository).backfillCommitActors(200L);
        }

        @Test
        void noOpWhenRepositoryIdMissing() {
            var event = new ScmDomainEvent.CommitAuthorsReconciled(null, createContext());

            listener.onCommitAuthorsReconciled(event);

            verify(activityEventRepository, never()).backfillCommitActors(anyLong());
        }

        @Test
        void swallowsBackfillExceptions() {
            when(activityEventRepository.backfillCommitActors(200L)).thenThrow(new RuntimeException("db outage"));

            var event = new ScmDomainEvent.CommitAuthorsReconciled(200L, createContext());

            listener.onCommitAuthorsReconciled(event);

            verify(activityEventRepository).backfillCommitActors(200L);
        }
    }

    // Helpers

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

    private Discussion createDiscussion(Long id) {
        Discussion discussion = new Discussion();
        discussion.setId(id);
        discussion.setNumber(100);
        discussion.setTitle("Test Discussion");
        discussion.setState(Discussion.State.OPEN);
        discussion.setAuthor(testUser);
        discussion.setRepository(testRepository);
        discussion.setCreatedAt(Instant.now());
        discussion.setUpdatedAt(Instant.now());
        discussion.setHtmlUrl("https://github.com/test/test-repo/discussions/100");
        return discussion;
    }

    private DiscussionComment createDiscussionComment(Long id) {
        Discussion discussion = createDiscussion(30L);
        DiscussionComment comment = new DiscussionComment();
        comment.setId(id);
        comment.setBody("Test comment");
        comment.setHtmlUrl("https://github.com/test/test-repo/discussions/100#discussioncomment-" + id);
        comment.setAuthor(testUser);
        comment.setDiscussion(discussion);
        comment.setCreatedAt(Instant.now());
        return comment;
    }

    private ScmEventPayload.DiscussionData createDiscussionData(Discussion discussion) {
        return ScmEventPayload.DiscussionData.from(discussion);
    }

    private ScmEventPayload.DiscussionCommentData createDiscussionCommentData(DiscussionComment comment) {
        return ScmEventPayload.DiscussionCommentData.from(comment);
    }
}
