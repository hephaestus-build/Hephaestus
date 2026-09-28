package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobEventListener;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.agent.job.PullRequestSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.handler.WebhookDelivery;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integration tests for GitLabMergeRequestMessageHandler.
 * <p>
 * Tests the full webhook handling flow: JSON fixtures -> DTO -> handler -> processor -> DB.
 * <p>
 * <b>Fixture data comes from real GitLab exports (gitlab.lrz.de).</b>
 * The fixtures represent 3 distinct merge requests:
 * <ul>
 *   <li>MR !3 (open/close/reopen): "Test MR for close/reopen" — author ga84xah (18024)</li>
 *   <li>MR !2 (merge/update): "Implement OAuth authentication" — author ga84xah (18024)</li>
 *   <li>MR !4 (approved/unapproved): "Draft: Work in progress feature" — approver bot (83343)</li>
 * </ul>
 * <p>
 * Note: Does NOT use @Transactional (see GitLabIssueMessageHandlerIntegrationTest for rationale).
 */
@Tag("integration")
@DisplayName("GitLab Merge Request Message Handler")
class GitLabMergeRequestMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Common Constants

    private static final long NATIVE_AUTHOR_ID = 18024L;
    private static final String FIXTURE_AUTHOR_LOGIN = "ga84xah";
    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    // MR !3 (open/close/reopen)

    private static final long NATIVE_MR3_ID = 334053L;
    private static final int MR3_IID = 3;
    private static final String MR3_TITLE = "Test MR for close/reopen";
    private static final String MR3_HTML_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/merge_requests/3";
    private static final String MR3_SOURCE_BRANCH = "feature/test-close-reopen";
    private static final String MR3_TARGET_BRANCH = "main";

    // MR !2 (merge/update)

    private static final long NATIVE_MR2_ID = 334047L;
    private static final int MR2_IID = 2;
    private static final String MR2_TITLE = "Implement OAuth authentication";

    // MR !4 (approved/unapproved)

    private static final long NATIVE_MR4_ID = 334054L;
    private static final int MR4_IID = 4;
    private static final long NATIVE_APPROVER_ID = 83343L;

    @Autowired
    private GitLabMergeRequestMessageHandler handler;

    @Autowired
    private GitLabMergeRequestProcessor mergeRequestProcessor;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private IssueRepository issueRepository;

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
    private ArtifactSignalRepository artifactSignalRepository;

    @Autowired
    private SignalRecorder signalRecorder;

    @Autowired
    private WorkspaceResolver workspaceResolver;

    private Repository savedRepo;
    private IdentityProvider savedProvider;
    private Workspace savedWorkspace;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Type

    @Nested
    class EventType {

        @Test
        void returnsCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("merge_request");
        }
    }

    // Basic Lifecycle

    @Nested
    class BasicLifecycleEvents {

        @Test
        void openMergeRequest_createsPullRequest() throws Exception {
            GitLabMergeRequestEventDTO event = loadPayload("merge_request.open");

            handler.handleEvent(event);

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();

                // Core fields
                assertThat(pr.getNativeId()).isEqualTo(NATIVE_MR3_ID);
                assertThat(pr.getNumber()).isEqualTo(MR3_IID);
                assertThat(pr.getTitle()).isEqualTo(MR3_TITLE);
                assertThat(pr.getBody()).isNull();
                assertThat(pr.getState()).isEqualTo(Issue.State.OPEN);
                assertThat(pr.getHtmlUrl()).isEqualTo(MR3_HTML_URL);

                // Branch info
                assertThat(pr.getHeadRefName()).isEqualTo(MR3_SOURCE_BRANCH);
                assertThat(pr.getBaseRefName()).isEqualTo(MR3_TARGET_BRANCH);

                // Provider
                assertThat(pr.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);

                // Timestamps
                assertThat(pr.getCreatedAt()).isNotNull();
                assertThat(pr.getUpdatedAt()).isNotNull();

                // Repository
                assertThat(pr.getRepository()).isNotNull();
                assertThat(pr.getRepository().getId()).isEqualTo(savedRepo.getId());

                // Author
                assertThat(pr.getAuthor()).isNotNull();
                assertThat(pr.getAuthor().getNativeId()).isEqualTo(NATIVE_AUTHOR_ID);
                assertThat(pr.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);

                // PR-specific
                assertThat(pr.isPullRequest()).isTrue();
            });

            // Domain event
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
        }

        @Test
        void closeMergeRequest_setsStateToClosed() throws Exception {
            // Create MR !3 first
            handler.handleEvent(loadPayload("merge_request.open"));
            eventListener.clear();

            // Close MR !3
            handler.handleEvent(loadPayload("merge_request.close"));

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.CLOSED);
                // Real GitLab webhook payloads for 'close' action don't include closed_at
                assertThat(pr.getClosedAt()).isNull();
            });

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isFalse();
        }

        @Test
        void mergeMergeRequest_setsStateToMerged() throws Exception {
            // Create MR !2 via update event first
            handler.handleEvent(loadPayload("merge_request.update"));
            eventListener.clear();

            // Merge MR !2
            handler.handleEvent(loadPayload("merge_request.merge"));

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.MERGED);
                assertThat(pr.isMerged()).isTrue();
                // Real GitLab webhook payloads for 'merge' action don't include merged_at
                assertThat(pr.getMergedAt()).isNull();
            });

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isTrue();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestMerged.class))
                    .hasSize(1);
        }

        @Test
        void reopenMergeRequest_setsStateToOpen() throws Exception {
            // Create MR !3 and close it
            handler.handleEvent(loadPayload("merge_request.open"));
            handler.handleEvent(loadPayload("merge_request.close"));
            eventListener.clear();

            // Reopen MR !3
            handler.handleEvent(loadPayload("merge_request.reopen"));

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.OPEN);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReopened.class))
                    .hasSize(1);
        }
    }

    // Approval Events

    @Nested
    class ApprovalEvents {

        @Test
        void approveMergeRequest_createsReview() throws Exception {
            // Approved event creates MR !4 via internal process() call
            handler.handleEvent(loadPayload("merge_request.approved"));

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR4_IID)
                        .orElseThrow();

                List<PullRequestReview> reviews = reviewRepository.findAll().stream()
                        .filter(r -> r.getPullRequest() != null
                                && r.getPullRequest().getId().equals(pr.getId()))
                        .toList();

                assertThat(reviews).hasSize(1);
                PullRequestReview review = reviews.get(0);
                assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
                assertThat(review.getId()).isPositive(); // auto-generated PK

                long expectedNativeId =
                        GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, NATIVE_APPROVER_ID);
                assertThat(review.getNativeId()).isEqualTo(expectedNativeId);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);
        }

        @Test
        @DisplayName("deletes review on 'unapproved' event")
        void unapproveMergeRequest_dismissesReview() throws Exception {
            // Create MR !4 and approve it
            handler.handleEvent(loadPayload("merge_request.approved"));
            eventListener.clear();

            // Unapprove MR !4 — should dismiss the review (not delete, not CHANGES_REQUESTED)
            handler.handleEvent(loadPayload("merge_request.unapproved"));

            transactionTemplate.executeWithoutResult(status -> {
                long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, NATIVE_APPROVER_ID);
                var review = reviewRepository.findByNativeIdAndProviderId(nativeId, persistedId(savedProvider));
                assertThat(review).isPresent();
                assertThat(review.get().getState()).isEqualTo(PullRequestReview.State.DISMISSED);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .hasSize(1);
        }
    }

    // Edge Cases

    // Reviewer states

    @Nested
    class ReviewerStates {

        @Test
        void shouldStoreEachReviewersStateWhenTheMergeRequestListsReviewers() throws Exception {
            handler.handleEvent(withReviewers(
                    null,
                    reviewer(900001L, "approving-reviewer", "approved"),
                    reviewer(900002L, "waiting-reviewer", "unreviewed")));

            assertThat(reviewerStates())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "approving-reviewer", RequestedReviewer.ReviewState.APPROVED,
                            "waiting-reviewer", RequestedReviewer.ReviewState.UNREVIEWED));
        }

        @Test
        void shouldSetTheReviewerBackToUnreviewedWhenTheirReviewIsRequestedAgain() throws Exception {
            handler.handleEvent(withReviewers(null, reviewer(900001L, "re-requested-reviewer", "approved")));

            ObjectNode reRequested = reviewer(900001L, "re-requested-reviewer", "unreviewed");
            reRequested.put("re_requested", true);
            handler.handleEvent(withReviewers("2026-01-31 19:10:00 +0100", reRequested));

            assertThat(reviewerStates())
                    .containsExactlyEntriesOf(
                            Map.of("re-requested-reviewer", RequestedReviewer.ReviewState.UNREVIEWED));
        }

        @Test
        void shouldTakeTheReviewersStateWhenAnApprovalLeavesTheUpdateTimeAsItWas() throws Exception {
            handler.handleEvent(withReviewers(null, reviewer(900001L, "approving-reviewer", "unreviewed")));

            handler.handleEvent(withReviewers(null, reviewer(900001L, "approving-reviewer", "approved")));

            assertThat(reviewerStates())
                    .containsExactlyEntriesOf(Map.of("approving-reviewer", RequestedReviewer.ReviewState.APPROVED));
        }

        @Test
        void shouldKeepTheNewerStateWhenAnOlderPayloadArrivesLate() throws Exception {
            handler.handleEvent(
                    withReviewers("2026-01-31 19:10:00 +0100", reviewer(900001L, "approving-reviewer", "approved")));

            handler.handleEvent(withReviewers(null, reviewer(900001L, "approving-reviewer", "unreviewed")));

            assertThat(reviewerStates())
                    .containsExactlyEntriesOf(Map.of("approving-reviewer", RequestedReviewer.ReviewState.APPROVED));
        }

        @Test
        void shouldStoreEachReviewersStateWhenTheSyncReadsThem() {
            sync(
                    Instant.now(),
                    syncedReviewer(900003L, "synced-approver", "APPROVED"),
                    syncedReviewer(900004L, "synced-waiting", "UNREVIEWED"),
                    syncedReviewer(900005L, "stateless-reviewer", null));

            Map<String, RequestedReviewer.@Nullable ReviewState> expected = new HashMap<>();
            expected.put("synced-approver", RequestedReviewer.ReviewState.APPROVED);
            expected.put("synced-waiting", RequestedReviewer.ReviewState.UNREVIEWED);
            expected.put("stateless-reviewer", null);
            assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(expected);
        }

        @Test
        void shouldKeepTheWebhooksReviewersWhenASyncPageFetchedBeforeItIsWrittenAfterIt() throws Exception {
            Instant fetchedBeforeTheWebhook = Instant.now().minus(Duration.ofMinutes(1));
            handler.handleEvent(withReviewers(null, reviewer(900001L, "added-reviewer", "approved")));

            sync(fetchedBeforeTheWebhook, syncedReviewer(900002L, "earlier-reviewer", "UNREVIEWED"));

            assertThat(reviewerStates())
                    .as("the older page neither removes the reviewer the webhook added nor adds its own")
                    .containsExactlyEntriesOf(Map.of("added-reviewer", RequestedReviewer.ReviewState.APPROVED));
        }

        @Test
        void shouldNotBringBackAReviewerTheWebhookRemovedWhenAnOlderSyncPageListsThem() throws Exception {
            sync(Instant.now().minus(Duration.ofMinutes(2)), syncedReviewer(900002L, "removed-reviewer", "UNREVIEWED"));
            handler.handleEvent(withReviewers("2026-01-31 19:15:00 +0100"));

            sync(Instant.now().minus(Duration.ofMinutes(1)), syncedReviewer(900002L, "removed-reviewer", "UNREVIEWED"));

            assertThat(reviewerStates()).isEmpty();
        }

        @Test
        void shouldTakeTheSyncsReviewersWhenItsPageWasFetchedAfterTheWebhook() throws Exception {
            handler.handleEvent(withReviewers(null, reviewer(900001L, "re-requested-reviewer", "approved")));

            sync(
                    Instant.now().plusSeconds(1),
                    syncedReviewer(900001L, "re-requested-reviewer", "UNREVIEWED"),
                    syncedReviewer(900002L, "new-reviewer", "UNREVIEWED"));

            assertThat(reviewerStates())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "re-requested-reviewer", RequestedReviewer.ReviewState.UNREVIEWED,
                            "new-reviewer", RequestedReviewer.ReviewState.UNREVIEWED));
        }

        /** The webhook sat in the stream while a sync read the merge request; its arrival, not its handling, dates it. */
        @Test
        void shouldIgnoreADelayedWebhookWhenItArrivedBeforeTheSyncPageWasRead() throws Exception {
            Instant arrived = Instant.now().minus(Duration.ofMinutes(5));
            sync(Instant.now().minus(Duration.ofMinutes(1)), syncedReviewer(900001L, "approving-reviewer", "APPROVED"));

            GitLabMergeRequestEventDTO delayed =
                    withReviewers("2026-01-31 19:20:00 +0100", reviewer(900001L, "approving-reviewer", "unreviewed"));
            WebhookDelivery.during(arrived, () -> handler.handleEvent(delayed));

            assertThat(reviewerStates())
                    .containsExactlyEntriesOf(Map.of("approving-reviewer", RequestedReviewer.ReviewState.APPROVED));
        }

        private void sync(Instant fetchedAt, GitLabMergeRequestProcessor.SyncReviewerData... reviewers) {
            mergeRequestProcessor.processFromSync(syncedMergeRequest(List.of(reviewers)), savedRepo, null, fetchedAt);
        }

        private GitLabMergeRequestProcessor.SyncReviewerData syncedReviewer(
                long id, String username, @Nullable String state) {
            return new GitLabMergeRequestProcessor.SyncReviewerData(
                    new GitLabMergeRequestProcessor.SyncUserData(
                            "gid://gitlab/User/" + id, username, username, null, null, null),
                    state);
        }

        /** MR !2 as a sync reads it, with only what these tests need. */
        private GitLabMergeRequestProcessor.SyncMergeRequestData syncedMergeRequest(
                List<GitLabMergeRequestProcessor.SyncReviewerData> reviewers) {
            return new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/" + NATIVE_MR2_ID,
                    String.valueOf(MR2_IID),
                    MR2_TITLE,
                    null, // description
                    "opened",
                    false, // draft
                    null, // mergeable
                    null, // detailedMergeStatus
                    false, // approved
                    "https://gitlab.lrz.de/" + FIXTURE_REPO_FULL_NAME + "/-/merge_requests/" + MR2_IID,
                    "2026-01-31T18:00:00Z",
                    "2026-01-31T18:10:00Z",
                    null, // closedAt
                    null, // mergedAt
                    1, // commitCount
                    0, // additions
                    0, // deletions
                    0, // fileCount
                    "feature/oauth",
                    "main",
                    null, // diffHeadSha
                    null, // baseSha
                    null, // mergeCommitSha
                    false, // discussionLocked
                    0, // commentsCount
                    "gid://gitlab/User/" + NATIVE_AUTHOR_ID,
                    FIXTURE_AUTHOR_LOGIN,
                    FIXTURE_AUTHOR_LOGIN,
                    null, // authorAvatarUrl
                    null, // authorWebUrl
                    null, // authorPublicEmail
                    null, // mergeUserGlobalId
                    null, // mergeUserUsername
                    null, // mergeUserName
                    null, // mergeUserAvatarUrl
                    null, // mergeUserWebUrl
                    null, // mergeUserPublicEmail
                    null, // syncLabels
                    null, // syncAssignees
                    reviewers,
                    null, // syncApprovers
                    null, // syncParticipants
                    null, // milestoneIid
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null); // closingIssueNumbers
        }

        /** The {@code update} fixture of MR !2, listing {@code reviewers}, at {@code updatedAt} when given. */
        private GitLabMergeRequestEventDTO withReviewers(@Nullable String updatedAt, ObjectNode... reviewers)
                throws IOException {
            ObjectNode payload =
                    (ObjectNode) objectMapper.readTree(new ClassPathResource("gitlab/merge_request.update.json")
                            .getContentAsString(StandardCharsets.UTF_8));
            ArrayNode list = payload.putArray("reviewers");
            for (ObjectNode reviewer : reviewers) {
                list.add(reviewer);
            }
            if (updatedAt != null) {
                ((ObjectNode) payload.get("object_attributes")).put("updated_at", updatedAt);
            }
            return objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class);
        }

        private ObjectNode reviewer(long id, String username, String state) {
            ObjectNode reviewer = objectMapper.createObjectNode();
            reviewer.put("id", id);
            reviewer.put("username", username);
            reviewer.put("name", username);
            reviewer.put("state", state);
            reviewer.put("re_requested", false);
            return reviewer;
        }

        private Map<String, RequestedReviewer.@Nullable ReviewState> reviewerStates() {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                Map<String, RequestedReviewer.@Nullable ReviewState> states = new HashMap<>();
                pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                        .orElseThrow()
                        .getRequestedReviewers()
                        .forEach(request -> states.put(request.getUser().getLogin(), request.getReviewState()));
                return states;
            }));
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabMergeRequestEventDTO event = loadPayload("merge_request.open");

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
            assertThat(pullRequestRepository.count()).isZero();
        }

        @Test
        void idempotency_processSameEventTwice() throws Exception {
            GitLabMergeRequestEventDTO event = loadPayload("merge_request.open");

            handler.handleEvent(event);
            long countAfterFirst = pullRequestRepository.count();

            handler.handleEvent(event);

            assertThat(pullRequestRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void fullLifecycle_openCloseReopen() throws Exception {
            // Open MR !3
            handler.handleEvent(loadPayload("merge_request.open"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.OPEN);
            });

            // Close MR !3
            handler.handleEvent(loadPayload("merge_request.close"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isFalse();

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.CLOSED);
            });

            // Reopen MR !3
            handler.handleEvent(loadPayload("merge_request.reopen"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReopened.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.OPEN);
            });
        }

        @Test
        void fullLifecycle_approveUnapprove() throws Exception {
            // Approve MR !4 (also creates it)
            handler.handleEvent(loadPayload("merge_request.approved"));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, NATIVE_APPROVER_ID);
                assertThat(reviewRepository.findByNativeIdAndProviderId(nativeId, persistedId(savedProvider)))
                        .isPresent();
            });

            // Unapprove MR !4 — should dismiss the review (not delete, not CHANGES_REQUESTED)
            handler.handleEvent(loadPayload("merge_request.unapproved"));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, NATIVE_APPROVER_ID);
                var review = reviewRepository.findByNativeIdAndProviderId(nativeId, persistedId(savedProvider));
                assertThat(review).isPresent();
                assertThat(review.get().getState()).isEqualTo(PullRequestReview.State.DISMISSED);
            });
        }

        @Test
        void fullLifecycle_updateMerge() throws Exception {
            // Create MR !2 via update
            handler.handleEvent(loadPayload("merge_request.update"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);

            // Merge MR !2
            handler.handleEvent(loadPayload("merge_request.merge"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestMerged.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.MERGED);
                assertThat(pr.isMerged()).isTrue();
                assertThat(pr.getTitle()).isEqualTo(MR2_TITLE);
            });
        }

        @Test
        void iidNamespaceIsolation_issueAndMrCoexist() throws Exception {
            // Create an Issue with number=3 in the same repository
            transactionTemplate.executeWithoutResult(status -> {
                issueRepository.upsertCore(
                        /* nativeId */ 888888L,
                        /* providerId */ persistedId(savedProvider),
                        /* number */ MR3_IID, // Same number as MR !3
                        /* title */ "Issue with same IID",
                        /* body */ "This is an issue with the same IID as the MR",
                        /* state */ "OPEN",
                        /* stateReason */ null,
                        /* htmlUrl */ "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/3",
                        /* isLocked */ false,
                        /* closedAt */ null,
                        /* commentsCount */ 0,
                        /* lastSyncAt */ Instant.now(),
                        /* createdAt */ Instant.now(),
                        /* updatedAt */ Instant.now(),
                        /* authorId */ null,
                        /* repositoryId */ savedRepo.getId(),
                        /* milestoneId */ null,
                        /* issueTypeId */ null,
                        /* parentIssueId */ null,
                        /* subIssuesTotal */ null,
                        /* subIssuesCompleted */ null,
                        /* subIssuesPercentCompleted */ null);
            });

            // Now create MR !3
            handler.handleEvent(loadPayload("merge_request.open"));

            // Both should exist independently
            transactionTemplate.executeWithoutResult(status -> {
                // Issue #3 exists as Issue type
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElse(null);
                assertThat(issue).isNotNull();
                assertThat(issue.getTitle()).isEqualTo("Issue with same IID");
                assertThat(issue.isPullRequest()).isFalse();

                // MR !3 exists as PullRequest type
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElse(null);
                assertThat(pr).isNotNull();
                assertThat(pr.getTitle()).isEqualTo(MR3_TITLE);
                assertThat(pr.isPullRequest()).isTrue();
            });
        }
    }

    // Domain Events

    @Nested
    class DomainEvents {

        @Test
        void domainEvents_mr3Lifecycle() throws Exception {
            // Open -> PullRequestCreated
            handler.handleEvent(loadPayload("merge_request.open"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);

            // Close -> PullRequestClosed(wasMerged=false)
            handler.handleEvent(loadPayload("merge_request.close"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isFalse();

            eventListener.clear();

            // Reopen -> PullRequestReopened
            handler.handleEvent(loadPayload("merge_request.reopen"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReopened.class))
                    .hasSize(1);
        }

        @Test
        void domainEvents_mr2Merge() throws Exception {
            // Create via update
            handler.handleEvent(loadPayload("merge_request.update"));
            eventListener.clear();

            // Merge -> PullRequestClosed(wasMerged=true) + PullRequestMerged
            handler.handleEvent(loadPayload("merge_request.merge"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isTrue();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestMerged.class))
                    .hasSize(1);
        }

        @Test
        void domainEvents_mr4Approval() throws Exception {
            // Approve -> ReviewSubmitted
            handler.handleEvent(loadPayload("merge_request.approved"));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);

            eventListener.clear();

            // Unapprove -> ReviewDismissed (not CHANGES_REQUESTED — unapproval is a distinct action)
            handler.handleEvent(loadPayload("merge_request.unapproved"));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .hasSize(1);
        }
    }

    // Author Resolution

    @Nested
    class EntityResolution {

        @Test
        @DisplayName("creates author with native ID and GITLAB provider")
        void shouldCreateAuthorWithCorrectFields() throws Exception {
            assertThat(userRepository.count()).isZero();

            handler.handleEvent(loadPayload("merge_request.open"));

            transactionTemplate.executeWithoutResult(status -> {
                var author = userRepository
                        .findByNativeIdAndProviderId(NATIVE_AUTHOR_ID, persistedId(savedProvider))
                        .orElseThrow();
                assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(author.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);
            });
        }
    }

    // Tombstoned Work

    @Nested
    class TombstonedWork {

        /**
         * The hold is provider-neutral, so this proves it on the GitLab adapter's own path: the merge
         * request, the domain event and the restoring delivery all come from real webhook payloads rather
         * than from a hand-built {@code ScmDomainEvent}.
         *
         * <p>The listener is constructed here because {@code hephaestus.agent.enabled} is off in the
         * integration profile, and driven inside a transaction because its {@code REQUIRES_NEW} proxy is
         * bypassed by direct construction.
         */
        @Test
        void tombstonedMergeRequestHoldsItsOccasionUntilAFurtherDeliveryRestoresIt() throws Exception {
            handler.handleEvent(loadPayload("merge_request.open"));
            ScmDomainEvent.PullRequestCreated created = eventListener
                    .ofType(ScmDomainEvent.PullRequestCreated.class)
                    .getFirst();
            long pullRequestId = pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                    .orElseThrow()
                    .getId();
            assertThat(issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(
                            savedRepo.getId(), List.of(MR3_IID), Instant.now()))
                    .isEqualTo(1);

            var jobs = mock(AgentJobService.class);
            var gate = mock(PracticeReviewDetectionGate.class);
            var listener =
                    new AgentJobEventListener(jobs, pullRequestRepository, gate, workspaceResolver, signalRecorder);
            transactionTemplate.executeWithoutResult(status -> listener.onPullRequestCreated(created));

            ArtifactSignal held = artifactSignalRepository
                    .findForArtifact(savedWorkspace.getId(), ScmSignals.PULL_REQUEST.value(), pullRequestId)
                    .getFirst();
            assertThat(held.getState()).isEqualTo(SignalState.PENDING);
            assertThat(held.getStateReason()).isEqualTo(SignalStateReason.ARTIFACT_NOT_VISIBLE);
            verifyNoInteractions(jobs, gate);

            handler.handleEvent(loadPayload("merge_request.reopen"));
            assertThat(pullRequestRepository
                            .findById(pullRequestId)
                            .orElseThrow()
                            .getDeletedAt())
                    .isNull();

            var decision = new GateDecision.Detect(savedWorkspace, List.of(), 1, TriggerMode.AUTO);
            when(gate.evaluate(any(), eq(ScmSignals.PULL_REQUEST_OPENED), eq(TriggerMode.AUTO)))
                    .thenReturn(decision);
            transactionTemplate.executeWithoutResult(status -> new PullRequestSignalResubmitter(
                            jobs, pullRequestRepository, gate, signalRecorder, reviewRepository)
                    .resubmit(held));

            var request = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
            verify(jobs)
                    .submit(
                            eq(savedWorkspace.getId()),
                            eq(AgentJobType.PULL_REQUEST_REVIEW),
                            request.capture(),
                            eq(held.key()),
                            eq(decision));
            assertThat(request.getValue().pullRequest().id()).isEqualTo(pullRequestId);
            assertThat(request.getValue().headRefName()).isEqualTo(MR3_SOURCE_BRANCH);
            assertThat(request.getValue().baseRefName()).isEqualTo(MR3_TARGET_BRANCH);
            assertThat(request.getValue().triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_OPENED);
        }
    }

    // Helpers

    private GitLabMergeRequestEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("gitlab/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitLabMergeRequestEventDTO.class);
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

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test-gitlab");
        workspace.setDisplayName("HephaestusTest GitLab");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        savedWorkspace = workspaceRepository.save(workspace);
    }

    private static long persistedId(IdentityProvider provider) {
        Long id = provider.getId();
        assertNotNull(id);
        return id;
    }
}
