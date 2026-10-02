package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabPullRequestReviewCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
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
    /** The head of MR !2 in its recorded update hook. */
    private static final String MR2_HEAD = "11499a581bf88090fa5e0abbf6d73e10e6fb56a7";

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    @Autowired
    private ArtifactSignalRepository artifactSignalRepository;

    @Autowired
    private SignalRecorder signalRecorder;

    @Autowired
    private IntegrationManifestRegistry manifests;

    @Autowired
    private WorkspaceResolver workspaceResolver;

    @Autowired
    private GitLabGraphQlResponseHandler graphQlResponseHandler;

    @Autowired
    private GitLabProperties gitLabProperties;

    @Autowired
    private GitLabWebhookContextResolver webhookContextResolver;

    @Autowired
    private NatsMessageDeserializer natsMessageDeserializer;

    @Autowired
    private PullRequestReviewThreadRepository threadRepository;

    @Autowired
    private GitLabPullRequestReviewThreadProcessor threadProcessor;

    @Autowired
    private GitLabPullRequestReviewCommentProcessor reviewCommentProcessor;

    @Autowired
    private GitLabIssueCommentProcessor issueCommentProcessor;

    @Autowired
    private GitLabReviewReconciler reviewReconciler;

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

            receive(event);

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
        void shouldReportAnEditedDescriptionButNotAnUpdateThatLeftTheTextAlone() throws Exception {
            receive(update("2026-01-31 19:04:04 +0100", null));
            eventListener.clear();
            String repaired = "This MR implements OAuth2 so partners can sign in without a password.\n\nCloses #5";

            receive(update("2026-01-31 19:10:00 +0100", repaired));
            receive(update("2026-01-31 19:12:00 +0100", repaired));

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .extracting(ScmDomainEvent.PullRequestUpdated::changedFields)
                    .containsExactly(Set.of("body"), Set.of());
        }

        @Test
        void closeMergeRequest_setsStateToClosed() throws Exception {
            // Create MR !3 first
            receive(loadPayload("merge_request.open"));
            eventListener.clear();

            // Close MR !3
            receive(loadPayload("merge_request.close"));

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
            receive(loadPayload("merge_request.update"));
            eventListener.clear();

            // Merge MR !2
            deliver(loadPayload("merge_request.merge"));

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
            receive(loadPayload("merge_request.open"));
            receive(loadPayload("merge_request.close"));
            eventListener.clear();

            // Reopen MR !3
            receive(loadPayload("merge_request.reopen"));

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
            receive(loadPayload("merge_request.approved"));

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
            receive(loadPayload("merge_request.approved"));
            eventListener.clear();

            // Unapprove MR !4 — should dismiss the review (not delete, not CHANGES_REQUESTED)
            receive(loadPayload("merge_request.unapproved"));

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

    @Nested
    class ReviewerStates {

        /** The reviewers after their reviews, as {@code merge_request.update.reviewers} lists them. */
        private static final Map<String, RequestedReviewer.ReviewState> REVIEWED = Map.of(
                "user1", RequestedReviewer.ReviewState.APPROVED,
                "sjones", RequestedReviewer.ReviewState.REQUESTED_CHANGES,
                "user2", RequestedReviewer.ReviewState.REVIEWED,
                "user3", RequestedReviewer.ReviewState.UNREVIEWED);

        /** The list a sync reads in the snapshot tests: {@code user1} asked again, and no one else. */
        private static final Map<String, RequestedReviewer.ReviewState> SYNCED =
                Map.of("user1", RequestedReviewer.ReviewState.UNREVIEWED);

        private static final Instant STORED_AT = Instant.parse("2026-01-31T18:20:00Z");

        /** The {@code updated_at} of the merge request {@link #syncedMergeRequest} stores, as a hook states it. */
        private static final String SYNCED_UPDATED_AT = "2026-01-31 19:10:00 +0100";

        /** When the second list was stated, against the stored one's {@link #STORED_AT}. */
        enum Order {
            OLDER(Duration.ofMinutes(-1)),
            EQUAL(Duration.ZERO),
            NEWER(Duration.ofSeconds(1));

            private final Duration offset;

            Order(Duration offset) {
                this.offset = offset;
            }

            Instant observedAt() {
                return STORED_AT.plus(offset);
            }
        }

        @Test
        void shouldStoreEachReviewersStateWhenTheMergeRequestListsReviewers() throws Exception {
            receive(loadPayload("merge_request.update.reviewers.derived"));

            assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(REVIEWED);
        }

        @Test
        void shouldSetTheReviewerBackToUnreviewedWhenTheirReviewIsRequestedAgain() throws Exception {
            receive(loadPayload("merge_request.update.reviewers.derived"));

            receive(loadPayload("merge_request.update.review_rerequested.derived"));

            assertThat(reviewerStates())
                    .containsEntry("user1", RequestedReviewer.ReviewState.UNREVIEWED)
                    .containsEntry("sjones", RequestedReviewer.ReviewState.REQUESTED_CHANGES)
                    .hasSize(4);
        }

        @Test
        void shouldApplyTheReviewersOfAPayloadWhoseUpdateTimeEqualsTheStoredOne() throws Exception {
            receive(loadPayload("merge_request.update.reviewers.derived"));

            receive(at("merge_request.update.review_rerequested.derived", "2026-01-31 19:05:00 +0100"));

            assertThat(reviewerStates()).containsEntry("user1", RequestedReviewer.ReviewState.UNREVIEWED);
        }

        @Test
        void shouldKeepTheNewerStateWhenAnOlderPayloadArrivesLate() throws Exception {
            receive(loadPayload("merge_request.update.review_rerequested.derived"));

            receive(loadPayload("merge_request.update.reviewers.derived"));

            assertThat(reviewerStates()).containsEntry("user1", RequestedReviewer.ReviewState.UNREVIEWED);
        }

        /** GitLab before 18.6 lists reviewers without their state, so only the sync states them; who is listed changes. */
        @Test
        void shouldKeepEachStoredStateWhenAHookListsTheReviewersWithoutOne() throws Exception {
            sync(
                    Instant.now().minus(Duration.ofMinutes(1)),
                    syncedReviewer(6L, "user1", "APPROVED"),
                    syncedReviewer(25L, "sjones", "REQUESTED_CHANGES"),
                    syncedReviewer(7L, "user2", "REVIEWED"),
                    syncedReviewer(8L, "user3", "UNREVIEWED"));

            receive(at("merge_request.update.reviewers_stateless.derived", SYNCED_UPDATED_AT));

            assertThat(reviewerStates())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "user1", RequestedReviewer.ReviewState.APPROVED,
                            "sjones", RequestedReviewer.ReviewState.REQUESTED_CHANGES,
                            "user2", RequestedReviewer.ReviewState.REVIEWED));
        }

        /** A state from a GitLab newer than Hephaestus must not leave the verdict it replaced standing. */
        @Test
        void shouldStoreNoStateWhenTheHookStatesOneHephaestusDoesNotKnow() throws Exception {
            receive(loadPayload("merge_request.update.reviewers.derived"));
            ObjectNode payload = (ObjectNode)
                    objectMapper.readTree(new ClassPathResource("gitlab/merge_request.update.reviewers.derived.json")
                            .getContentAsString(StandardCharsets.UTF_8));
            ((ObjectNode) payload.get("object_attributes")).put("updated_at", "2026-01-31 19:06:00 +0100");
            ((ObjectNode) payload.get("reviewers").get(0)).put("state", "review_withdrawn");

            receive(objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class));

            assertThat(reviewerStates()).containsEntry("user1", null);
        }

        /** GitLab leaves {@code reviewers} and {@code assignees} out of a hook when there are none. */
        @Test
        void shouldRemoveTheLastReviewersAndAssigneesWhenTheHookLeavesTheListsOut() throws Exception {
            receive(loadPayload("merge_request.update.reviewers.derived"));

            receive(loadPayload("merge_request.update.reviewers_removed.derived"));

            assertThat(reviewerStates()).isEmpty();
            Boolean assigned = transactionTemplate.execute(status -> !pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow()
                    .getAssignees()
                    .isEmpty());
            assertThat(assigned)
                    .as("the assignee the first hook listed is gone")
                    .isFalse();
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

        /** A sync page applies unless it was asked for before the webhook arrived; applying it twice changes nothing. */
        @ParameterizedTest
        @EnumSource(Order.class)
        void shouldApplyASyncPageUnlessItWasReadBeforeTheStoredWebhook(Order page) throws Exception {
            handler.handle(loadPayload("merge_request.update.reviewers.derived"), STORED_AT);

            sync(page.observedAt(), syncedReviewer(6L, "user1", "UNREVIEWED"));
            sync(page.observedAt(), syncedReviewer(6L, "user1", "UNREVIEWED"));

            assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(page == Order.OLDER ? REVIEWED : SYNCED);
        }

        /**
         * A webhook applies unless it arrived before the stored sync page was asked for, even when it waited in the
         * stream until after; its payload is as new as the merge request the sync stored, so only its arrival dates it.
         */
        @ParameterizedTest
        @EnumSource(Order.class)
        void shouldApplyAWebhookUnlessItArrivedBeforeTheStoredSyncPageWasRead(Order arrival) throws Exception {
            sync(STORED_AT, syncedReviewer(6L, "user1", "UNREVIEWED"));
            GitLabMergeRequestEventDTO webhook = at("merge_request.update.reviewers.derived", SYNCED_UPDATED_AT);

            handler.handle(webhook, arrival.observedAt());
            handler.handle(webhook, arrival.observedAt());

            assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(arrival == Order.OLDER ? SYNCED : REVIEWED);
        }

        /**
         * A sync page read after the webhook arrived is written while the webhook is handled. The webhook reads the
         * merge request under its row lock, so it waits for the sync to commit and compares against the sync's list,
         * not against the one stored before.
         */
        @Test
        void shouldCompareAgainstTheListAConcurrentSyncCommittedWhileTheWebhookWaited() throws Exception {
            Instant syncedAt = STORED_AT.plus(Duration.ofMinutes(2));
            sync(STORED_AT.minus(Duration.ofMinutes(1)), syncedReviewer(6L, "user1", "APPROVED"));
            GitLabMergeRequestEventDTO webhookPayload = at("merge_request.update.reviewers.derived", SYNCED_UPDATED_AT);
            CountDownLatch syncWritten = new CountDownLatch(1);
            CountDownLatch releaseSync = new CountDownLatch(1);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<?> sync = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                    sync(syncedAt, syncedReviewer(6L, "user1", "UNREVIEWED"));
                    syncWritten.countDown();
                    awaitUninterruptibly(releaseSync);
                }));
                assertThat(syncWritten.await(30, TimeUnit.SECONDS)).isTrue();

                Future<?> webhook = threads.submit(() -> handler.handle(webhookPayload, STORED_AT));
                String waitedOn = lockAnotherBackendWaitsOn();
                releaseSync.countDown();
                sync.get(30, TimeUnit.SECONDS);
                webhook.get(30, TimeUnit.SECONDS);

                assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(SYNCED);
                assertThat(reviewersObservedAt()).isEqualTo(syncedAt);
                // A row lock: a plain read never waits on another transaction.
                assertThat(waitedOn).isIn("transactionid", "tuple");
            } finally {
                releaseSync.countDown();
                threads.shutdownNow();
            }
        }

        /**
         * What the one other backend waiting on a lock in this database waits on, once there is one. Its statement text
         * is cut at {@code track_activity_query_size}, so it cannot show the locking clause of a long select.
         */
        private String lockAnotherBackendWaitsOn() throws InterruptedException {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(deadline)) {
                List<@Nullable String> waiting = jdbcTemplate.queryForList("""
                        SELECT wait_event FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                        """, String.class);
                if (!waiting.isEmpty()) {
                    return String.valueOf(waiting.getFirst());
                }
                Thread.sleep(20);
            }
            throw new AssertionError("no backend waited on a lock within 30 seconds");
        }

        private @Nullable Instant reviewersObservedAt() {
            return transactionTemplate.execute(status -> pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow()
                    .getReviewersObservedAt());
        }

        private void awaitUninterruptibly(CountDownLatch latch) {
            try {
                assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        private void sync(Instant fetchedAt, GitLabMergeRequestProcessor.SyncReviewerData... reviewers) {
            mergeRequestProcessor.processFromSync(
                    syncedMergeRequest(false, null, List.of(reviewers), null),
                    ProcessingContext.forSync(null, savedRepo).withObservedAt(fetchedAt));
        }

        /** A recorded payload at another {@code updated_at}, for a test about the order payloads arrive in. */
        private GitLabMergeRequestEventDTO at(String filename, String updatedAt) throws IOException {
            ObjectNode payload = (ObjectNode) objectMapper.readTree(
                    new ClassPathResource("gitlab/" + filename + ".json").getContentAsString(StandardCharsets.UTF_8));
            ((ObjectNode) payload.get("object_attributes")).put("updated_at", updatedAt);
            return objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class);
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

    /**
     * What the merge request's review decision says, from what the sync read about its reviewers. GitLab's
     * {@code approved} means the approval rules are met, which a project requiring no approval meets with nobody
     * approving.
     */
    @Nested
    class ReviewDecisions {

        private static final long APPROVER_ID = 900010L;
        private static final long REQUESTER_ID = 900011L;

        @Test
        void shouldNotCallAMergeRequestApprovedWhenNobodyApprovedAndNoneIsRequired() {
            sync(syncedMergeRequest(true, "MERGEABLE", List.of(), List.of()));

            assertThat(reviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
            assertThat(reviewStates()).isEmpty();
        }

        @Test
        void shouldCallAMergeRequestApprovedWhenSomeoneApprovedAndTheRulesAreMet() {
            sync(syncedMergeRequest(
                    true,
                    "MERGEABLE",
                    List.of(syncedReviewer(APPROVER_ID, "approver", "APPROVED")),
                    List.of(syncedUser(APPROVER_ID, "approver"))));

            assertThat(reviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            assertThat(reviewStates()).containsExactly(Map.entry("approver", PullRequestReview.State.APPROVED));
        }

        @Test
        void shouldNotCallAMergeRequestApprovedWhileRequiredApprovalsAreMissing() {
            sync(syncedMergeRequest(
                    false,
                    "NOT_APPROVED",
                    List.of(syncedReviewer(APPROVER_ID, "approver", "APPROVED")),
                    List.of(syncedUser(APPROVER_ID, "approver"))));

            assertThat(reviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
            assertThat(reviewStates())
                    .as("the approval given still stands")
                    .containsExactly(Map.entry("approver", PullRequestReview.State.APPROVED));
        }

        /** Premium blocks merging on a request for changes; the approval rules can still be met. */
        @Test
        void shouldReportAStandingRequestForChangesOverApprovalsThatMeetTheRules() {
            sync(syncedMergeRequest(
                    true,
                    "REQUESTED_CHANGES",
                    List.of(
                            syncedReviewer(REQUESTER_ID, "requester", "REQUESTED_CHANGES"),
                            syncedReviewer(APPROVER_ID, "approver", "APPROVED")),
                    List.of(syncedUser(APPROVER_ID, "approver"))));

            assertThat(reviewDecision()).isEqualTo(ReviewDecision.CHANGES_REQUESTED);
            assertThat(reviewStates())
                    .as(
                            "the approver's approval is theirs, and the request for changes is the requester's system note's")
                    .containsExactly(Map.entry("approver", PullRequestReview.State.APPROVED));
        }

        /** Without Premium a request for changes blocks nothing, but the reviewer's state still says it stands. */
        @Test
        void shouldReportAReviewersRequestForChangesWhereItDoesNotBlockMerging() {
            sync(syncedMergeRequest(
                    true,
                    "MERGEABLE",
                    List.of(syncedReviewer(REQUESTER_ID, "requester", "REQUESTED_CHANGES")),
                    List.of(syncedUser(APPROVER_ID, "approver"))));

            assertThat(reviewDecision()).isEqualTo(ReviewDecision.CHANGES_REQUESTED);
        }

        @Test
        void shouldLeaveTheDecisionUnknownWhenTheApproversWereNotReadWhole() {
            sync(syncedMergeRequest(true, "MERGEABLE", List.of(), List.of(syncedUser(APPROVER_ID, "approver"))));

            sync(syncedMergeRequest(true, "MERGEABLE", List.of(), null));

            assertThat(reviewDecision()).isNull();
            assertThat(reviewStates())
                    .as("an approval the incomplete list did not name is not taken away either")
                    .containsExactly(Map.entry("approver", PullRequestReview.State.APPROVED));
        }

        @Test
        void shouldLeaveTheDecisionUnknownWhenTheReviewersWereNotReadWhole() {
            sync(syncedMergeRequest(true, "MERGEABLE", null, List.of(syncedUser(APPROVER_ID, "approver"))));

            assertThat(reviewDecision()).isNull();
        }

        /**
         * GitLab sends {@code approval} and {@code unapproval} where the approval rules are not met, or stay met: each is
         * one person's act, and the stored decision no longer stands after it.
         */
        @Test
        void shouldRecordEachApproversActAndForgetTheDecisionItChanged() throws Exception {
            receive(loadPayload("merge_request.approval"));
            assertThat(approvalState()).isEqualTo(PullRequestReview.State.APPROVED);

            setReviewDecision(MR4_IID, ReviewDecision.APPROVED);
            receive(loadPayload("merge_request.unapproval"));

            assertThat(approvalState()).isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(reviewDecision(MR4_IID)).isNull();

            setReviewDecision(MR4_IID, ReviewDecision.REVIEW_REQUIRED);
            receive(loadPayload("merge_request.unapproval"));

            assertThat(reviewDecision(MR4_IID))
                    .as("a redelivered hook changes no one's review, so it leaves a newer sync's decision standing")
                    .isEqualTo(ReviewDecision.REVIEW_REQUIRED);
        }

        private void sync(GitLabMergeRequestProcessor.SyncMergeRequestData data) {
            mergeRequestProcessor.processFromSync(data, ProcessingContext.forSync(null, savedRepo));
        }

        private PullRequestReview.@Nullable State approvalState() {
            long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, NATIVE_APPROVER_ID);
            return reviewRepository
                    .findByNativeIdAndProviderId(nativeId, persistedId(savedProvider))
                    .map(PullRequestReview::getState)
                    .orElse(null);
        }

        private @Nullable ReviewDecision reviewDecision() {
            return reviewDecision(MR2_IID);
        }

        private @Nullable ReviewDecision reviewDecision(int iid) {
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), iid)
                    .orElseThrow()
                    .getReviewDecision();
        }

        private void setReviewDecision(int iid, ReviewDecision decision) {
            transactionTemplate.executeWithoutResult(status -> pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), iid)
                    .orElseThrow()
                    .setReviewDecision(decision));
        }

        /** Each review of MR !2 by its author's login. */
        private Map<String, PullRequestReview.State> reviewStates() {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                Map<String, PullRequestReview.State> states = new HashMap<>();
                pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                        .orElseThrow()
                        .getReviews()
                        .forEach(review -> states.put(
                                Objects.requireNonNull(review.getAuthor()).getLogin(), review.getState()));
                return states;
            }));
        }
    }

    /**
     * An approval act, and GitLab's own reset, apply to the merge request as stored now: the head a hook names decides
     * that, not when it arrived. Then the dated review snapshot orders it against reads of the approvals.
     */
    @Nested
    class ApprovalActsOnTheCurrentHead {

        @Test
        void shouldIgnoreAnApprovalOfAnEarlierHeadThatArrivesAfterThePush() throws Exception {
            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            eventListener.clear();

            // The recorded approval names the fixture head and an older version, delivered late.
            handler.handle(loadPayload("merge_request.approved"), Instant.now());

            assertThat(approval(NATIVE_APPROVER_ID)).isNull();
            assertThat(storedHead()).isEqualTo(NEXT_HEAD);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"approvals_reset_on_push", "code_owner_approvals_reset_on_push"})
        void shouldNotLetAResetOfAnEarlierHeadUndoAnApprovalOfTheCurrentOne(String systemAction) throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            setReviewDecision(ReviewDecision.APPROVED);
            eventListener.clear();

            receive(systemReset(FIXTURE_HEAD, "2026-01-31 22:13:55 +0100", systemAction));

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.isDismissed()).isFalse();
            assertThat(approval.getCommitId()).isEqualTo(NEXT_HEAD);
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .isEmpty();
        }

        @Test
        void shouldForgetTheDecisionButDismissNoOneWhenGitLabResetsApprovalsOnTheCurrentHead() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            setReviewDecision(ReviewDecision.APPROVED);
            eventListener.clear();

            // Its user is whoever pushed; the reset does not say whose approvals went.
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:31:00 +0100", "approvals_reset_on_push"));

            assertThat(reviewDecision()).isNull();
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .isEmpty();
        }

        @Test
        void shouldIgnoreAnApprovalOfAnOlderVersionOfTheSameHeadThatArrivesLate() throws Exception {
            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            eventListener.clear();

            handler.handle(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:20:00 +0100"), Instant.now());

            assertThat(approval(NATIVE_APPROVER_ID)).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldNotLetAWithdrawalOrResetOfAnOlderVersionOfTheSameHeadUndoTheApproval(boolean system)
                throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            setReviewDecision(ReviewDecision.APPROVED);
            eventListener.clear();

            handler.handle(
                    system
                            ? systemReset(NEXT_HEAD, "2026-01-31 22:20:00 +0100", "approvals_reset_on_push")
                            : approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:20:00 +0100"),
                    Instant.now());

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.isDismissed()).isFalse();
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .isEmpty();
        }

        @Test
        void shouldApplyAWithdrawalOfTheStoredVersionReceivedLater() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            eventListener.clear();

            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .hasSize(1);
        }

        @Test
        void shouldRecordAnApprovalOfThePushedHeadOverTheApprovalGitLabResetOnThePush() throws Exception {
            handler.handle(loadPayload("merge_request.approved"), Instant.now());
            // GitLab's reset on the push says whose approvals went only by resetting them all, so it dismisses no one.
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:30:00 +0100", "approvals_reset_on_push"));
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getCommitId())
                    .isEqualTo(FIXTURE_HEAD);
            eventListener.clear();

            GitLabMergeRequestEventDTO reapproval =
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100");
            receive(reapproval);
            receive(reapproval);

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.getCommitId()).isEqualTo(NEXT_HEAD);
            // Once: the redelivery approves the head already recorded.
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldKeepTheStandingApprovalWhenAnApprovalAfterThePushNamesNoHead(boolean blank) throws Exception {
            handler.handle(loadPayload("merge_request.approved"), Instant.now());
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:30:00 +0100", "approvals_reset_on_push"));
            eventListener.clear();

            receive(headless("merge_request.approved", "2026-01-31 22:30:00 +0100", blank));

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.getCommitId()).isEqualTo(FIXTURE_HEAD);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldRecordNoApprovalThatNamesNoHead(boolean blank) throws Exception {
            receive(headless("merge_request.approved", "2026-01-31 19:41:31 +0100", blank));

            assertThat(approval(NATIVE_APPROVER_ID)).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @Test
        void shouldRecordTwoPeoplesApprovalsReceivedAtTheSameInstant() throws Exception {
            Instant receivedAt = Instant.parse("2026-09-30T10:00:00.000001Z");

            handler.handle(loadPayload("merge_request.approved"), receivedAt);
            handler.handle(asTutor(loadPayload("merge_request.approved")), receivedAt);

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(Objects.requireNonNull(approval(NATIVE_TUTOR_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
        }
    }

    /**
     * What a read of one merge request after its webhook records ({@link GitLabMergeRequestProcessor#applyReadiness}),
     * and what it may not.
     */
    @Nested
    class ReadinessRead {

        /** A read begun after the hook below was received, so no stored review snapshot is newer. */
        private Instant readAt = Instant.EPOCH;

        @BeforeEach
        void storeTheMergeRequestAtItsNextHead() throws Exception {
            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            readAt = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }

        @Test
        void shouldRemoveAGuessedApprovalDateOnlyOnAnAcceptedWholeSnapshotWithoutAnotherApprovalEvent()
                throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            PullRequestReview initial = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(initial.getSubmittedAt()).isNull();
            Instant legacyDate = Instant.parse("2026-01-31T21:00:00Z");
            transactionTemplate.executeWithoutResult(tx ->
                    reviewRepository.findById(initial.getId()).orElseThrow().setSubmittedAt(legacyDate));
            eventListener.clear();

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, null, "mergeable"), readAt);
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(legacyDate);
            read(
                    facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "approvals_syncing"),
                    readAt.plusSeconds(1));
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(legacyDate);
            assertThat(read(
                            facts(
                                    FIXTURE_HEAD,
                                    GitLabHeadPipeline.NOT_CAPTURED,
                                    true,
                                    List.of(approver()),
                                    "mergeable"),
                            readAt.plusSeconds(2)))
                    .isFalse();
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(legacyDate);
            read(
                    facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable"),
                    readAt.minusSeconds(1));
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(legacyDate);

            read(
                    facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable"),
                    readAt.plusSeconds(3));
            PullRequestReview current = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(current.getId()).isEqualTo(initial.getId());
            assertThat(current.getSubmittedAt()).isNull();
            assertThat(current.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(current.isDismissed()).isFalse();
            assertThat(current.getCommitId()).isEqualTo(NEXT_HEAD);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @Test
        void shouldPersistAMatchingMergedNativeDateWithoutAnotherApprovalEvent() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            PullRequestReview initial = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            transactionTemplate.executeWithoutResult(tx -> {
                PullRequest merged = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR4_IID)
                        .orElseThrow();
                merged.setState(Issue.State.MERGED);
                reviewRepository
                        .findById(initial.getId())
                        .orElseThrow()
                        .setSubmittedAt(Instant.parse("2026-01-31T20:00:00Z"));
            });
            eventListener.clear();
            var actualDate = Instant.parse("2026-01-31T21:00:19.087Z");
            var rows = new GitLabApprovalClient.Snapshot(
                    NATIVE_MR4_ID,
                    MR4_IID,
                    savedRepo.getNativeId(),
                    "merged",
                    null,
                    null,
                    null,
                    List.of(new GitLabApprovalClient.Approval(
                            new GitLabApprovalClient.Approver(NATIVE_APPROVER_ID), actualDate)));
            var opened = facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable");
            var merged = new GitLabMergeRequestReadinessReader.Facts(
                    opened.projectNativeId(),
                    opened.mergeRequestNativeId(),
                    "merged",
                    opened.updatedAt(),
                    opened.headSha(),
                    opened.mergeable(),
                    opened.detailedMergeStatus(),
                    opened.approved(),
                    opened.headPipeline(),
                    opened.reviewers(),
                    opened.approvers(),
                    opened.merge(),
                    rows);

            assertThat(read(merged, readAt)).isTrue();
            PullRequestReview dated = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(dated.getSubmittedAt()).isEqualTo(actualDate);
            assertThat(dated.getCommitId()).isEqualTo(NEXT_HEAD);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
            assertThat(read(merged.withApprovalRows(null), readAt.plusSeconds(1)))
                    .isTrue();
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(actualDate);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void shouldKeepSnapshotApprovalMembershipWhenOldDecisionNotesAreReplayed(boolean listed) throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            read(
                    facts(
                            NEXT_HEAD,
                            GitLabHeadPipeline.NOT_CAPTURED,
                            listed,
                            listed ? List.of(approver()) : List.of(),
                            "mergeable"),
                    readAt);
            PullRequestReview current = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            transactionTemplate.executeWithoutResult(tx -> {
                PullRequest mr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR4_IID)
                        .orElseThrow();
                var author = Objects.requireNonNull(
                        reviewRepository.findById(current.getId()).orElseThrow().getAuthor());
                for (String body :
                        List.of("unapproved this merge request", "approved this merge request", "requested changes")) {
                    reviewReconciler.recordSystemNote(
                            mr,
                            author,
                            new GitLabReviewReconciler.SystemNote(
                                    body, Instant.parse("2026-01-01T10:00:00Z"), "gid://gitlab/Note/987654", false),
                            savedRepo.getProvider());
                }
            });
            PullRequestReview after = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(after.getState())
                    .isEqualTo(listed ? PullRequestReview.State.APPROVED : PullRequestReview.State.DISMISSED);
            assertThat(after.isDismissed()).isEqualTo(!listed);
            assertThat(after.getSubmittedAt()).isNull();
            assertThat(after.getCommitId()).isEqualTo(NEXT_HEAD);
        }

        @Test
        void shouldRecordThatTheCurrentHeadHasNoPipeline() {
            assertThat(read(facts(NEXT_HEAD, GitLabHeadPipeline.NO_PIPELINE, true, List.of(), "mergeable"), readAt))
                    .isTrue();

            PullRequest pr = stored();
            assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.NO_PIPELINE);
            assertThat(pr.getHeadCheckSha()).isEqualTo(NEXT_HEAD);
            assertThat(pr.getHeadCheckObservedAt()).isEqualTo(readAt);
            assertThat(pr.getMergeable()).isTrue();
            assertThat(pr.getMergeStateStatus()).isEqualTo(MergeStateStatus.CLEAN);
            // GitLab's approved alone, with nobody approving, is not an approval.
            assertThat(pr.getReviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
        }

        @Test
        void shouldKeepAPipelineReadLaterOverAnEarlierReadAppliedAfterIt() {
            read(facts(NEXT_HEAD, GitLabHeadPipeline.reported("SUCCESS", NEXT_HEAD), true, null, "mergeable"), readAt);

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NO_PIPELINE, true, null, "mergeable"), readAt.minusSeconds(1));
            read(
                    facts(NEXT_HEAD, GitLabHeadPipeline.reported("FAILED", NEXT_HEAD), true, null, "mergeable"),
                    readAt.minusSeconds(1));

            assertThat(stored().getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
        }

        @Test
        void shouldForgetTheMergeabilityAWithdrawalChangesAndRecoverItOnTheNextRead() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            setReadiness(true, MergeStateStatus.CLEAN, ReviewDecision.APPROVED);

            // Its readiness read failed: only the hook itself is recorded.
            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:32:00 +0100"));

            PullRequest withdrawn = stored();
            assertThat(withdrawn.getReviewDecision()).isNull();
            assertThat(withdrawn.getMergeable()).isNull();
            assertThat(withdrawn.getMergeStateStatus()).isNull();
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);

            read(mergeability(false, "not_approved"), Instant.now());

            assertThat(stored().getMergeStateStatus()).isEqualTo(MergeStateStatus.BLOCKED);
            assertThat(stored().getMergeable()).isFalse();
            assertThat(stored().getReviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
        }

        @Test
        void shouldForgetTheMergeabilityAResetChangesWithoutDismissingAnyoneAndRecoverItOnTheNextRead()
                throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            setReadiness(true, MergeStateStatus.CLEAN, ReviewDecision.APPROVED);

            receive(systemReset(NEXT_HEAD, "2026-01-31 22:32:00 +0100", "approvals_reset_on_push"));

            PullRequest reset = stored();
            assertThat(reset.getReviewDecision()).isNull();
            assertThat(reset.getMergeable()).isNull();
            assertThat(reset.getMergeStateStatus()).isNull();
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);

            read(mergeability(false, "not_approved"), Instant.now());

            assertThat(stored().getMergeStateStatus()).isEqualTo(MergeStateStatus.BLOCKED);
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
        }

        @Test
        void shouldLeaveReadinessUnknownRatherThanRecordAReadThatCannotShowItIsNotOlderThanAMillisecondHook()
                throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31T22:31:00.080+01:00"));

            // GitLab's GraphQL dates to the second: this read may describe the version before the hook's.
            assertThat(read(approvedAt(Instant.parse("2026-01-31T21:31:00Z")), readAt))
                    .isFalse();

            assertThat(stored().getMergeable()).isNull();
            assertThat(stored().getReviewDecision()).isNull();
        }

        private GitLabMergeRequestReadinessReader.Facts approvedAt(Instant version) {
            return new GitLabMergeRequestReadinessReader.Facts(
                    savedRepo.getNativeId(),
                    NATIVE_MR4_ID,
                    "opened",
                    version,
                    NEXT_HEAD,
                    true,
                    "mergeable",
                    true,
                    GitLabHeadPipeline.NOT_CAPTURED,
                    List.of(),
                    List.of(approver()),
                    GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                    null);
        }

        private void setReadiness(boolean mergeable, MergeStateStatus status, ReviewDecision decision) {
            transactionTemplate.executeWithoutResult(tx -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR4_IID)
                        .orElseThrow();
                pr.setMergeable(mergeable);
                pr.setMergeStateStatus(status);
                pr.setReviewDecision(decision);
            });
        }

        @Test
        void shouldKeepTheMergeabilityOfALaterReadOverAnEarlierReadAppliedAfterIt() {
            read(mergeability(false, "not_approved"), readAt);

            read(mergeability(true, "mergeable"), readAt.minusSeconds(1));

            PullRequest pr = stored();
            assertThat(pr.getMergeable()).isFalse();
            assertThat(pr.getMergeStateStatus()).isEqualTo(MergeStateStatus.BLOCKED);
            assertThat(pr.getReviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
        }

        @Test
        void shouldTakeTheMergeabilityOfALaterReadThatSeesTheMergeRequestRecover() {
            read(mergeability(false, "not_approved"), readAt);

            read(mergeability(true, "mergeable"), readAt.plusSeconds(1));

            PullRequest pr = stored();
            assertThat(pr.getMergeable()).isTrue();
            assertThat(pr.getMergeStateStatus()).isEqualTo(MergeStateStatus.CLEAN);
            assertThat(pr.getReviewDecision()).isEqualTo(ReviewDecision.APPROVED);
        }

        /** A read of the stored head and version that GitLab answered with {@code mergeable} and {@code status}. */
        private GitLabMergeRequestReadinessReader.Facts mergeability(boolean mergeable, String status) {
            return new GitLabMergeRequestReadinessReader.Facts(
                    savedRepo.getNativeId(),
                    NATIVE_MR4_ID,
                    "opened",
                    Objects.requireNonNull(stored().getUpdatedAt()),
                    NEXT_HEAD,
                    mergeable,
                    status,
                    mergeable,
                    GitLabHeadPipeline.NOT_CAPTURED,
                    List.of(),
                    mergeable ? List.of(approver()) : List.of(),
                    GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                    null);
        }

        @Test
        void shouldRecordNothingAboutAnotherHeadThanTheStoredOne() {
            assertThat(read(facts(FIXTURE_HEAD, GitLabHeadPipeline.NO_PIPELINE, true, List.of(), "mergeable"), readAt))
                    .isFalse();

            assertThat(stored().getHeadCheckState()).isNull();
            assertThat(stored().getMergeable()).isNull();
        }

        @Test
        void shouldDismissTheTutorWhoseApprovalGitLabResetOnThePush() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:32:00 +0100", "approvals_reset_on_push"));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, false, List.of(), "not_approved"), Instant.now());

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(approval.isDismissed()).isTrue();
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
        }

        @Test
        void shouldKeepTheApproversASelectiveResetLeft() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            receive(asTutor(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100")));
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:32:00 +0100", "code_owner_approvals_reset_on_push"));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(tutor()), "mergeable"), Instant.now());

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(Objects.requireNonNull(approval(NATIVE_TUTOR_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.APPROVED);
        }

        @Test
        void shouldGiveAnApprovalGivenAgainItsStandingAndTheCurrentHead() throws Exception {
            receive(approvalEvent("merge_request.approved", FIXTURE_HEAD, "2026-01-31 22:31:00 +0100"));
            receive(approvalEvent("merge_request.unapproved", FIXTURE_HEAD, "2026-01-31 22:32:00 +0100"));
            // The tutor never approved, so this hook only moves the head.
            receive(asTutor(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:33:00 +0100")));

            read(
                    facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable"),
                    Instant.now());

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.isDismissed()).isFalse();
            assertThat(approval.getCommitId()).isEqualTo(NEXT_HEAD);
        }

        @Test
        void shouldNotLetAReadBegunBeforeAWithdrawalPutTheApprovalBack() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));
            Instant readBegun = Instant.now();
            handler.handle(
                    approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:32:00 +0100"),
                    readBegun.plusSeconds(1));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable"), readBegun);

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(reviewDecision()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"checking", "approvals_syncing"})
        void shouldConfirmNothingWhileGitLabIsStillSettlingTheMergeRequest(String status) throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(), status), Instant.now());

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isNull();
            assertThat(stored().getMergeable()).isNull();
        }

        @Test
        void shouldLeaveTheApprovalsAndTheDecisionUnknownWhenTheApproversWereNotRead() throws Exception {
            receive(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, null, "mergeable"), Instant.now());

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isNull();
        }

        private boolean read(GitLabMergeRequestReadinessReader.Facts facts, Instant requestedAt) {
            return mergeRequestProcessor.applyReadiness(
                    savedRepo, MR4_IID, facts, requestedAt, ProcessingContext.forSync(null, savedRepo));
        }

        private GitLabMergeRequestReadinessReader.Facts facts(
                String head,
                GitLabHeadPipeline pipeline,
                @Nullable Boolean approved,
                @Nullable List<GitLabMergeRequestProcessor.SyncUserData> approvers,
                String detailedMergeStatus) {
            return new GitLabMergeRequestReadinessReader.Facts(
                    savedRepo.getNativeId(),
                    NATIVE_MR4_ID,
                    "opened",
                    Instant.parse("2026-01-31T21:33:00Z"),
                    head,
                    true,
                    detailedMergeStatus,
                    approved,
                    pipeline,
                    List.of(),
                    approvers,
                    GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                    null);
        }
    }

    /**
     * A sync page that failed to give a merge request's head records nothing that holds for a head: the upsert keeps
     * the stored head, which is not the one the page read.
     */
    @Nested
    class SyncedPagesWithoutTheirHead {

        private static final String FIRST_HEAD = "d".repeat(40);

        @Test
        void shouldRecordNoPipelineNoApprovalAndNoReadinessOnTheStoredHeadWhenThePageLostItsHead() {
            syncPage(
                    page(
                            FIRST_HEAD,
                            "2026-01-31T18:10:00Z",
                            Map.of("status", "SUCCESS", "sha", FIRST_HEAD),
                            "mergeable",
                            List.of(approverNode())),
                    List.of());
            PullRequest before = mr2();
            assertThat(before.getHeadRefOid()).isEqualTo(FIRST_HEAD);
            assertThat(before.getMergeStateStatus()).isEqualTo(MergeStateStatus.CLEAN);
            assertThat(before.getReviewDecision()).isEqualTo(ReviewDecision.APPROVED);

            Map<String, @Nullable Object> lost =
                    page(null, "2026-01-31T18:20:00Z", null, "not_approved", List.of(approverNode(), tutorNode()));
            syncPage(
                    lost,
                    List.of(GraphQlResponses.error(
                            "Internal server error", "project", "mergeRequests", "nodes", 0, "diffHeadSha")));

            PullRequest after = mr2();
            assertThat(after.getHeadRefOid()).isEqualTo(FIRST_HEAD);
            assertThat(after.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
            assertThat(after.getMergeStateStatus()).isEqualTo(MergeStateStatus.CLEAN);
            assertThat(after.getReviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            assertThat(mr2Approval(NATIVE_TUTOR_ID)).isNull();

            syncPage(page(NEXT_HEAD, "2026-01-31T18:30:00Z", null, "not_approved", List.of(tutorNode())), List.of());

            PullRequest recovered = mr2();
            assertThat(recovered.getHeadRefOid()).isEqualTo(NEXT_HEAD);
            assertThat(recovered.getHeadCheckState()).isEqualTo(CheckState.NO_PIPELINE);
            assertThat(recovered.getHeadCheckSha()).isEqualTo(NEXT_HEAD);
            assertThat(recovered.getMergeStateStatus()).isEqualTo(MergeStateStatus.BLOCKED);
            PullRequestReview tutorApproval = Objects.requireNonNull(mr2Approval(NATIVE_TUTOR_ID));
            assertThat(tutorApproval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(tutorApproval.getCommitId()).isEqualTo(NEXT_HEAD);
        }

        @Test
        void shouldChangeNothingStoredWhenThePageLostTheVersion() {
            syncPage(page(FIRST_HEAD, "2026-01-31T18:10:00Z", null, "mergeable", List.of(approverNode())), List.of());

            Map<String, @Nullable Object> lost =
                    page(NEXT_HEAD, null, null, "not_approved", List.of(approverNode(), tutorNode()));
            syncPage(
                    lost,
                    List.of(GraphQlResponses.error(
                            "Internal server error", "project", "mergeRequests", "nodes", 0, "updatedAt")));

            PullRequest after = mr2();
            assertThat(after.getHeadRefOid()).isEqualTo(FIRST_HEAD);
            assertThat(after.getHeadCheckSha()).isEqualTo(FIRST_HEAD);
            assertThat(after.getMergeStateStatus()).isEqualTo(MergeStateStatus.CLEAN);
            assertThat(mr2Approval(NATIVE_TUTOR_ID)).isNull();
        }

        @Test
        void shouldRecoverNativeApprovalDatesThroughTheMergedDiscoveryPage() {
            var node = page(FIRST_HEAD, "2026-01-31T18:30:00Z", null, "mergeable", List.of(approverNode()));
            node.put("state", "merged");
            node.put("description", "Why this change is needed");
            var actualDate = Instant.parse("2026-01-31T18:20:19.087Z");
            var rows = new GitLabApprovalClient.Snapshot(
                    NATIVE_MR2_ID,
                    MR2_IID,
                    savedRepo.getNativeId(),
                    "merged",
                    null,
                    null,
                    null,
                    List.of(new GitLabApprovalClient.Approval(
                            new GitLabApprovalClient.Approver(NATIVE_APPROVER_ID), actualDate)));
            syncPage(node, List.of(), rows);

            PullRequestReview approval = Objects.requireNonNull(mr2Approval(NATIVE_APPROVER_ID));
            assertThat(mr2().getState()).isEqualTo(Issue.State.MERGED);
            assertThat(approval.getSubmittedAt()).isEqualTo(actualDate);
            assertThat(approval.getCommitId()).isEqualTo(FIRST_HEAD);
            eventListener.clear();
            syncPage(node, List.of(), rows);
            assertThat(Objects.requireNonNull(mr2Approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(actualDate);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
            var preciseVersion = Instant.parse("2026-01-31T18:30:00.080Z");
            transactionTemplate.executeWithoutResult(tx -> {
                var mr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                        .orElseThrow();
                mr.setUpdatedAt(preciseVersion);
                reviewRepository.findById(approval.getId()).orElseThrow().setSubmittedAt(null);
            });
            var mirrored = mr2();
            var preciseRows = new GitLabApprovalClient.Snapshot(
                    NATIVE_MR2_ID,
                    MR2_IID,
                    savedRepo.getNativeId(),
                    "merged",
                    preciseVersion,
                    mirrored.getTitle(),
                    mirrored.getBody(),
                    rows.approvedBy());
            syncPage(node, List.of(), preciseRows);
            assertThat(Objects.requireNonNull(mr2Approval(NATIVE_APPROVER_ID)).getSubmittedAt())
                    .isEqualTo(actualDate);
            assertThat(mr2().getUpdatedAt()).isEqualTo(preciseVersion);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        /** Merge request !2 as GitLab's listing gives it; a null head, version or pipeline is sent as null. */
        private Map<String, @Nullable Object> page(
                @Nullable String head,
                @Nullable String updatedAt,
                @Nullable Map<String, ?> pipeline,
                String detailedMergeStatus,
                List<Map<String, @Nullable Object>> approvers) {
            Map<String, @Nullable Object> node = new HashMap<>();
            node.put("id", "gid://gitlab/MergeRequest/" + NATIVE_MR2_ID);
            node.put("iid", String.valueOf(MR2_IID));
            node.put("title", MR2_TITLE);
            node.put("state", "opened");
            node.put("createdAt", "2026-01-31T18:00:00Z");
            node.put("updatedAt", updatedAt);
            node.put("sourceBranch", "feature/oauth");
            node.put("targetBranch", "main");
            node.put("diffHeadSha", head);
            node.put("mergeable", "mergeable".equals(detailedMergeStatus));
            node.put("detailedMergeStatus", detailedMergeStatus);
            node.put("approved", true);
            node.put("headPipeline", pipeline);
            node.put("reviewers", connection(List.of()));
            node.put("approvedBy", connection(approvers));
            return node;
        }

        private Map<String, @Nullable Object> approverNode() {
            return userNode(NATIVE_APPROVER_ID, "project_246765_bot_75d5fb2b096a67c668541ae88aa22385");
        }

        private Map<String, @Nullable Object> tutorNode() {
            return userNode(NATIVE_TUTOR_ID, "tutor");
        }

        private PullRequest mr2() {
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow();
        }

        private @Nullable PullRequestReview mr2Approval(long userNativeId) {
            long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR2_ID, userNativeId);
            return reviewRepository
                    .findByNativeIdAndProviderId(nativeId, persistedId(savedProvider))
                    .orElse(null);
        }
    }

    /** A sync page's head pipeline is dated by when the page was asked for, like the pipeline hook. */
    @Nested
    class SyncedHeadChecks {

        @Test
        void shouldKeepTheHeadChecksOfALaterReadOverAnEarlierPageAppliedAfterIt() {
            Instant later = Instant.parse("2026-09-30T10:00:05Z");
            sync(later, GitLabHeadPipeline.reported("FAILED", NEXT_HEAD));

            sync(later.minusSeconds(3), GitLabHeadPipeline.reported("SUCCESS", NEXT_HEAD));
            sync(later.minusSeconds(3), GitLabHeadPipeline.NO_PIPELINE);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow();
            assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
            assertThat(pr.getHeadCheckObservedAt()).isEqualTo(later);
        }

        private void sync(Instant fetchedAt, GitLabHeadPipeline pipeline) {
            mergeRequestProcessor.processFromSync(
                    syncedMergeRequest(true, "mergeable", null, null, NEXT_HEAD, pipeline),
                    ProcessingContext.forSync(null, savedRepo).withObservedAt(fetchedAt));
        }

        @Test
        void shouldKeepTheMergeStatusOfALaterPageOverAnEarlierPageAppliedAfterIt() {
            Instant later = Instant.parse("2026-09-30T10:00:05Z");
            syncStatus(later, "not_approved");

            syncStatus(later.minusSeconds(3), "mergeable");

            assertThat(mergeStatus()).isEqualTo(MergeStateStatus.BLOCKED);
        }

        @Test
        void shouldTakeTheMergeStatusOfALaterPageThatSeesTheMergeRequestRecover() {
            Instant earlier = Instant.parse("2026-09-30T10:00:05Z");
            syncStatus(earlier, "not_approved");

            syncStatus(earlier.plusSeconds(3), "mergeable");

            assertThat(mergeStatus()).isEqualTo(MergeStateStatus.CLEAN);
        }

        private void syncStatus(Instant fetchedAt, String detailedMergeStatus) {
            mergeRequestProcessor.processFromSync(
                    syncedMergeRequest(
                            true,
                            detailedMergeStatus,
                            List.of(),
                            List.of(),
                            NEXT_HEAD,
                            GitLabHeadPipeline.NOT_CAPTURED),
                    ProcessingContext.forSync(null, savedRepo).withObservedAt(fetchedAt));
        }

        private @Nullable MergeStateStatus mergeStatus() {
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow()
                    .getMergeStateStatus();
        }
    }

    private Map<String, Object> connection(List<Map<String, @Nullable Object>> nodes) {
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", false);
        pageInfo.put("endCursor", null);
        return Map.of("count", nodes.size(), "pageInfo", pageInfo, "nodes", nodes);
    }

    private Map<String, @Nullable Object> userNode(long id, String username) {
        Map<String, @Nullable Object> user = new HashMap<>();
        user.put("id", "gid://gitlab/User/" + id);
        user.put("username", username);
        user.put("name", username);
        return user;
    }

    /** Runs the merge request sync over one page GitLab answered with {@code node} and {@code errors}. */
    private void syncPage(Map<String, @Nullable Object> node, List<Map<String, ?>> errors) {
        syncPage(node, errors, null);
    }

    private void syncPage(
            Map<String, @Nullable Object> node,
            List<Map<String, ?>> errors,
            GitLabApprovalClient.@Nullable Snapshot rows) {
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", false);
        pageInfo.put("endCursor", null);
        List<Map<String, @Nullable Object>> nodes = List.of(node);
        assertVendorCouldReturn(GITLAB, "GetProjectMergeRequests", "project.mergeRequests.nodes", nodes);
        ClientGraphQlResponse response = GraphQlResponses.of(
                Map.of("project", Map.of("mergeRequests", Map.of("count", 1, "pageInfo", pageInfo, "nodes", nodes))),
                errors);
        GitLabGraphQlClientProvider provider = mock(GitLabGraphQlClientProvider.class);
        when(provider.forScope(any())).thenReturn(ScriptedGraphQlClient.of(request -> Mono.just(response)));
        var approvalClient = mock(GitLabApprovalClient.class);
        if (rows != null)
            when(approvalClient.read(1L, savedRepo.getNativeId(), Integer.parseInt(Objects.toString(node.get("iid")))))
                    .thenReturn(rows);
        new GitLabMergeRequestSyncService(
                        provider,
                        graphQlResponseHandler,
                        mergeRequestProcessor,
                        mock(GitLabDiscussionSyncService.class),
                        mock(GitLabClosingIssueClient.class),
                        gitLabProperties,
                        approvalClient)
                .syncMergeRequests(1L, savedRepo, null);
    }

    private static final String FIXTURE_HEAD = "2be093fe73e06752381b635b99518c2255ee7946";
    private static final String NEXT_HEAD = "c".repeat(40);
    private static final long NATIVE_TUTOR_ID = 99_001L;

    /** A recorded approval hook of MR !4, naming {@code head} and GitLab's {@code updatedAt}. */
    private GitLabMergeRequestEventDTO approvalEvent(String filename, String head, String updatedAt)
            throws IOException {
        return edited(filename, attributes -> {
            ((ObjectNode) attributes.get("last_commit")).put("id", head);
            attributes.put("updated_at", updatedAt);
        });
    }

    /** A recorded hook of MR !4 at GitLab's {@code updatedAt} that names no head: none, or a blank one. */
    private GitLabMergeRequestEventDTO headless(String filename, String updatedAt, boolean blank) throws IOException {
        return edited(filename, attributes -> {
            if (blank) {
                ((ObjectNode) attributes.get("last_commit")).put("id", "");
            } else {
                attributes.remove("last_commit");
            }
            attributes.put("updated_at", updatedAt);
        });
    }

    /** GitLab's own reset of MR !4's approvals after a push, as its unapproval hook with {@code system}. */
    private GitLabMergeRequestEventDTO systemReset(String head, String updatedAt, String systemAction)
            throws IOException {
        return edited("merge_request.unapproved", attributes -> {
            ((ObjectNode) attributes.get("last_commit")).put("id", head);
            attributes.put("updated_at", updatedAt);
            attributes.put("system", true);
            attributes.put("system_action", systemAction);
        });
    }

    private GitLabMergeRequestEventDTO edited(String filename, java.util.function.Consumer<ObjectNode> edit)
            throws IOException {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(
                new ClassPathResource("gitlab/" + filename + ".json").getContentAsString(StandardCharsets.UTF_8));
        edit.accept((ObjectNode) payload.get("object_attributes"));
        return objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class);
    }

    /** The same hook, sent for the tutor. */
    private GitLabMergeRequestEventDTO asTutor(GitLabMergeRequestEventDTO event) {
        ObjectNode payload = objectMapper.valueToTree(event);
        ((ObjectNode) payload.get("user"))
                .put("id", NATIVE_TUTOR_ID)
                .put("username", "tutor")
                .put("name", "Tutor");
        return objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class);
    }

    private GitLabMergeRequestProcessor.SyncUserData approver() {
        return syncedUser(NATIVE_APPROVER_ID, "project_246765_bot_75d5fb2b096a67c668541ae88aa22385");
    }

    private GitLabMergeRequestProcessor.SyncUserData tutor() {
        return syncedUser(NATIVE_TUTOR_ID, "tutor");
    }

    /** MR !4's approval review by the user GitLab knows as {@code userNativeId}, or null. */
    private @Nullable PullRequestReview approval(long userNativeId) {
        long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, userNativeId);
        return reviewRepository
                .findByNativeIdAndProviderId(nativeId, persistedId(savedProvider))
                .orElse(null);
    }

    private PullRequest stored() {
        return pullRequestRepository
                .findByRepositoryIdAndNumber(savedRepo.getId(), MR4_IID)
                .orElseThrow();
    }

    private @Nullable String storedHead() {
        return stored().getHeadRefOid();
    }

    private @Nullable ReviewDecision reviewDecision() {
        return stored().getReviewDecision();
    }

    private void setReviewDecision(ReviewDecision decision) {
        transactionTemplate.executeWithoutResult(status -> pullRequestRepository
                .findByRepositoryIdAndNumber(savedRepo.getId(), MR4_IID)
                .orElseThrow()
                .setReviewDecision(decision));
    }

    // Discussion resolution

    /**
     * An update hook names no thread, and GitLab sends one when the last open thread is resolved: the discussions are
     * read after it and their resolution recorded where the delivery may still write.
     */
    @Nested
    class DiscussionResolution {

        private static final String DISCUSSION_GID = "gid://gitlab/Discussion/" + "d".repeat(40);
        private static final long THREAD_NATIVE_ID =
                GitLabPullRequestReviewThreadProcessor.deterministicNativeId(DISCUSSION_GID);

        /** A thread stored by a diff note webhook before webhooks carried the discussion's GID. */
        @BeforeEach
        void storeTheMergeRequestAndTheThreadItsDiffNoteWebhookLeft() throws Exception {
            receive(loadPayload("merge_request.update"));
            transactionTemplate.executeWithoutResult(status -> threadProcessor.findOrCreateWebhookThread(
                    new GitLabPullRequestReviewThreadProcessor.WebhookThreadData(
                            THREAD_NATIVE_ID, "src/auth.ts", 12, Instant.now(), Instant.now(), null),
                    mr2(),
                    savedProvider));
            eventListener.clear();
        }

        @Test
        void shouldRecordTheResolutionAnUpdateWithoutChangesLeftUnsaid() throws Exception {
            handlerReadingDiscussions(() -> discussions(true)).dispatchEvent(unchangedUpdate(), Instant.now());

            PullRequestReviewThread thread = thread();
            assertThat(thread.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(thread.getNodeId()).isEqualTo(DISCUSSION_GID);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewThreadResolved.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .extracting(ScmDomainEvent.PullRequestUpdated::changedFields)
                    .allSatisfy(changed -> assertThat(changed).doesNotContain("title", "body"));
        }

        @Test
        void shouldKeepTheResolutionWhenGitLabCouldNotBeRead() throws Exception {
            handlerReadingDiscussions(
                            () -> GraphQlResponses.of(null, List.of(GraphQlResponses.error("Internal server error"))))
                    .dispatchEvent(unchangedUpdate(), Instant.now());

            PullRequestReviewThread thread = thread();
            assertThat(thread.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(thread.getNodeId()).isNull();
        }

        @Test
        void shouldRecordNoResolutionOnceTheProjectLeftTheWorkspaceDuringTheRead() throws Exception {
            handlerReadingDiscussions(() -> {
                        transactionTemplate.executeWithoutResult(status -> repositoryRepository
                                .findById(savedRepo.getId())
                                .orElseThrow()
                                .setOrganization(null));
                        return discussions(true);
                    })
                    .dispatchEvent(unchangedUpdate(), Instant.now());

            PullRequestReviewThread thread = thread();
            assertThat(thread.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(thread.getNodeId()).isNull();
        }

        /** MR !2's update hook at a later time with no {@code changes}, as GitLab sends it for a resolution. */
        private GitLabMergeRequestEventDTO unchangedUpdate() throws IOException {
            ObjectNode payload =
                    (ObjectNode) objectMapper.readTree(new ClassPathResource("gitlab/merge_request.update.json")
                            .getContentAsString(StandardCharsets.UTF_8));
            ((ObjectNode) payload.get("object_attributes")).put("updated_at", "2026-01-31 19:20:00 +0100");
            payload.putObject("changes");
            return objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class);
        }

        /** One page holding MR !2's diff discussion, resolved as {@code resolved} says. */
        private ClientGraphQlResponse discussions(boolean resolved) {
            Map<String, @Nullable Object> position = new HashMap<>();
            position.put("filePath", "src/auth.ts");
            position.put("newPath", "src/auth.ts");
            position.put("oldPath", "src/auth.ts");
            position.put("newLine", 12);
            position.put("oldLine", null);
            position.put("positionType", "text");
            Map<String, @Nullable Object> note = new HashMap<>();
            note.put("id", "gid://gitlab/DiffNote/" + 4_406_190L);
            note.put("body", "Please handle the error here.");
            note.put("system", false);
            note.put("internal", false);
            note.put("position", position);
            note.put("author", userNode(NATIVE_TUTOR_ID, "tutor"));
            note.put("createdAt", "2026-01-31T18:05:00Z");
            note.put("updatedAt", "2026-01-31T18:05:00Z");
            Map<String, @Nullable Object> notePage = new HashMap<>();
            notePage.put("hasNextPage", false);
            Map<String, @Nullable Object> discussion = new HashMap<>();
            discussion.put("id", DISCUSSION_GID);
            discussion.put("resolved", resolved);
            discussion.put("resolvedAt", resolved ? "2026-01-31T18:19:00Z" : null);
            discussion.put("resolvedBy", resolved ? userNode(NATIVE_AUTHOR_ID, FIXTURE_AUTHOR_LOGIN) : null);
            discussion.put("notes", Map.of("pageInfo", notePage, "nodes", List.of(note)));
            List<Map<String, @Nullable Object>> nodes = List.of(discussion);
            assertVendorCouldReturn(
                    GITLAB, "GetMergeRequestDiscussions", "project.mergeRequest.discussions.nodes", nodes);
            Map<String, @Nullable Object> pageInfo = new HashMap<>();
            pageInfo.put("hasNextPage", false);
            pageInfo.put("endCursor", null);
            return GraphQlResponses.of(
                    Map.of(
                            "project",
                            Map.of(
                                    "mergeRequest",
                                    Map.of("discussions", Map.of("pageInfo", pageInfo, "nodes", nodes)))),
                    List.of());
        }

        /** The merge request handler, reading the discussions GitLab answers with {@code answer} and nothing else. */
        private GitLabMergeRequestMessageHandler handlerReadingDiscussions(Supplier<ClientGraphQlResponse> answer) {
            GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);
            when(clients.forScope(any())).thenReturn(ScriptedGraphQlClient.of(request -> Mono.fromSupplier(answer)));
            return new GitLabMergeRequestMessageHandler(
                    mergeRequestProcessor,
                    webhookContextResolver,
                    mock(GitLabClosingIssueClient.class),
                    mock(GitLabMergeRequestReadinessReader.class),
                    new GitLabDiscussionSyncService(
                            clients,
                            graphQlResponseHandler,
                            threadProcessor,
                            reviewCommentProcessor,
                            issueCommentProcessor,
                            reviewReconciler,
                            gitLabProperties),
                    natsMessageDeserializer,
                    transactionTemplate);
        }

        private PullRequestReviewThread thread() {
            return threadRepository
                    .findByNativeIdAndProviderId(THREAD_NATIVE_ID, persistedId(savedProvider))
                    .orElseThrow();
        }

        private PullRequest mr2() {
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow();
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabMergeRequestEventDTO event = loadPayload("merge_request.open");

            assertThatCode(() -> receive(event)).doesNotThrowAnyException();
            assertThat(pullRequestRepository.count()).isZero();
        }

        @Test
        void idempotency_processSameEventTwice() throws Exception {
            GitLabMergeRequestEventDTO event = loadPayload("merge_request.open");

            receive(event);
            long countAfterFirst = pullRequestRepository.count();

            receive(event);

            assertThat(pullRequestRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void fullLifecycle_openCloseReopen() throws Exception {
            // Open MR !3
            receive(loadPayload("merge_request.open"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElseThrow();
                assertThat(pr.getState()).isEqualTo(Issue.State.OPEN);
            });

            // Close MR !3
            receive(loadPayload("merge_request.close"));
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
            receive(loadPayload("merge_request.reopen"));
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
            receive(loadPayload("merge_request.approved"));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);

            transactionTemplate.executeWithoutResult(status -> {
                long nativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR4_ID, NATIVE_APPROVER_ID);
                assertThat(reviewRepository.findByNativeIdAndProviderId(nativeId, persistedId(savedProvider)))
                        .isPresent();
            });

            // Unapprove MR !4 — should dismiss the review (not delete, not CHANGES_REQUESTED)
            receive(loadPayload("merge_request.unapproved"));
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
            receive(loadPayload("merge_request.update"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);

            // Merge MR !2
            deliver(loadPayload("merge_request.merge"));
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
            receive(loadPayload("merge_request.open"));

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
            receive(loadPayload("merge_request.open"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);

            // Close -> PullRequestClosed(wasMerged=false)
            receive(loadPayload("merge_request.close"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isFalse();

            eventListener.clear();

            // Reopen -> PullRequestReopened
            receive(loadPayload("merge_request.reopen"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReopened.class))
                    .hasSize(1);
        }

        @Test
        void domainEvents_mr2Merge() throws Exception {
            // Create via update
            receive(loadPayload("merge_request.update"));
            eventListener.clear();

            // Merge -> PullRequestClosed(wasMerged=true), then PullRequestMerged once the read after it ran
            deliver(loadPayload("merge_request.merge"));
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
            receive(loadPayload("merge_request.approved"));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);

            eventListener.clear();

            // Unapprove -> ReviewDismissed (not CHANGES_REQUESTED — unapproval is a distinct action)
            receive(loadPayload("merge_request.unapproved"));
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

            receive(loadPayload("merge_request.open"));

            transactionTemplate.executeWithoutResult(status -> {
                var author = userRepository
                        .findByNativeIdAndProviderId(NATIVE_AUTHOR_ID, persistedId(savedProvider))
                        .orElseThrow();
                assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(author.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);
            });
        }
    }

    /**
     * A merge's review judges who merged only once Hephaestus knows who that was. MR !2's recorded merge hook names a
     * merge commit but no merger and no merge time, and its user is the author, which says nothing about who merged.
     * The listener and resubmitter are constructed here, as in {@link TombstonedWork}.
     */
    @Nested
    class MergeAdmission {

        private static final String MERGE_SHA = "b186370e62e2ae348df64fb187c13aff4008457b";
        private static final Instant MERGE_VERSION = Instant.parse("2026-01-31T18:04:07Z");
        private static final Instant MERGED_AT = Instant.parse("2026-01-31T18:04:06Z");

        private final AgentJobService jobs = mock(AgentJobService.class);
        private final ReviewGate gate = mock(ReviewGate.class);

        /** Settles the occasion as the real submission does, so a redelivery meets a decided signal. */
        @BeforeEach
        void submissionSettlesTheOccasion() {
            when(jobs.submit(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                signalRecorder.markTriggered(invocation.getArgument(3), UUID.randomUUID());
                return Optional.empty();
            });
        }

        @Test
        void shouldKeepTheMergeCommitTheHookNamesWithoutTakingAnyoneForTheMerger() throws Exception {
            receive(loadPayload("merge_request.update"));
            receive(loadPayload("merge_request.merge"));

            PullRequest merged = mr2();
            assertThat(merged.getState()).isEqualTo(Issue.State.MERGED);
            assertThat(merged.getMergeCommitSha()).isEqualTo(MERGE_SHA);
            assertThat(merged.getMergedBy()).isNull();
        }

        @Test
        void shouldRecordTheMergerAndTimeGitLabNamesAfterTheHook() throws Exception {
            merge();

            assertThat(mergeRead(MR2_HEAD, MERGE_VERSION, author())).isTrue();

            PullRequest merged = mr2();
            assertThat(Objects.requireNonNull(merged.getMergedBy()).getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(merged.getMergedAt()).isEqualTo(MERGED_AT);
            assertThat(merged.getMergeCommitSha()).isEqualTo(MERGE_SHA);
        }

        @Test
        void shouldRecordNoMergeFactsReadOfAnotherMergeAndReplaceNoneRecorded() throws Exception {
            merge();

            assertThat(mergeRead("f".repeat(40), MERGE_VERSION, author())).isFalse();
            assertThat(terminalRead(NATIVE_MR2_ID + 1, "merged", MERGE_SHA, author()))
                    .isFalse();
            assertThat(terminalRead(NATIVE_MR2_ID, "opened", MERGE_SHA, author()))
                    .isFalse();
            assertThat(terminalRead(NATIVE_MR2_ID, "merged", "e".repeat(40), author()))
                    .isFalse();
            assertThat(mr2().getMergedBy()).isNull();

            // The facts of a completed merge do not change, so a report of it dated an older version still names its
            // merger.
            assertThat(mergeRead(MR2_HEAD, MERGE_VERSION.minusSeconds(60), author()))
                    .isTrue();
            assertThat(mergeRead(MR2_HEAD, MERGE_VERSION, syncedUser(NATIVE_TUTOR_ID, "tutor")))
                    .isFalse();
            assertThat(Objects.requireNonNull(mr2().getMergedBy()).getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
        }

        @Test
        void shouldHoldTheMergeReviewUntilTheMergerIsKnownThenReviewTheSelfMerge() throws Exception {
            ScmDomainEvent.PullRequestMerged event = merge();
            detect(mergerPractice());

            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(event));

            ArtifactSignal held = mergeSignal();
            assertThat(held.getState()).isEqualTo(SignalState.PENDING);
            assertThat(held.getStateReason()).isEqualTo(SignalStateReason.MERGE_ACTOR_UNAVAILABLE);
            transactionTemplate.executeWithoutResult(status -> resubmitter().resubmit(held));
            assertThat(mergeSignal().getStateReason()).isEqualTo(SignalStateReason.MERGE_ACTOR_UNAVAILABLE);
            verifyNoInteractions(jobs);

            mergeRead(MR2_HEAD, MERGE_VERSION, author());
            transactionTemplate.executeWithoutResult(status -> resubmitter().resubmit(held));

            ScmEventPayload.PullRequestData submitted = submitted();
            assertThat(submitted.mergedById()).isNotNull().isEqualTo(submitted.authorId());
        }

        @Test
        void shouldNameAnotherMergerRatherThanTheAuthor() throws Exception {
            ScmDomainEvent.PullRequestMerged event = merge();
            mergeRead(MR2_HEAD, MERGE_VERSION, syncedUser(NATIVE_TUTOR_ID, "tutor"));
            detect(mergerPractice());

            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(event));

            ScmEventPayload.PullRequestData submitted = submitted();
            assertThat(submitted.mergedById())
                    .isEqualTo(userRepository
                            .findByNativeIdAndProviderId(NATIVE_TUTOR_ID, persistedId(savedProvider))
                            .orElseThrow()
                            .getId());
            assertThat(submitted.mergedById()).isNotEqualTo(submitted.authorId());
        }

        @Test
        void shouldReviewAMergeRightAwayWhenNoPracticeJudgesTheMerger() throws Exception {
            ScmDomainEvent.PullRequestMerged event = merge();
            detect(new Practice());

            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(event));

            assertThat(submitted().mergedById()).isNull();
        }

        @Test
        void shouldLetTheLiveMergeClaimTheMergeASyncRecordedFirstAndReviewItOnce() throws Exception {
            receive(loadPayload("merge_request.update"));
            syncPage(mergedPage(), List.of());
            ScmDomainEvent.PullRequestMerged synced =
                    eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getLast();
            assertThat(synced.context().isSync()).isTrue();
            detect(mergerPractice());

            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(synced));
            assertThat(mergeSignal().getState()).isEqualTo(SignalState.RECORDED);
            verifyNoInteractions(jobs);

            eventListener.clear();
            // Older than the page the sync stored, so it changes nothing; the merge request is merged all the same.
            deliver(loadPayload("merge_request.merge"));
            ScmDomainEvent.PullRequestMerged live =
                    eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getFirst();
            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(live));
            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(live));

            // Once: the redelivery finds the occasion taken.
            assertThat(submitted().mergedById())
                    .isNotNull()
                    .isEqualTo(submitted().authorId());
        }

        @Test
        void shouldOfferTheMergeOnlyAfterItsReadAndWithTheMergerTheReadNamed() throws Exception {
            receive(loadPayload("merge_request.update"));
            eventListener.clear();
            AtomicBoolean offeredDuringRead = new AtomicBoolean(true);
            AtomicReference<Issue.@Nullable State> storedDuringRead = new AtomicReference<>();
            GitLabMergeRequestReadinessReader reader = mock(GitLabMergeRequestReadinessReader.class);
            when(reader.read(any(), anyString(), eq(MR2_IID))).thenAnswer(invocation -> {
                offeredDuringRead.set(!eventListener
                        .ofType(ScmDomainEvent.PullRequestMerged.class)
                        .isEmpty());
                storedDuringRead.set(mr2().getState());
                return mergedFacts(author());
            });

            handlerReading(reader).dispatchEvent(loadPayload("merge_request.merge"), Instant.now());

            assertThat(storedDuringRead.get()).isEqualTo(Issue.State.MERGED);
            assertThat(offeredDuringRead.get()).isFalse();
            ScmDomainEvent.PullRequestMerged offered =
                    eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getFirst();
            assertThat(offered.context().isSync()).isFalse();
            detect(mergerPractice());
            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(offered));
            ScmEventPayload.PullRequestData submitted = submitted();
            assertThat(submitted.mergedById()).isNotNull().isEqualTo(submitted.authorId());
            assertThat(submitted.mergedAt()).isEqualTo(MERGED_AT);
            assertThat(mr2().getMergeCommitSha()).isEqualTo(MERGE_SHA);
        }

        @Test
        void shouldHoldTheMergeTheReadCouldNotNameAMergerForUntilASyncDoesThenReviewItOnce() throws Exception {
            receive(loadPayload("merge_request.update"));
            eventListener.clear();
            GitLabMergeRequestReadinessReader reader = mock(GitLabMergeRequestReadinessReader.class);

            handlerReading(reader).dispatchEvent(loadPayload("merge_request.merge"), Instant.now());

            ScmDomainEvent.PullRequestMerged offered =
                    eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getFirst();
            detect(mergerPractice());
            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(offered));
            ArtifactSignal held = mergeSignal();
            assertThat(mr2().getState()).isEqualTo(Issue.State.MERGED);
            assertThat(held.getState()).isEqualTo(SignalState.PENDING);
            assertThat(held.getStateReason()).isEqualTo(SignalStateReason.MERGE_ACTOR_UNAVAILABLE);
            assertThat(held.getDiscoveredVia()).isEqualTo(DiscoveredVia.EVENT);
            verifyNoInteractions(jobs);

            syncPage(mergedPage(), List.of());
            transactionTemplate.executeWithoutResult(status -> resubmitter().resubmit(held));

            ScmEventPayload.PullRequestData submitted = submitted();
            assertThat(submitted.mergedById()).isNotNull().isEqualTo(submitted.authorId());
            // Settled, so the reaper, which re-offers only pending occasions, does not offer it again.
            assertThat(mergeSignal().getState()).isEqualTo(SignalState.TRIGGERED);
            assertThat(mergeSignal().getDiscoveredVia()).isEqualTo(DiscoveredVia.EVENT);
        }

        @Test
        void shouldRecordTheMergerAReadNamesWithinTheSecondOfAMillisecondMergeHook() throws Exception {
            receive(loadPayload("merge_request.update"));
            eventListener.clear();
            GitLabMergeRequestReadinessReader reader = mock(GitLabMergeRequestReadinessReader.class);
            // GraphQL reports the merged version to the second; the hook stored it to the millisecond.
            when(reader.read(any(), anyString(), eq(MR2_IID))).thenReturn(mergedFacts(author()));

            handlerReading(reader).dispatchEvent(millisecondMerge(), Instant.now());

            PullRequest merged = mr2();
            assertThat(merged.getUpdatedAt()).isEqualTo(Instant.parse("2026-01-31T18:04:07.317Z"));
            assertThat(Objects.requireNonNull(merged.getMergedBy()).getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            ScmDomainEvent.PullRequestMerged offered =
                    eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getFirst();
            detect(mergerPractice());
            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(offered));
            ScmEventPayload.PullRequestData submitted = submitted();
            assertThat(submitted.mergedById()).isNotNull().isEqualTo(submitted.authorId());
        }

        @Test
        void shouldRecoverOnlyTheStandingNativeDateAfterAMillisecondMergeHook() throws Exception {
            receive(loadPayload("merge_request.update"));
            transactionTemplate.executeWithoutResult(tx -> {
                PullRequest mr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                        .orElseThrow();
                var author = Objects.requireNonNull(mr.getAuthor());
                var approval = new PullRequestReview();
                approval.setNativeId(
                        GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR2_ID, author.getNativeId()));
                approval.setProvider(savedRepo.getProvider());
                approval.setAuthor(author);
                approval.setPullRequest(mr);
                approval.setState(PullRequestReview.State.APPROVED);
                approval.setCommitId(MR2_HEAD);
                reviewRepository.save(approval);
                mr.addReview(approval);
            });
            eventListener.clear();
            var hook = millisecondMerge();
            var attrs = Objects.requireNonNull(hook.objectAttributes());
            var actualDate = Instant.parse("2026-01-31T18:03:19.087Z");
            long nativeApprover = GitLabSyncConstants.extractNumericId(Objects.requireNonNull(author().globalId()));
            var rows = new GitLabApprovalClient.Snapshot(
                    NATIVE_MR2_ID,
                    MR2_IID,
                    savedRepo.getNativeId(),
                    "merged",
                    Instant.parse("2026-01-31T18:04:07.317Z"),
                    attrs.title(),
                    attrs.description(),
                    List.of(new GitLabApprovalClient.Approval(
                            new GitLabApprovalClient.Approver(nativeApprover), actualDate)));
            var terminal = mergedFacts(author());
            var response = new GitLabMergeRequestReadinessReader.Facts(
                    terminal.projectNativeId(),
                    terminal.mergeRequestNativeId(),
                    terminal.state(),
                    terminal.updatedAt(),
                    terminal.headSha(),
                    true,
                    "mergeable",
                    true,
                    GitLabHeadPipeline.NO_PIPELINE,
                    List.of(),
                    List.of(author()),
                    terminal.merge(),
                    rows);
            var reader = mock(GitLabMergeRequestReadinessReader.class);
            when(reader.read(any(), anyString(), eq(MR2_IID))).thenReturn(response);

            handlerReading(reader).dispatchEvent(hook, Instant.now());

            var merged = mr2();
            var approval = reviewRepository
                    .findByNativeIdAndProviderId(
                            GitLabMergeRequestProcessor.generateApprovalNativeId(NATIVE_MR2_ID, nativeApprover),
                            Objects.requireNonNull(savedRepo.getProvider().getId()))
                    .orElseThrow();
            assertThat(approval.getSubmittedAt()).isEqualTo(actualDate);
            assertThat(approval.getCommitId()).isEqualTo(MR2_HEAD);
            assertThat(merged.getHeadCheckState()).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @Test
        void shouldLetASyncNameTheMergerOfAMillisecondMergeHookWithoutTakingAnythingElseFromTheOlderPage()
                throws Exception {
            receive(loadPayload("merge_request.update"));
            eventListener.clear();
            // The read after the hook failed.
            handlerReading(mock(GitLabMergeRequestReadinessReader.class))
                    .dispatchEvent(millisecondMerge(), Instant.now());
            ScmDomainEvent.PullRequestMerged offered =
                    eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getFirst();
            detect(mergerPractice());
            transactionTemplate.executeWithoutResult(status -> listener().onPullRequestMerged(offered));
            ArtifactSignal held = mergeSignal();
            assertThat(held.getStateReason()).isEqualTo(SignalStateReason.MERGE_ACTOR_UNAVAILABLE);

            // Dated to the second, each page is older than the hook's version.
            Map<String, @Nullable Object> open = mergedPage();
            open.put("updatedAt", "2026-01-31T18:04:07Z");
            open.put("state", "opened");
            syncPage(open, List.of());
            Map<String, @Nullable Object> otherHead = mergedPage();
            otherHead.put("updatedAt", "2026-01-31T18:04:07Z");
            otherHead.put("diffHeadSha", "f".repeat(40));
            syncPage(otherHead, List.of());
            assertThat(mr2().getState()).isEqualTo(Issue.State.MERGED);
            assertThat(mr2().getMergedBy()).isNull();

            Map<String, @Nullable Object> merged = mergedPage();
            merged.put("updatedAt", "2026-01-31T18:04:07Z");
            merged.put("title", "A title from the older page");
            syncPage(merged, List.of());
            PullRequest repaired = mr2();
            assertThat(Objects.requireNonNull(repaired.getMergedBy()).getLogin())
                    .isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(repaired.getTitle()).isNotEqualTo("A title from the older page");
            assertThat(repaired.getUpdatedAt()).isEqualTo(Instant.parse("2026-01-31T18:04:07.317Z"));

            transactionTemplate.executeWithoutResult(status -> resubmitter().resubmit(held));
            ScmEventPayload.PullRequestData submitted = submitted();
            assertThat(submitted.mergedById()).isNotNull().isEqualTo(submitted.authorId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"Older description", "Newer description"})
        void shouldTakeNothingFromAPageOfTheSameSecondAsANewerMillisecondEdit(String pageDescription) throws Exception {
            receive(update("2026-01-31T19:04:04.900+01:00", "Newer description"));
            eventListener.clear();
            Map<String, @Nullable Object> page = mergedPage();
            page.put("state", "opened");
            page.put("updatedAt", "2026-01-31T18:04:04Z");
            page.put("description", pageDescription);
            page.put("mergedAt", null);
            page.put("mergeCommitSha", null);
            page.put("mergeUser", null);
            page.put("detailedMergeStatus", "mergeable");

            syncPage(page, List.of());

            PullRequest stored = mr2();
            assertThat(stored.getBody()).isEqualTo("Newer description");
            assertThat(stored.getUpdatedAt()).isEqualTo(Instant.parse("2026-01-31T18:04:04.900Z"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .isEmpty();
        }

        /** The merge request handler, reading GitLab through {@code reader}. */
        private GitLabMergeRequestMessageHandler handlerReading(GitLabMergeRequestReadinessReader reader) {
            return new GitLabMergeRequestMessageHandler(
                    mergeRequestProcessor,
                    webhookContextResolver,
                    mock(GitLabClosingIssueClient.class),
                    reader,
                    mock(GitLabDiscussionSyncService.class),
                    natsMessageDeserializer,
                    transactionTemplate);
        }

        private GitLabMergeRequestReadinessReader.Facts mergedFacts(GitLabMergeRequestProcessor.SyncUserData merger) {
            return mergedFacts(merger, MERGE_VERSION);
        }

        private GitLabMergeRequestReadinessReader.Facts mergedFacts(
                GitLabMergeRequestProcessor.SyncUserData merger, Instant version) {
            return new GitLabMergeRequestReadinessReader.Facts(
                    savedRepo.getNativeId(),
                    NATIVE_MR2_ID,
                    "merged",
                    version,
                    MR2_HEAD,
                    false,
                    "not_open",
                    null,
                    GitLabHeadPipeline.NOT_CAPTURED,
                    null,
                    null,
                    new GitLabMergeRequestReadinessReader.Merge(merger, MERGED_AT, MERGE_SHA),
                    null);
        }

        /** MR !2's merge hook as GitLab 19 sends it: its times to the millisecond, and no merger. */
        private GitLabMergeRequestEventDTO millisecondMerge() throws IOException {
            return edited("merge_request.merge", attributes -> {
                attributes.put("updated_at", "2026-01-31T19:04:07.317+01:00");
                attributes.put("merged_at", "2026-01-31T19:04:07.329+01:00");
                attributes.putNull("merge_user_id");
            });
        }

        private ScmDomainEvent.PullRequestMerged merge() throws Exception {
            receive(loadPayload("merge_request.update"));
            eventListener.clear();
            deliver(loadPayload("merge_request.merge"));
            return eventListener.ofType(ScmDomainEvent.PullRequestMerged.class).getFirst();
        }

        /** A read of MR !2 at its head reporting {@code nativeId} in {@code state}, merged into {@code commit}. */
        private boolean terminalRead(
                long nativeId, String state, String commit, GitLabMergeRequestProcessor.SyncUserData merger) {
            return mergeRequestProcessor.applyTerminalFacts(
                    savedRepo,
                    MR2_IID,
                    new GitLabMergeRequestReadinessReader.Facts(
                            savedRepo.getNativeId(),
                            nativeId,
                            state,
                            MERGE_VERSION,
                            MR2_HEAD,
                            false,
                            "not_open",
                            null,
                            GitLabHeadPipeline.NOT_CAPTURED,
                            null,
                            null,
                            new GitLabMergeRequestReadinessReader.Merge(merger, MERGED_AT, commit),
                            null));
        }

        private boolean mergeRead(
                String head, Instant version, GitLabMergeRequestProcessor.@Nullable SyncUserData merger) {
            return mergeRequestProcessor.applyTerminalFacts(
                    savedRepo,
                    MR2_IID,
                    new GitLabMergeRequestReadinessReader.Facts(
                            savedRepo.getNativeId(),
                            NATIVE_MR2_ID,
                            "merged",
                            version,
                            head,
                            false,
                            "not_open",
                            null,
                            GitLabHeadPipeline.NOT_CAPTURED,
                            null,
                            null,
                            new GitLabMergeRequestReadinessReader.Merge(merger, MERGED_AT, MERGE_SHA),
                            null));
        }

        /** MR !2 merged by its author, as a sync reads it after the merge. */
        private Map<String, @Nullable Object> mergedPage() {
            Map<String, @Nullable Object> node = new HashMap<>();
            node.put("id", "gid://gitlab/MergeRequest/" + NATIVE_MR2_ID);
            node.put("iid", String.valueOf(MR2_IID));
            node.put("title", MR2_TITLE);
            node.put("state", "merged");
            node.put("createdAt", "2026-01-31T18:00:00Z");
            node.put("updatedAt", "2026-01-31T18:30:00Z");
            node.put("mergedAt", MERGED_AT.toString());
            node.put("sourceBranch", "feature/oauth");
            node.put("targetBranch", "main");
            node.put("diffHeadSha", MR2_HEAD);
            node.put("mergeCommitSha", MERGE_SHA);
            node.put("mergeable", false);
            node.put("detailedMergeStatus", "not_open");
            node.put("approved", true);
            node.put("headPipeline", null);
            node.put("reviewers", connection(List.of()));
            node.put("approvedBy", connection(List.of()));
            node.put("author", userNode(NATIVE_AUTHOR_ID, FIXTURE_AUTHOR_LOGIN));
            node.put("mergeUser", userNode(NATIVE_AUTHOR_ID, FIXTURE_AUTHOR_LOGIN));
            return node;
        }

        private GitLabMergeRequestProcessor.SyncUserData author() {
            return syncedUser(NATIVE_AUTHOR_ID, FIXTURE_AUTHOR_LOGIN);
        }

        /** A practice judging the merger's conduct when the merge request is merged. */
        private Practice mergerPractice() {
            Practice practice = new Practice();
            practice.setSignals(List.of(ScmSignals.PULL_REQUEST_MERGED));
            practice.setEvidenceRequirements(PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST));
            practice.setReviewWhen(Map.of());
            practice.setSubject(ActorRole.MERGER);
            practice.setPrecondition(null);
            return practice;
        }

        private void detect(Practice practice) {
            var decision = new GateDecision.Detect(savedWorkspace, List.of(practice), 1, TriggerMode.AUTO);
            when(gate.evaluate(any(), eq(ScmSignals.PULL_REQUEST_MERGED), eq(TriggerMode.AUTO)))
                    .thenReturn(decision);
            when(gate.evaluateQueued(
                            any(), eq(savedWorkspace.getId()), eq(ScmSignals.PULL_REQUEST_MERGED), any(), anyBoolean()))
                    .thenReturn(decision);
        }

        private AgentJobEventListener listener() {
            return new AgentJobEventListener(
                    jobs, pullRequestRepository, gate, workspaceResolver, signalRecorder, manifests);
        }

        private PullRequestSignalResubmitter resubmitter() {
            return new PullRequestSignalResubmitter(
                    jobs, pullRequestRepository, gate, signalRecorder, reviewRepository, manifests);
        }

        private ArtifactSignal mergeSignal() {
            return artifactSignalRepository
                    .findForArtifact(savedWorkspace.getId(), ScmSignals.PULL_REQUEST.value(), mr2().getId())
                    .stream()
                    .filter(signal -> signal.key().signalName().equals(ScmSignals.PULL_REQUEST_MERGED))
                    .findFirst()
                    .orElseThrow();
        }

        /** The one review submitted, as the job carries the merge request. */
        private ScmEventPayload.PullRequestData submitted() {
            var request = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
            verify(jobs)
                    .submit(
                            eq(savedWorkspace.getId()),
                            eq(AgentJobType.PULL_REQUEST_REVIEW),
                            request.capture(),
                            any(),
                            any());
            return request.getValue().pullRequest();
        }

        private PullRequest mr2() {
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), MR2_IID)
                    .orElseThrow();
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
            receive(loadPayload("merge_request.open"));
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
            var gate = mock(ReviewGate.class);
            var listener = new AgentJobEventListener(
                    jobs, pullRequestRepository, gate, workspaceResolver, signalRecorder, manifests);
            transactionTemplate.executeWithoutResult(status -> listener.onPullRequestCreated(created));

            ArtifactSignal held = artifactSignalRepository
                    .findForArtifact(savedWorkspace.getId(), ScmSignals.PULL_REQUEST.value(), pullRequestId)
                    .getFirst();
            assertThat(held.getState()).isEqualTo(SignalState.PENDING);
            assertThat(held.getStateReason()).isEqualTo(SignalStateReason.ARTIFACT_NOT_VISIBLE);
            verifyNoInteractions(jobs, gate);

            receive(loadPayload("merge_request.reopen"));
            assertThat(pullRequestRepository
                            .findById(pullRequestId)
                            .orElseThrow()
                            .getDeletedAt())
                    .isNull();

            var decision = new GateDecision.Detect(savedWorkspace, List.of(), 1, TriggerMode.AUTO);
            when(gate.evaluateQueued(
                            any(), eq(savedWorkspace.getId()), eq(ScmSignals.PULL_REQUEST_OPENED), any(), eq(false)))
                    .thenReturn(decision);
            transactionTemplate.executeWithoutResult(status -> new PullRequestSignalResubmitter(
                            jobs, pullRequestRepository, gate, signalRecorder, reviewRepository, manifests)
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

    private GitLabMergeRequestProcessor.SyncReviewerData syncedReviewer(
            long id, String username, @Nullable String state) {
        return new GitLabMergeRequestProcessor.SyncReviewerData(syncedUser(id, username), state);
    }

    private GitLabMergeRequestProcessor.SyncUserData syncedUser(long id, String username) {
        return new GitLabMergeRequestProcessor.SyncUserData(
                "gid://gitlab/User/" + id, username, username, null, null, null, null);
    }

    /** MR !2 as a sync reads it, with only what these tests need; a list the sync did not read whole is null. */
    private GitLabMergeRequestProcessor.SyncMergeRequestData syncedMergeRequest(
            boolean approved,
            @Nullable String detailedMergeStatus,
            @Nullable List<GitLabMergeRequestProcessor.SyncReviewerData> reviewers,
            @Nullable List<GitLabMergeRequestProcessor.SyncUserData> approvers) {
        return syncedMergeRequest(
                approved, detailedMergeStatus, reviewers, approvers, MR2_HEAD, GitLabHeadPipeline.NOT_CAPTURED);
    }

    /** MR !2 as a sync reads it at {@code head}, with its head pipeline as read. */
    private GitLabMergeRequestProcessor.SyncMergeRequestData syncedMergeRequest(
            boolean approved,
            @Nullable String detailedMergeStatus,
            @Nullable List<GitLabMergeRequestProcessor.SyncReviewerData> reviewers,
            @Nullable List<GitLabMergeRequestProcessor.SyncUserData> approvers,
            @Nullable String head,
            GitLabHeadPipeline headPipeline) {
        return new GitLabMergeRequestProcessor.SyncMergeRequestData(
                "gid://gitlab/MergeRequest/" + NATIVE_MR2_ID,
                String.valueOf(MR2_IID),
                MR2_TITLE,
                null, // description
                "opened",
                false, // draft
                null, // mergeable
                detailedMergeStatus,
                approved,
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
                head, // diffHeadSha
                null, // baseSha
                null, // mergeCommitSha
                false, // discussionLocked
                0, // commentsCount
                "gid://gitlab/User/" + NATIVE_AUTHOR_ID,
                FIXTURE_AUTHOR_LOGIN,
                FIXTURE_AUTHOR_LOGIN,
                null, // authorAvatarUrl
                null, // authorWebUrl
                null,
                null, // authorPublicEmail
                null, // mergeUserGlobalId
                null, // mergeUserUsername
                null, // mergeUserName
                null, // mergeUserAvatarUrl
                null, // mergeUserWebUrl
                null,
                null, // mergeUserPublicEmail
                null, // syncLabels
                null, // syncAssignees
                reviewers,
                approvers,
                null, // syncParticipants
                null, // milestoneIid
                headPipeline,
                null,
                null); // closingIssueNumbers
    }

    /** Delivers {@code event} as the stream does: stored, then read from GitLab, then offered for review. */
    private void deliver(GitLabMergeRequestEventDTO event) {
        handler.dispatchEvent(event, Instant.now());
    }

    /** Handles {@code event} as a delivery that reached the stream now. */
    private void receive(GitLabMergeRequestEventDTO event) {
        handler.handle(event, Instant.now());
    }

    /** MR !2's update hook at {@code updatedAt}, carrying {@code description} where one is given. */
    private GitLabMergeRequestEventDTO update(String updatedAt, @Nullable String description) throws IOException {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(
                new ClassPathResource("gitlab/merge_request.update.json").getContentAsString(StandardCharsets.UTF_8));
        ObjectNode attributes = (ObjectNode) payload.get("object_attributes");
        attributes.put("updated_at", updatedAt);
        if (description != null) {
            attributes.put("description", description);
        }
        return objectMapper.treeToValue(payload, GitLabMergeRequestEventDTO.class);
    }

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
