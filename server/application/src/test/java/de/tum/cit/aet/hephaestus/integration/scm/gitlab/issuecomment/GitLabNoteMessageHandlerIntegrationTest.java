package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabPullRequestReviewCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Integration tests: JSON fixtures → DTO → handler → processor → DB. */
@Tag("integration")
@DisplayName("GitLab Note Message Handler")
class GitLabNoteMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Native IDs from fixtures
    private static final long NATIVE_NOTE_ID = 4406174L;
    private static final long NATIVE_ISSUE_ID = 422296L;
    private static final int ISSUE_IID = 5;
    private static final long NATIVE_USER_ID = 18024L;
    private static final long NATIVE_MR_NOTE_ID = 4406178L;
    private static final long NATIVE_MR_ID = 334047L;
    private static final int MR_IID = 2;

    // Fixture values
    private static final String FIXTURE_NOTE_BODY = "I'll start working on this feature";
    private static final String FIXTURE_NOTE_UPDATED_BODY =
            "Updated: I'll start working on this feature - high priority\\!";
    private static final String FIXTURE_MR_NOTE_BODY =
            "LGTM\\! Just a minor suggestion: consider adding error handling.";
    private static final String FIXTURE_MR_NOTE_UPDATED_BODY =
            "Updated: Consider adding error handling here. Also add input validation.";
    private static final String FIXTURE_NOTE_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5#note_4406174";
    private static final String FIXTURE_AUTHOR_LOGIN = "ga84xah";

    // Repository/org setup
    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    @Autowired
    private GitLabNoteMessageHandler handler;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    @Autowired
    private PullRequestReviewThreadRepository threadRepository;

    @Autowired
    private PullRequestReviewCommentRepository reviewCommentRepository;

    @Autowired
    private GitLabPullRequestReviewThreadProcessor threadProcessor;

    @Autowired
    private GitLabPullRequestReviewCommentProcessor reviewCommentProcessor;

    @Autowired
    private GitLabIssueCommentProcessor issueCommentProcessor;

    @Autowired
    private GitLabReviewReconciler reviewReconciler;

    @Autowired
    private GitLabGraphQlResponseHandler graphQlResponseHandler;

    @Autowired
    private GitLabProperties gitLabProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Repository savedRepo;
    private IdentityProvider savedProvider;
    private Issue savedIssue;
    private PullRequest savedPr;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Type

    @Test
    void returnsCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("note");
    }

    // Issue Notes

    @Nested
    class IssueNotes {

        @Test
        void shouldCreateCommentFromIssueNote() throws Exception {
            handler.handleEvent(loadPayload("note.issue.create"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_NOTE_BODY);
                assertThat(comment.getHtmlUrl()).isEqualTo(FIXTURE_NOTE_URL);
                assertNotNull(comment.getIssue());
                assertThat(comment.getIssue().getId()).isEqualTo(savedIssue.getId());
                assertThat(comment.getAuthor()).isNotNull();
                assertThat(comment.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(comment.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldUpdateCommentOnIssueNoteUpdate() throws Exception {
            handler.handleEvent(loadPayload("note.issue.create"));
            eventListener.clear();

            handler.handleEvent(loadPayload("note.issue.update"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_NOTE_UPDATED_BODY);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .hasSize(1);
        }
    }

    // MR Notes

    @Nested
    class MergeRequestNotes {

        @Test
        void shouldCreateCommentFromMrNote() throws Exception {
            handler.handleEvent(loadPayload("note.mergerequest.create"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_MR_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_MR_NOTE_BODY);
                assertNotNull(comment.getIssue());
                assertThat(comment.getIssue().getId()).isEqualTo(savedPr.getId());
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldUpdateCommentFromMrNoteUpdate() throws Exception {
            handler.handleEvent(loadPayload("note.mergerequest.create"));
            eventListener.clear();

            handler.handleEvent(loadPayload("note.mergerequest.update"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_MR_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_MR_NOTE_UPDATED_BODY);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .hasSize(1);
        }
    }

    // System Notes

    @Nested
    class SystemNotes {

        @Test
        void shouldSkipSystemNote() throws Exception {
            handler.handleEvent(loadPayload("note.system"));

            assertThat(commentRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    /**
     * Who requested changes is known only from a note that names them: GitLab's system note "requested changes". A
     * note's embedded {@code detailed_merge_status} is the merge request's, stated on every note while anyone's request
     * for changes stands.
     */
    @Nested
    class ReviewDecisions {

        private static final long APPROVAL_NATIVE_ID =
                GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR_ID, NATIVE_USER_ID);

        @BeforeEach
        void approveAsTheCommenter() throws Exception {
            receive(loadPayload("note.mergerequest.system.approved"));
            assertThat(reviewState(APPROVAL_NATIVE_ID)).isEqualTo(PullRequestReview.State.APPROVED);
            eventListener.clear();
        }

        @Test
        void shouldKeepAnApprovalWhenTheApproverCommentsWhileSomeoneElseRequestsChanges() throws Exception {
            receive(whileChangesAreRequested("note.mergerequest.create", attributes -> {}));

            assertThat(reviewState(APPROVAL_NATIVE_ID)).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewStatesOf(FIXTURE_AUTHOR_LOGIN)).doesNotContain(PullRequestReview.State.CHANGES_REQUESTED);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @Test
        void shouldKeepAnApprovalWhenTheApproverCommentsOnTheDiffWhileSomeoneElseRequestsChanges() throws Exception {
            receive(whileChangesAreRequested("note.mergerequest.create", attributes -> {
                attributes.put("type", "DiffNote");
                attributes.put("discussion_id", "6a9c1750b37d513a43987b574953fceb50b03ce7");
                ObjectNode position = attributes.putObject("position");
                position.put("position_type", "text");
                position.put("new_path", "src/auth.ts");
                position.put("old_path", "src/auth.ts");
                position.put("new_line", 12);
                position.put("base_sha", "a".repeat(40));
                position.put("start_sha", "a".repeat(40));
                position.put("head_sha", "b".repeat(40));
            }));

            assertThat(reviewState(APPROVAL_NATIVE_ID)).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewStatesOf(FIXTURE_AUTHOR_LOGIN)).doesNotContain(PullRequestReview.State.CHANGES_REQUESTED);
        }

        @Test
        void shouldRecordTheRequestForChangesItsSystemNoteNames() throws Exception {
            setReviewDecision(ReviewDecision.APPROVED);

            receive(loadPayload("note.mergerequest.system.requested_changes"));

            long nativeId = GitLabReviewReconciler.generateChangesRequestedNativeId(
                    "gid://gitlab/Note/4538603", NATIVE_USER_ID);
            assertThat(reviewState(nativeId)).isEqualTo(PullRequestReview.State.CHANGES_REQUESTED);
            assertThat(reviewState(APPROVAL_NATIVE_ID))
                    .as("GitLab withdraws the approval of a reviewer who requests changes")
                    .isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(reviewDecision())
                    .as("one person's decision changed, so the stored decision no longer stands")
                    .isNull();

            setReviewDecision(ReviewDecision.CHANGES_REQUESTED);
            receive(loadPayload("note.mergerequest.system.requested_changes"));

            assertThat(reviewDecision())
                    .as("a redelivered note changes no one's decision, so it leaves a newer sync's standing")
                    .isEqualTo(ReviewDecision.CHANGES_REQUESTED);
        }

        /** Handles {@code event} in the transaction a delivery runs in. */
        private void receive(GitLabNoteEventDTO event) {
            transactionTemplate.executeWithoutResult(status -> handler.handleEvent(event));
        }

        private GitLabNoteEventDTO whileChangesAreRequested(String fixture, Consumer<ObjectNode> attributes)
                throws IOException {
            ObjectNode payload = (ObjectNode) objectMapper.readTree(
                    new ClassPathResource("gitlab/" + fixture + ".json").getContentAsString(StandardCharsets.UTF_8));
            ((ObjectNode) payload.get("merge_request")).put("detailed_merge_status", "requested_changes");
            attributes.accept((ObjectNode) payload.get("object_attributes"));
            return objectMapper.treeToValue(payload, GitLabNoteEventDTO.class);
        }

        private PullRequestReview.@Nullable State reviewState(long nativeId) {
            return reviewRepository
                    .findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(savedProvider.getId()))
                    .map(PullRequestReview::getState)
                    .orElse(null);
        }

        private List<PullRequestReview.State> reviewStatesOf(String login) {
            return Objects.requireNonNull(transactionTemplate.execute(status -> reviewRepository.findAll().stream()
                    .filter(review -> review.getPullRequest() != null
                            && review.getPullRequest().getId().equals(savedPr.getId()))
                    .filter(review -> review.getAuthor() != null
                            && login.equals(review.getAuthor().getLogin()))
                    .map(PullRequestReview::getState)
                    .toList()));
        }

        private @Nullable ReviewDecision reviewDecision() {
            return pullRequestRepository.findById(savedPr.getId()).orElseThrow().getReviewDecision();
        }

        private void setReviewDecision(ReviewDecision decision) {
            transactionTemplate.executeWithoutResult(status -> pullRequestRepository
                    .findById(savedPr.getId())
                    .orElseThrow()
                    .setReviewDecision(decision));
        }
    }

    // Inline discussions

    /**
     * A diff note webhook and the discussion read name one thread through the discussion's GID; a stored thread's
     * resolution follows the newest whole read of the merge request's discussions.
     */
    @Nested
    class InlineDiscussions {

        private static final String DISCUSSION_ID = "7c831f954579a554aefcbfbd5a50dd77cfeb9521";
        private static final String DISCUSSION_GID = "gid://gitlab/Discussion/" + DISCUSSION_ID;
        private static final long THREAD_NATIVE_ID =
                GitLabPullRequestReviewThreadProcessor.deterministicNativeId(DISCUSSION_GID);
        private static final String OTHER_GID = "gid://gitlab/Discussion/" + "0".repeat(40);
        private static final Instant RESOLVED_AT = Instant.parse("2026-02-01T10:00:00Z");
        private static final long NATIVE_TUTOR_ID = 99_001L;

        @Test
        void shouldResolveAndReopenTheThreadItsDiffNoteWebhookStoredWhenTheDiscussionIsRead() throws Exception {
            receiveDiffNote();
            PullRequestReviewThread stored = thread();
            assertThat(stored.getNodeId()).isEqualTo(DISCUSSION_GID);
            assertThat(stored.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            Long commentId = comment().getId();

            sync(true);

            PullRequestReviewThread resolved = thread();
            assertThat(resolved.getId()).isEqualTo(stored.getId());
            assertThat(resolved.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(resolved.getResolvedAt()).isEqualTo(RESOLVED_AT);
            assertThat(resolverLogin()).isEqualTo("tutor");
            assertThat(comment().getId()).isEqualTo(commentId);
            assertThat(commentThreadId()).isEqualTo(stored.getId());
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewThreadResolved.class))
                    .hasSize(1);

            sync(false);

            PullRequestReviewThread reopened = thread();
            assertThat(reopened.getId()).isEqualTo(stored.getId());
            assertThat(reopened.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(reopened.getResolvedAt()).isNull();
            assertThat(resolverLogin()).isNull();
            assertThat(commentThreadId()).isEqualTo(stored.getId());
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewThreadUnresolved.class))
                    .hasSize(1);
        }

        /** A thread a diff note webhook stored before webhooks carried the discussion's GID. */
        @Test
        void shouldCompleteAndResolveAThreadStoredWithoutItsDiscussionsGid() throws Exception {
            receiveDiffNote();
            jdbcTemplate.update("UPDATE pull_request_review_thread SET node_id = NULL WHERE id = ?", thread().getId());

            sync(true);

            PullRequestReviewThread stored = thread();
            assertThat(stored.getNodeId()).isEqualTo(DISCUSSION_GID);
            assertThat(stored.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(commentThreadId()).isEqualTo(stored.getId());
        }

        /** Reopened and resolved again by someone else between two reads: the second names who resolved it now. */
        @Test
        void shouldNameWhoResolvedTheDiscussionLastAndKeepThemWhenAReadNamesNobody() throws Exception {
            receiveDiffNote();
            sync(true);
            Instant later = RESOLVED_AT.plusSeconds(3600);

            syncPages(onePage(resolvedBy(user(NATIVE_USER_ID, FIXTURE_AUTHOR_LOGIN), later)));

            assertThat(resolverLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(thread().getResolvedAt()).isEqualTo(later);

            syncPages(onePage(resolvedBy(null, later)));

            assertThat(resolverLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(thread().getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
        }

        @Test
        void shouldKeepTheResolutionWhenTheReadDoesNotSayIt() throws Exception {
            receiveDiffNote();
            sync(true);

            sync(null);

            PullRequestReviewThread stored = thread();
            assertThat(stored.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(stored.getResolvedAt()).isEqualTo(RESOLVED_AT);
            assertThat(resolverLogin()).isEqualTo("tutor");
        }

        @Test
        void shouldKeepOneThreadWhenTheDiscussionIsReadBeforeItsDiffNoteWebhook() throws Exception {
            sync(false);
            PullRequestReviewThread read = thread();
            assertThat(read.getNodeId()).isEqualTo(DISCUSSION_GID);

            receiveDiffNote();

            assertThat(thread().getId()).isEqualTo(read.getId());
            assertThat(commentThreadId()).isEqualTo(read.getId());
        }

        /** Delayed read A saw the thread resolved; read B, begun later, saw it reopened and so changed nothing. */
        @Test
        void shouldNotLetAnOlderReadUndoANewerSyncThatChangedNothing() throws Exception {
            receiveDiffNote();
            GitLabDiscussionSyncService.DiscussionRead older = Objects.requireNonNull(
                    service(onePage(discussion(true))).readThreadResolutions(1L, FIXTURE_REPO_FULL_NAME, MR_IID));

            sync(false);
            service(onePage(discussion(true))).applyThreadResolutions(savedRepo, MR_IID, older, 1L);

            assertThat(thread().getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(thread().getResolvedAt()).isNull();
            assertThat(discussionsObservedAt()).isAfter(older.requestedAt());
        }

        @Test
        void shouldNotLetAnOlderWebhookReadUndoANewerOneThatChangedNothing() throws Exception {
            receiveDiffNote();
            sync(true);
            GitLabDiscussionSyncService reader = service(onePage(discussion(false)));
            GitLabDiscussionSyncService.DiscussionRead older =
                    Objects.requireNonNull(reader.readThreadResolutions(1L, FIXTURE_REPO_FULL_NAME, MR_IID));
            GitLabDiscussionSyncService.DiscussionRead newer = Objects.requireNonNull(
                    service(onePage(discussion(true))).readThreadResolutions(1L, FIXTURE_REPO_FULL_NAME, MR_IID));

            reader.applyThreadResolutions(savedRepo, MR_IID, newer, 1L);
            reader.applyThreadResolutions(savedRepo, MR_IID, older, 1L);

            assertThat(thread().getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(discussionsObservedAt()).isEqualTo(newer.requestedAt());
        }

        /**
         * A sync begins reading a discussion no thread holds yet; a webhook read begun later records the opposite
         * resolution before the sync stores the discussion's comments. The sync's older read changes nothing.
         */
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void shouldKeepTheNewerReadOfADiscussionAnOlderSyncStoresLater(boolean newer) {
            GitLabDiscussionSyncService webhookRead = service(onePage(discussion(newer)));
            GitLabDiscussionSyncService olderSync = serviceRunningFirst(
                    () -> webhookRead.applyThreadResolutions(
                            savedRepo,
                            MR_IID,
                            Objects.requireNonNull(
                                    webhookRead.readThreadResolutions(1L, FIXTURE_REPO_FULL_NAME, MR_IID)),
                            1L),
                    onePage(discussion(!newer)));

            olderSync.syncDiscussionsForMergeRequest(1L, savedRepo, MR_IID, savedPr);

            PullRequestReviewThread stored = thread();
            assertThat(stored.getState())
                    .isEqualTo(
                            newer ? PullRequestReviewThread.State.RESOLVED : PullRequestReviewThread.State.UNRESOLVED);
            assertThat(stored.getResolvedAt()).isEqualTo(newer ? RESOLVED_AT : null);
            assertThat(stored.getNodeId()).isEqualTo(DISCUSSION_GID);
            assertThat(commentThreadId()).isEqualTo(stored.getId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"nodes", "hasNextPage"})
        void shouldRecordNoResolutionWhenTheLastPageIsNotWhole(String missing) {
            Map<String, @Nullable Object> last = page(List.of(), false, null);
            if ("nodes".equals(missing)) {
                last.put("nodes", null);
            } else {
                pageInfoOf(last).remove("hasNextPage");
            }

            syncPages(page(List.of(discussion(true)), true, "c1"), last);

            // The first page's notes are stored on a thread, but the page's resolution is not a whole read.
            assertThat(thread().getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(thread().getResolvedAt()).isNull();
            assertThat(commentThreadId()).isEqualTo(thread().getId());
            assertThat(discussionsObservedAt()).isNull();
        }

        @Test
        void shouldReadOnPastAnEmptyPageThatSaysMoreFollow() throws Exception {
            receiveDiffNote();

            syncPages(page(List.of(), true, "c1"), page(List.of(discussion(true)), false, null));

            assertThat(thread().getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
        }

        @Test
        void shouldLinkNothingToAThreadOfAnotherMergeRequest() throws Exception {
            PullRequestReviewThread other = storeThread(anotherMergeRequest(), null);

            sync(true);
            receiveDiffNote();

            assertUntouched(other, null);
        }

        @Test
        void shouldLinkNothingToAThreadHoldingAnotherDiscussion() throws Exception {
            PullRequestReviewThread other = storeThread(savedPr, OTHER_GID);

            sync(true);
            receiveDiffNote();

            assertUntouched(other, OTHER_GID);
            assertThat(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, providerId()))
                    .isEmpty();
        }

        @Test
        void shouldLinkNothingWhenTwoThreadsClaimTheDiscussion() throws Exception {
            PullRequestReviewThread byNative = storeThread(savedPr, null);
            PullRequestReviewThread byNode = new PullRequestReviewThread();
            byNode.setNativeId(THREAD_NATIVE_ID + 1);
            byNode.setNodeId(DISCUSSION_GID);
            byNode.setProvider(savedProvider);
            byNode.setPullRequest(savedPr);
            byNode.setState(PullRequestReviewThread.State.UNRESOLVED);
            byNode = threadRepository.save(byNode);

            sync(true);
            receiveDiffNote();

            assertUntouched(byNative, null);
            PullRequestReviewThread node =
                    threadRepository.findById(byNode.getId()).orElseThrow();
            assertThat(node.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
        }

        @Test
        void shouldNotTakeAThreadOfAnotherInstanceForThisOne() {
            IdentityProvider otherInstance = gitProviderRepository.save(
                    new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.other.example"));
            PullRequestReviewThread foreign = new PullRequestReviewThread();
            foreign.setNativeId(THREAD_NATIVE_ID);
            foreign.setNodeId(DISCUSSION_GID);
            foreign.setProvider(otherInstance);
            foreign.setPullRequest(anotherMergeRequest());
            foreign.setState(PullRequestReviewThread.State.UNRESOLVED);
            foreign = threadRepository.save(foreign);

            sync(true);

            assertThat(thread().getId()).isNotEqualTo(foreign.getId());
            assertThat(thread().getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            PullRequestReviewThread untouched =
                    threadRepository.findById(foreign.getId()).orElseThrow();
            assertThat(untouched.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(untouched.getResolvedAt()).isNull();
        }

        /** The stored {@code thread} still as stored, and no comment linked for the note. */
        private void assertUntouched(PullRequestReviewThread thread, @Nullable String nodeId) {
            PullRequestReviewThread stored =
                    threadRepository.findById(thread.getId()).orElseThrow();
            assertThat(stored.getNodeId()).isEqualTo(nodeId);
            assertThat(stored.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(stored.getResolvedAt()).isNull();
            assertThat(reviewCommentRepository.findByNativeIdAndProviderId(NATIVE_MR_NOTE_ID, providerId()))
                    .isEmpty();
        }

        /** Handles MR !2's note hook as a diff note on a line of this discussion, in the delivery's transaction. */
        private void receiveDiffNote() throws IOException {
            ObjectNode payload =
                    (ObjectNode) objectMapper.readTree(new ClassPathResource("gitlab/note.mergerequest.create.json")
                            .getContentAsString(StandardCharsets.UTF_8));
            ObjectNode attributes = (ObjectNode) payload.get("object_attributes");
            attributes.put("type", "DiffNote");
            attributes.put("discussion_id", DISCUSSION_ID);
            ObjectNode position = attributes.putObject("position");
            position.put("position_type", "text");
            position.put("new_path", "src/auth.ts");
            position.put("old_path", "src/auth.ts");
            position.put("new_line", 12);
            position.put("base_sha", "a".repeat(40));
            position.put("start_sha", "a".repeat(40));
            position.put("head_sha", "b".repeat(40));
            GitLabNoteEventDTO event = objectMapper.treeToValue(payload, GitLabNoteEventDTO.class);
            transactionTemplate.executeWithoutResult(status -> handler.handleEvent(event));
        }

        /** Runs the discussion sync of MR !2 over one page holding this discussion, resolved as {@code resolved} says. */
        private void sync(@Nullable Boolean resolved) {
            syncPages(onePage(discussion(resolved)));
        }

        /** Runs the discussion sync of MR !2 over {@code pages}, answered in order. */
        @SafeVarargs
        private void syncPages(Map<String, @Nullable Object>... pages) {
            service(pages).syncDiscussionsForMergeRequest(1L, savedRepo, MR_IID, savedPr);
        }

        /** The discussion sync, reading MR !2's discussions from {@code pages} in order. */
        @SafeVarargs
        private GitLabDiscussionSyncService service(Map<String, @Nullable Object>... pages) {
            AtomicInteger next = new AtomicInteger();
            GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);
            when(clients.forScope(any())).thenReturn(ScriptedGraphQlClient.of(request -> {
                Map<String, @Nullable Object> page = pages[Math.min(next.getAndIncrement(), pages.length - 1)];
                return Mono.just(GraphQlResponses.of(
                        Map.of("project", Map.of("mergeRequest", Map.of("discussions", page))), List.of()));
            }));
            return new GitLabDiscussionSyncService(
                    clients,
                    graphQlResponseHandler,
                    threadProcessor,
                    reviewCommentProcessor,
                    issueCommentProcessor,
                    reviewReconciler,
                    gitLabProperties);
        }

        /** The discussion sync, running {@code first} when GitLab is first asked, then reading {@code pages} in order. */
        @SafeVarargs
        private GitLabDiscussionSyncService serviceRunningFirst(
                Runnable first, Map<String, @Nullable Object>... pages) {
            AtomicInteger asked = new AtomicInteger();
            GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);
            AtomicInteger next = new AtomicInteger();
            when(clients.forScope(any())).thenReturn(ScriptedGraphQlClient.of(request -> {
                if (asked.getAndIncrement() == 0) {
                    first.run();
                }
                Map<String, @Nullable Object> page = pages[Math.min(next.getAndIncrement(), pages.length - 1)];
                return Mono.just(GraphQlResponses.of(
                        Map.of("project", Map.of("mergeRequest", Map.of("discussions", page))), List.of()));
            }));
            return new GitLabDiscussionSyncService(
                    clients,
                    graphQlResponseHandler,
                    threadProcessor,
                    reviewCommentProcessor,
                    issueCommentProcessor,
                    reviewReconciler,
                    gitLabProperties);
        }

        private Map<String, @Nullable Object> onePage(Map<String, @Nullable Object> discussion) {
            return page(List.of(discussion), false, null);
        }

        /** A page of MR !2's discussions connection. */
        private Map<String, @Nullable Object> page(
                List<Map<String, @Nullable Object>> nodes, boolean hasNextPage, @Nullable String endCursor) {
            assertVendorCouldReturn(
                    GITLAB, "GetMergeRequestDiscussions", "project.mergeRequest.discussions.nodes", nodes);
            Map<String, @Nullable Object> pageInfo = new HashMap<>();
            pageInfo.put("hasNextPage", hasNextPage);
            pageInfo.put("endCursor", endCursor);
            Map<String, @Nullable Object> page = new HashMap<>();
            page.put("pageInfo", pageInfo);
            page.put("nodes", nodes);
            return page;
        }

        @SuppressWarnings("unchecked")
        private Map<String, @Nullable Object> pageInfoOf(Map<String, @Nullable Object> page) {
            return (Map<String, @Nullable Object>) Objects.requireNonNull(page.get("pageInfo"));
        }

        /**
         * This discussion with its diff note, resolved as {@code resolved} says; null leaves the field out, as a read
         * that could not state it.
         */
        private Map<String, @Nullable Object> discussion(@Nullable Boolean resolved) {
            return Boolean.TRUE.equals(resolved)
                    ? resolvedBy(user(NATIVE_TUTOR_ID, "tutor"), RESOLVED_AT)
                    : discussion(resolved, null, null);
        }

        /** This discussion resolved at {@code at} by {@code resolver}, or by nobody the read names. */
        private Map<String, @Nullable Object> resolvedBy(@Nullable Map<String, @Nullable Object> resolver, Instant at) {
            return discussion(true, resolver, at);
        }

        private Map<String, @Nullable Object> discussion(
                @Nullable Boolean resolved,
                @Nullable Map<String, @Nullable Object> resolver,
                @Nullable Instant resolvedAt) {
            Map<String, @Nullable Object> diffRefs = new HashMap<>();
            diffRefs.put("baseSha", "a".repeat(40));
            diffRefs.put("headSha", "b".repeat(40));
            diffRefs.put("startSha", "a".repeat(40));
            Map<String, @Nullable Object> position = new HashMap<>();
            position.put("filePath", "src/auth.ts");
            position.put("newPath", "src/auth.ts");
            position.put("oldPath", "src/auth.ts");
            position.put("newLine", 12);
            position.put("oldLine", null);
            position.put("positionType", "text");
            position.put("diffRefs", diffRefs);
            Map<String, @Nullable Object> note = new HashMap<>();
            note.put("id", "gid://gitlab/DiffNote/" + NATIVE_MR_NOTE_ID);
            note.put("body", FIXTURE_MR_NOTE_BODY);
            note.put("system", false);
            note.put("internal", false);
            note.put("url", savedPr.getHtmlUrl() + "#note_" + NATIVE_MR_NOTE_ID);
            note.put("position", position);
            note.put("author", user(NATIVE_USER_ID, FIXTURE_AUTHOR_LOGIN));
            note.put("createdAt", "2026-01-31T18:03:56Z");
            note.put("updatedAt", "2026-01-31T18:03:56Z");
            Map<String, @Nullable Object> notePage = new HashMap<>();
            notePage.put("hasNextPage", false);
            Map<String, @Nullable Object> discussion = new HashMap<>();
            discussion.put("id", DISCUSSION_GID);
            if (resolved != null) {
                discussion.put("resolved", resolved);
                discussion.put("resolvedAt", resolvedAt == null ? null : resolvedAt.toString());
                discussion.put("resolvedBy", resolver);
            }
            discussion.put("notes", Map.of("pageInfo", notePage, "nodes", List.of(note)));
            return discussion;
        }

        private Map<String, @Nullable Object> user(long id, String username) {
            Map<String, @Nullable Object> user = new HashMap<>();
            user.put("id", "gid://gitlab/User/" + id);
            user.put("username", username);
            user.put("name", username);
            return user;
        }

        private PullRequestReviewThread storeThread(PullRequest parent, @Nullable String nodeId) {
            PullRequestReviewThread thread = new PullRequestReviewThread();
            thread.setNativeId(THREAD_NATIVE_ID);
            if (nodeId != null) {
                thread.setNodeId(nodeId);
            }
            thread.setProvider(savedProvider);
            thread.setPullRequest(parent);
            thread.setState(PullRequestReviewThread.State.UNRESOLVED);
            return threadRepository.save(thread);
        }

        private PullRequest anotherMergeRequest() {
            PullRequest pr = new PullRequest();
            pr.setNativeId(NATIVE_MR_ID + 1);
            pr.setProvider(savedProvider);
            pr.setNumber(MR_IID + 1);
            pr.setTitle("Another merge request");
            pr.setState(Issue.State.OPEN);
            pr.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository/-/merge_requests/3");
            pr.setMerged(false);
            pr.setAdditions(0);
            pr.setDeletions(0);
            pr.setChangedFiles(0);
            pr.setCommits(0);
            pr.setHeadRefName("feature/other");
            pr.setBaseRefName("main");
            pr.setCreatedAt(Instant.now());
            pr.setUpdatedAt(Instant.now());
            pr.setRepository(savedRepo);
            return pullRequestRepository.save(pr);
        }

        private PullRequestReviewThread thread() {
            return threadRepository
                    .findByNativeIdAndProviderId(THREAD_NATIVE_ID, providerId())
                    .orElseThrow();
        }

        private PullRequestReviewComment comment() {
            return reviewCommentRepository
                    .findByNativeIdAndProviderId(NATIVE_MR_NOTE_ID, providerId())
                    .orElseThrow();
        }

        private @Nullable Long commentThreadId() {
            return transactionTemplate.execute(status -> {
                PullRequestReviewThread thread = comment().getThread();
                return thread == null ? null : thread.getId();
            });
        }

        private @Nullable String resolverLogin() {
            return transactionTemplate.execute(status -> {
                var resolver = thread().getResolvedBy();
                return resolver == null ? null : resolver.getLogin();
            });
        }

        private @Nullable Instant discussionsObservedAt() {
            return pullRequestRepository.findById(savedPr.getId()).orElseThrow().getDiscussionsObservedAt();
        }

        private long providerId() {
            return Objects.requireNonNull(savedProvider.getId());
        }
    }

    // Confidential Notes

    @Nested
    class ConfidentialNotes {

        @Test
        void shouldSkipConfidentialNote() throws Exception {
            handler.handleEvent(loadPayload("note.confidential.issue.create"));

            assertThat(commentRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabNoteEventDTO event = loadPayload("note.issue.create");
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
            assertThat(commentRepository.count()).isZero();
        }

        @Test
        @DisplayName("is idempotent — same event twice creates one comment")
        void shouldBeIdempotent() throws Exception {
            handler.handleEvent(loadPayload("note.issue.create"));
            long countAfterFirst = commentRepository.count();

            handler.handleEvent(loadPayload("note.issue.create"));

            assertThat(commentRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldCreateStubIssueWhenParentMissing() throws Exception {
            // Delete the pre-created issue
            commentRepository.deleteAll();
            issueRepository.deleteAll();

            handler.handleEvent(loadPayload("note.issue.create"));

            transactionTemplate.executeWithoutResult(status -> {
                // Should have created a stub issue AND the comment
                assertThat(issueRepository.count()).isEqualTo(1);
                assertThat(commentRepository.count()).isEqualTo(1);

                Issue stubIssue = issueRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                        .orElse(null);
                assertThat(stubIssue).isNotNull();
                assertThat(stubIssue.getNativeId()).isEqualTo(NATIVE_ISSUE_ID);
            });
        }

        @Test
        void shouldSkipCommitNote() throws Exception {
            handler.handleEvent(loadPayload("note.commit.create"));

            assertThat(commentRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    // Helpers

    private GitLabNoteEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("gitlab/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitLabNoteEventDTO.class);
    }

    private void setupTestData() {
        savedProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));

        Organization org = new Organization();
        org.setNativeId(1L);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("HephaestusTest");
        org.setAvatarUrl("");
        org.setHtmlUrl("https://gitlab.lrz.de/hephaestustest");
        org.setProvider(savedProvider);
        org = organizationRepository.save(org);

        Repository repo = new Repository();
        repo.setNativeId(246765L);
        repo.setName("demo-repository");
        repo.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        repo.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repo.setVisibility(Repository.Visibility.PRIVATE);
        repo.setDefaultBranch("main");
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setPushedAt(Instant.now());
        repo.setOrganization(org);
        repo.setProvider(savedProvider);
        savedRepo = repositoryRepository.save(repo);

        // Pre-create Issue (IID 5) — parent for issue notes
        Issue issue = new Issue();
        issue.setNativeId(NATIVE_ISSUE_ID);
        issue.setProvider(savedProvider);
        issue.setNumber(ISSUE_IID);
        issue.setTitle("Feature: Add user authentication");
        issue.setBody("Implement OAuth2 authentication flow");
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5");
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setRepository(savedRepo);
        savedIssue = issueRepository.save(issue);

        // Pre-create PullRequest (IID 2) — parent for MR notes
        PullRequest pr = new PullRequest();
        pr.setNativeId(NATIVE_MR_ID);
        pr.setProvider(savedProvider);
        pr.setNumber(MR_IID);
        pr.setTitle("Implement OAuth authentication");
        pr.setBody("This MR implements OAuth2 authentication.\n\nCloses #5");
        pr.setState(Issue.State.OPEN);
        pr.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository/-/merge_requests/2");
        pr.setMerged(false);
        pr.setAdditions(0);
        pr.setDeletions(0);
        pr.setChangedFiles(0);
        pr.setCommits(0);
        pr.setHeadRefName("feature/oauth");
        pr.setBaseRefName("main");
        pr.setBaseRefOid("a".repeat(40));
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        pr.setRepository(savedRepo);
        savedPr = pullRequestRepository.save(pr);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test-gitlab");
        workspace.setDisplayName("HephaestusTest GitLab");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }
}
