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
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
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
            receive(loadPayload("merge_request.merge"));

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
                String waitingStatement = statementWaitingOnALock();
                releaseSync.countDown();
                sync.get(30, TimeUnit.SECONDS);
                webhook.get(30, TimeUnit.SECONDS);

                assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(SYNCED);
                assertThat(reviewersObservedAt()).isEqualTo(syncedAt);
                assertThat(waitingStatement.toLowerCase(Locale.ROOT)).containsPattern("for (no key )?update");
            } finally {
                releaseSync.countDown();
                threads.shutdownNow();
            }
        }

        /** The statement of the one other backend waiting on a lock in this database, once there is one. */
        private String statementWaitingOnALock() throws InterruptedException {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(deadline)) {
                List<@Nullable String> waiting = jdbcTemplate.queryForList("""
                        SELECT query FROM pg_stat_activity
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
                    syncedMergeRequest(List.of(reviewers)),
                    ProcessingContext.forSync(null, savedRepo).withObservedAt(fetchedAt));
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
            receive(loadPayload("merge_request.merge"));
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

            // Merge -> PullRequestClosed(wasMerged=true) + PullRequestMerged
            receive(loadPayload("merge_request.merge"));
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

            receive(loadPayload("merge_request.reopen"));
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

    /** Handles {@code event} as a delivery that reached the stream now. */
    private void receive(GitLabMergeRequestEventDTO event) {
        handler.handle(event, Instant.now());
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
