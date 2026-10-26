package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabPullRequestReviewCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("integration")
@DisplayName("GitLab Merge Request Message Handler")
class GitLabMergeRequestMessageHandlerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private GitLabProjectAdmission projectAdmission;

    private static final long NATIVE_AUTHOR_ID = 90001L;
    private static final String FIXTURE_AUTHOR_LOGIN = "ab12cde";
    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    private static final long NATIVE_MR3_ID = 334053L;
    private static final int MR3_IID = 3;
    private static final String MR3_TITLE = "Test MR for close/reopen";
    private static final String MR3_HTML_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/merge_requests/3";
    private static final String MR3_SOURCE_BRANCH = "feature/test-close-reopen";
    private static final String MR3_TARGET_BRANCH = "main";

    private static final long NATIVE_MR2_ID = 334047L;
    private static final int MR2_IID = 2;
    private static final String MR2_TITLE = "Implement OAuth authentication";

    /** The head of MR !2 in its recorded update hook. */
    private static final String MR2_HEAD = "11499a581bf88090fa5e0abbf6d73e10e6fb56a7";

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
    private ConnectionRepository connectionRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryMonitors;

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

    @Autowired
    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    @Nested
    class EventType {

        @Test
        void returnsCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("merge_request");
        }
    }

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

                assertThat(pr.getNativeId()).isEqualTo(NATIVE_MR3_ID);
                assertThat(pr.getNumber()).isEqualTo(MR3_IID);
                assertThat(pr.getTitle()).isEqualTo(MR3_TITLE);
                assertThat(pr.getBody()).isNull();
                assertThat(pr.getState()).isEqualTo(Issue.State.OPEN);
                assertThat(pr.getHtmlUrl()).isEqualTo(MR3_HTML_URL);

                assertThat(pr.getHeadRefName()).isEqualTo(MR3_SOURCE_BRANCH);
                assertThat(pr.getBaseRefName()).isEqualTo(MR3_TARGET_BRANCH);

                assertThat(pr.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);

                assertThat(pr.getCreatedAt()).isNotNull();
                assertThat(pr.getUpdatedAt()).isNotNull();

                assertThat(pr.getRepository()).isNotNull();
                assertThat(pr.getRepository().getId()).isEqualTo(savedRepo.getId());

                assertThat(pr.getAuthor()).isNotNull();
                assertThat(pr.getAuthor().getNativeId()).isEqualTo(NATIVE_AUTHOR_ID);
                assertThat(pr.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);

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
            String repaired = "This MR implements OAuth2 so partners can sign in without a password.\n\n" + "Closes #5";

            receive(update("2026-01-31 19:10:00 +0100", repaired));
            receive(update("2026-01-31 19:12:00 +0100", repaired));

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .extracting(ScmDomainEvent.PullRequestUpdated::changedFields)
                    .containsExactly(Set.of("body"), Set.of());
        }

        @Test
        void closeMergeRequest_setsStateToClosed() throws Exception {
            receive(loadPayload("merge_request.open"));
            eventListener.clear();

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
            receive(loadPayload("merge_request.update"));
            eventListener.clear();

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
            receive(loadPayload("merge_request.open"));
            receive(loadPayload("merge_request.close"));
            eventListener.clear();

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

    @Nested
    class ApprovalEvents {

        @Test
        void shouldCreateApprovalOnlyFromTheSnapshotReadAfterTheHook() throws Exception {
            // The approval hook is the first MR !4 hook, so it also creates the merge request.
            receiveWithApprovals(loadPayload("merge_request.approved"), List.of(approver()));

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
        @DisplayName("dismisses the review when the native snapshot no longer lists the approver")
        void unapproveMergeRequest_dismissesReview() throws Exception {
            receiveWithApprovals(loadPayload("merge_request.approved"), List.of(approver()));
            eventListener.clear();

            receiveWithApprovals(loadPayload("merge_request.unapproved"), List.of());

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

        private static final Map<String, RequestedReviewer.ReviewState> REVIEWED = Map.of(
                "user1", RequestedReviewer.ReviewState.APPROVED,
                "sjones", RequestedReviewer.ReviewState.REQUESTED_CHANGES,
                "user2", RequestedReviewer.ReviewState.REVIEWED,
                "user3", RequestedReviewer.ReviewState.UNREVIEWED);

        private static final Map<String, RequestedReviewer.ReviewState> SYNCED =
                Map.of("user1", RequestedReviewer.ReviewState.UNREVIEWED);

        private static final Instant STORED_AT = Instant.parse("2026-01-31T18:20:00Z");

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

        // GitLab before 18.6 omits reviewer state from hooks; only sync can supply that state.
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

        // GitLab omits empty reviewers and assignees from hooks.
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

        @ParameterizedTest
        @EnumSource(Order.class)
        void shouldApplyASyncPageUnlessItWasReadBeforeTheStoredWebhook(Order page) throws Exception {
            handler.handle(loadPayload("merge_request.update.reviewers.derived"), STORED_AT);

            sync(page.observedAt(), syncedReviewer(6L, "user1", "UNREVIEWED"));
            sync(page.observedAt(), syncedReviewer(6L, "user1", "UNREVIEWED"));

            assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(page == Order.OLDER ? REVIEWED : SYNCED);
        }

        @ParameterizedTest
        @EnumSource(Order.class)
        void shouldApplyAWebhookUnlessItArrivedBeforeTheStoredSyncPageWasRead(Order arrival) throws Exception {
            sync(STORED_AT, syncedReviewer(6L, "user1", "UNREVIEWED"));
            GitLabMergeRequestEventDTO webhook = at("merge_request.update.reviewers.derived", SYNCED_UPDATED_AT);

            handler.handle(webhook, arrival.observedAt());
            handler.handle(webhook, arrival.observedAt());

            assertThat(reviewerStates()).containsExactlyInAnyOrderEntriesOf(arrival == Order.OLDER ? SYNCED : REVIEWED);
        }

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

        // PostgreSQL truncates statement text at track_activity_query_size; a long query can hide its locking clause.
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

    // GitLab approved means approval rules are satisfied; zero required approvals need no approving person.
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
                    .as("the approver's approval is theirs, and the request for changes is the"
                            + " requester's system note's")
                    .containsExactly(Map.entry("approver", PullRequestReview.State.APPROVED));
        }

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

        // Approval and unapproval hooks report individual actions, independently of approval-rule completion.
        @Test
        void shouldRefreshApprovalMembershipOnlyFromTheNativeSnapshot() throws Exception {
            receive(loadPayload("merge_request.approval"));
            assertThat(approvalState()).isNull();
            assertThat(reviewDecision(MR4_IID)).isNull();

            receiveWithApprovals(loadPayload("merge_request.approval"), List.of(approver()));
            assertThat(approvalState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision(MR4_IID)).isEqualTo(ReviewDecision.APPROVED);

            receive(loadPayload("merge_request.unapproval"));
            assertThat(approvalState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision(MR4_IID)).isNull();

            receiveWithApprovals(loadPayload("merge_request.unapproval"), List.of());
            assertThat(approvalState()).isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(reviewDecision(MR4_IID)).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
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
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"),
                    List.of(approver()));
            setReviewDecision(ReviewDecision.APPROVED);
            eventListener.clear();

            receive(systemReset(FIXTURE_HEAD, "2026-01-31 22:13:55 +0100", systemAction));

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.isDismissed()).isFalse();
            assertThat(approval.getCommitId())
                    .as("a hook names the merge request's current head, not the approved revision")
                    .isNull();
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .isEmpty();
        }

        @Test
        void shouldForgetTheDecisionButDismissNoOneWhenGitLabResetsApprovalsOnTheCurrentHead() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"),
                    List.of(approver()));
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
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"),
                    List.of(approver()));
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
        void shouldKeepTheLastApprovalWhenTheWithdrawalRefreshIsUnavailable() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"),
                    List.of(approver()));
            eventListener.clear();

            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .isEmpty();
        }

        @Test
        void shouldLeaveAStandingApprovalToTheReadWhenAnApprovalHookArrivesAfterAReset() throws Exception {
            receiveWithApprovals(loadPayload("merge_request.approved"), List.of(approver()));
            // GitLab's reset on the push says whose approvals went only by resetting them all, so
            // it dismisses no one.
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:30:00 +0100", "approvals_reset_on_push"));
            eventListener.clear();

            // GitLab fires the hook from the merge request as it is when the hook runs, so the head
            // it names is no
            // approved revision, and an approval that already stands is the readiness read's to
            // reconcile.
            GitLabMergeRequestEventDTO reapproval =
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:30:00 +0100");
            receive(reapproval);
            receive(reapproval);

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.getCommitId()).isNull();
            assertThat(approval.getSubmittedAt()).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldKeepTheStandingApprovalWhenAnApprovalAfterThePushNamesNoHead(boolean blank) throws Exception {
            receiveWithApprovals(loadPayload("merge_request.approved"), List.of(approver()));
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:30:00 +0100", "approvals_reset_on_push"));
            eventListener.clear();

            receive(headless("merge_request.approved", "2026-01-31 22:30:00 +0100", blank));

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.getCommitId())
                    .as("a hook names the merge request's current head, not the approved revision")
                    .isNull();
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
        void shouldRecordNoApprovalsFromTwoHooksReceivedAtTheSameInstant() throws Exception {
            Instant receivedAt = Instant.parse("2026-09-30T10:00:00.000001Z");

            handler.handle(loadPayload("merge_request.approved"), receivedAt);
            handler.handle(asTutor(loadPayload("merge_request.approved")), receivedAt);

            assertThat(approval(NATIVE_APPROVER_ID)).isNull();
            assertThat(approval(NATIVE_TUTOR_ID)).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }
    }

    @Nested
    class ReadinessRead {

        private Instant readAt = Instant.EPOCH;

        @BeforeEach
        void storeTheMergeRequestAtItsNextHead() throws Exception {
            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:30:00 +0100"));
            readAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        }

        @Test
        void shouldRemoveAGuessedApprovalDateOnlyOnAnAcceptedWholeSnapshotWithoutAnotherApprovalEvent()
                throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
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
            assertThat(current.getCommitId()).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .isEmpty();
        }

        @Test
        void shouldPersistAMatchingMergedNativeDateWithoutAnotherApprovalEvent() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
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
            assertThat(dated.getCommitId()).isNull();
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
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
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
            assertThat(after.getCommitId()).isNull();
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
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
            setReadiness(true, MergeStateStatus.CLEAN, ReviewDecision.APPROVED);

            // Its readiness read failed: only the hook itself is recorded.
            receive(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:32:00 +0100"));

            PullRequest withdrawn = stored();
            assertThat(withdrawn.getReviewDecision()).isNull();
            assertThat(withdrawn.getMergeable()).isNull();
            assertThat(withdrawn.getMergeStateStatus()).isNull();
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);

            read(mergeability(false, "not_approved"), Instant.now());

            assertThat(stored().getMergeStateStatus()).isEqualTo(MergeStateStatus.BLOCKED);
            assertThat(stored().getMergeable()).isFalse();
            assertThat(stored().getReviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
        }

        @Test
        void shouldForgetTheMergeabilityAResetChangesWithoutDismissingAnyoneAndRecoverItOnTheNextRead()
                throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
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

            // GitLab's GraphQL dates to the second: this read may describe the version before the
            // hook's.
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
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:32:00 +0100", "approvals_reset_on_push"));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, false, List.of(), "not_approved"), Instant.now());

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(approval.isDismissed()).isTrue();
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
        }

        @Test
        void shouldKeepTheApproversASelectiveResetLeft() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
            receiveWithApprovals(
                    asTutor(approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100")),
                    List.of(approver(), tutor()));
            receive(systemReset(NEXT_HEAD, "2026-01-31 22:32:00 +0100", "code_owner_approvals_reset_on_push"));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(tutor()), "mergeable"), Instant.now());

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(Objects.requireNonNull(approval(NATIVE_TUTOR_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isEqualTo(ReviewDecision.APPROVED);
        }

        @Test
        void shouldReadARenewedApprovalWithoutGuessingItsApprovedHead() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", FIXTURE_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
            receiveWithApprovals(
                    approvalEvent("merge_request.unapproved", FIXTURE_HEAD, "2026-01-31 22:32:00 +0100"), List.of());
            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.DISMISSED);
            // The tutor never approved, so this hook only moves the head.
            receive(asTutor(approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:33:00 +0100")));

            read(
                    facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable"),
                    Instant.now());

            PullRequestReview approval = Objects.requireNonNull(approval(NATIVE_APPROVER_ID));
            assertThat(approval.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(approval.isDismissed()).isFalse();
            assertThat(approval.getCommitId())
                    .as("a renewed approval inherits no head")
                    .isNull();
        }

        @Test
        void shouldKeepLastMembershipAndUnknownReadinessWhenTheReadPredatesTheWithdrawalHook() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));
            Instant readBegun = Instant.now();
            handler.handle(
                    approvalEvent("merge_request.unapproved", NEXT_HEAD, "2026-01-31 22:32:00 +0100"),
                    readBegun.plusSeconds(1));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(approver()), "mergeable"), readBegun);

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"checking", "approvals_syncing"})
        void shouldConfirmNothingWhileGitLabIsStillSettlingTheMergeRequest(String status) throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));

            read(facts(NEXT_HEAD, GitLabHeadPipeline.NOT_CAPTURED, true, List.of(), status), Instant.now());

            assertThat(Objects.requireNonNull(approval(NATIVE_APPROVER_ID)).getState())
                    .isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(reviewDecision()).isNull();
            assertThat(stored().getMergeable()).isNull();
        }

        @Test
        void shouldLeaveTheApprovalsAndTheDecisionUnknownWhenTheApproversWereNotRead() throws Exception {
            receiveWithApprovals(
                    approvalEvent("merge_request.approved", NEXT_HEAD, "2026-01-31 22:31:00 +0100"),
                    List.of(approver()));

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
            assertThat(tutorApproval.getCommitId())
                    .as("the approver list names who approves, not the approved head")
                    .isNull();
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
            assertThat(approval.getCommitId())
                    .as("a native approval date names no head")
                    .isNull();
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

        @Test
        void shouldTakeNoDiffBaseFromAPageThatLostItsHead() {
            syncPage(paired(FIRST_HEAD, FIRST_HEAD, "a".repeat(40)), List.of());
            assertThat(mr2().getBaseRefOid()).isEqualTo("a".repeat(40));

            Map<String, @Nullable Object> lost = paired(null, NEXT_HEAD, "b".repeat(40));
            lost.put("updatedAt", "2026-01-31T18:20:00Z");
            syncPage(
                    lost,
                    List.of(GraphQlResponses.error(
                            "Internal server error", "project", "mergeRequests", "nodes", 0, "diffHeadSha")));

            PullRequest after = mr2();
            assertThat(after.getHeadRefOid()).isEqualTo(FIRST_HEAD);
            assertThat(after.getBaseRefOid())
                    .as("a base is never stored beside a head the page did not read")
                    .isEqualTo("a".repeat(40));
        }

        @Test
        void shouldKeepTheDiffBaseOfALaterAcceptedReadOverAnEarlierPageAtTheSameVersion() {
            String version = "2026-01-31T18:10:00Z";
            syncPage(paired(FIRST_HEAD, FIRST_HEAD, "a".repeat(40)), List.of());
            assertThat(mr2().getBaseRefOid()).isEqualTo("a".repeat(40));

            Instant accepted = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            assertThat(mergeRequestProcessor.applyReadiness(
                            savedRepo,
                            MR2_IID,
                            new GitLabMergeRequestReadinessReader.Facts(
                                    savedRepo.getNativeId(),
                                    NATIVE_MR2_ID,
                                    "opened",
                                    Instant.parse(version),
                                    FIRST_HEAD,
                                    true,
                                    "mergeable",
                                    true,
                                    GitLabHeadPipeline.NOT_CAPTURED,
                                    List.of(),
                                    null,
                                    GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                                    null,
                                    new GitLabMergeRequestReadinessReader.DiffRefs(FIRST_HEAD, "b".repeat(40))),
                            accepted,
                            ProcessingContext.forSync(null, savedRepo)))
                    .isTrue();
            assertThat(mr2().getBaseRefOid()).isEqualTo("b".repeat(40));

            syncPage(paired(FIRST_HEAD, FIRST_HEAD, "c".repeat(40)), List.of());
            assertThat(mr2().getBaseRefOid()).isEqualTo("b".repeat(40));

            syncAt(accepted.plusSeconds(1), FIRST_HEAD, "c".repeat(40));
            assertThat(mr2().getBaseRefOid()).isEqualTo("c".repeat(40));

            syncAt(accepted.plusSeconds(2), null, "d".repeat(40));
            assertThat(mr2().getBaseRefOid())
                    .as("a base without its head is not accepted")
                    .isEqualTo("c".repeat(40));

            syncAt(accepted.plusSeconds(3), NEXT_HEAD, null);
            PullRequest moved = mr2();
            assertThat(moved.getHeadRefOid()).isEqualTo(NEXT_HEAD);
            assertThat(moved.getBaseRefOid())
                    .as("the base of the previous head is forgotten when no pair came with the" + " new one")
                    .isNull();
        }

        private Map<String, @Nullable Object> paired(@Nullable String head, String pairedHead, String base) {
            Map<String, @Nullable Object> node =
                    page(head, "2026-01-31T18:10:00Z", null, "mergeable", List.of(approverNode()));
            node.put("diffRefs", Map.of("headSha", pairedHead, "baseSha", base));
            return node;
        }

        private void syncAt(Instant fetchedAt, @Nullable String head, @Nullable String base) {
            mergeRequestProcessor.processFromSync(
                    syncedMergeRequest(true, "mergeable", null, null, head, GitLabHeadPipeline.NOT_CAPTURED, base),
                    ProcessingContext.forSync(null, savedRepo).withObservedAt(fetchedAt));
        }

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
            when(approvalClient.read(
                            savedWorkspace.getId(),
                            savedRepo.getNativeId(),
                            Integer.parseInt(Objects.toString(node.get("iid")))))
                    .thenReturn(rows);
        new GitLabMergeRequestSyncService(
                        provider,
                        graphQlResponseHandler,
                        mergeRequestProcessor,
                        mock(GitLabDiscussionSyncService.class),
                        mock(GitLabClosingIssueClient.class),
                        gitLabProperties,
                        approvalClient,
                        projectAdmission)
                .syncMergeRequests(savedWorkspace.getId(), savedRepo, null);
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

    private GitLabMergeRequestEventDTO systemReset(String head, String updatedAt, String systemAction)
            throws IOException {
        return edited("merge_request.unapproved", attributes -> {
            ((ObjectNode) attributes.get("last_commit")).put("id", head);
            attributes.put("updated_at", updatedAt);
            attributes.put("system", true);
            attributes.put("system_action", systemAction);
        });
    }

    private GitLabMergeRequestEventDTO edited(String filename, Consumer<ObjectNode> edit) throws IOException {
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

    private void receiveWithApprovals(
            GitLabMergeRequestEventDTO event, List<GitLabMergeRequestProcessor.SyncUserData> currentApprovers) {
        receive(event);
        PullRequest current = stored();
        var facts = new GitLabMergeRequestReadinessReader.Facts(
                savedRepo.getNativeId(),
                NATIVE_MR4_ID,
                "opened",
                Objects.requireNonNull(current.getUpdatedAt()),
                Objects.requireNonNull(current.getHeadRefOid()),
                true,
                currentApprovers.isEmpty() ? "not_approved" : "mergeable",
                !currentApprovers.isEmpty(),
                GitLabHeadPipeline.NOT_CAPTURED,
                List.of(),
                currentApprovers,
                GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                null);
        assertThat(mergeRequestProcessor.applyReadiness(
                        savedRepo, MR4_IID, facts, Instant.now(), ProcessingContext.forSync(null, savedRepo)))
                .isTrue();
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

    // GitLab sends an update hook when the last thread resolves, but the hook names no thread; read discussions to
    // resolve it.
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
                        transactionTemplate.executeWithoutResult(status -> repositoryMonitors.delete(repositoryMonitors
                                .findByWorkspaceIdAndNameWithOwner(savedWorkspace.getId(), FIXTURE_REPO_FULL_NAME)
                                .orElseThrow()));
                        return discussions(true);
                    })
                    .dispatchEvent(unchangedUpdate(), Instant.now());

            PullRequestReviewThread thread = thread();
            assertThat(thread.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(thread.getNodeId()).isNull();
        }

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
                            gitLabProperties,
                            projectAdmission),
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
        void iidNamespaceIsolation_issueAndMrCoexist() throws Exception {
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

            receive(loadPayload("merge_request.open"));

            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElse(null);
                assertThat(issue).isNotNull();
                assertThat(issue.getTitle()).isEqualTo("Issue with same IID");
                assertThat(issue.isPullRequest()).isFalse();

                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), MR3_IID)
                        .orElse(null);
                assertThat(pr).isNotNull();
                assertThat(pr.getTitle()).isEqualTo(MR3_TITLE);
                assertThat(pr.isPullRequest()).isTrue();
            });
        }
    }

    @Nested
    class DomainEvents {

        @Test
        void domainEvents_mr3Lifecycle() throws Exception {
            receive(loadPayload("merge_request.open"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
            receive(loadPayload("merge_request.close"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener
                            .ofType(ScmDomainEvent.PullRequestClosed.class)
                            .get(0)
                            .wasMerged())
                    .isFalse();

            eventListener.clear();
            receive(loadPayload("merge_request.reopen"));
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReopened.class))
                    .hasSize(1);
        }

        @Test
        void domainEvents_mr2Merge() throws Exception {
            receive(loadPayload("merge_request.update"));
            eventListener.clear();

            // Merge -> PullRequestClosed(wasMerged=true), then PullRequestMerged once the read
            // after it ran
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
            // A native approval snapshot publishes ReviewSubmitted.
            receiveWithApprovals(loadPayload("merge_request.approved"), List.of(approver()));
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewSubmitted.class))
                    .hasSize(1);

            eventListener.clear();

            // A native withdrawal snapshot publishes ReviewDismissed, never CHANGES_REQUESTED.
            receiveWithApprovals(loadPayload("merge_request.unapproved"), List.of());
            assertThat(eventListener.ofType(ScmDomainEvent.ReviewDismissed.class))
                    .hasSize(1);
        }
    }

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
        return syncedMergeRequest(approved, detailedMergeStatus, reviewers, approvers, head, headPipeline, null);
    }

    /** MR !2 as a sync reads it at {@code head}, with the diff base GitLab paired with that head where it gave one. */
    private GitLabMergeRequestProcessor.SyncMergeRequestData syncedMergeRequest(
            boolean approved,
            @Nullable String detailedMergeStatus,
            @Nullable List<GitLabMergeRequestProcessor.SyncReviewerData> reviewers,
            @Nullable List<GitLabMergeRequestProcessor.SyncUserData> approvers,
            @Nullable String head,
            GitLabHeadPipeline headPipeline,
            @Nullable String base) {
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
                base, // baseSha
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

        var workspace =
                WorkspaceTestFixtures.gitLabPatWorkspace(FIXTURE_ORG_LOGIN).withSlug("hephaestus-test-gitlab");
        workspace.build().setPublicActivityEnabled(true);
        savedWorkspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository, connectionRepository, workspace, "https://gitlab.lrz.de");
        repositoryMonitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(savedWorkspace, FIXTURE_REPO_FULL_NAME));
    }

    private static long persistedId(IdentityProvider provider) {
        return Objects.requireNonNull(provider.getId());
    }
}
