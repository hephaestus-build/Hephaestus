package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmConnectionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmModelRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettings;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionService;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelService;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionClosing;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.composer.ReviewComposeSandboxKind;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentService;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxDispatcher;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyViolations;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestChecksRecord;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.practices.AppliesWhen;
import de.tum.cit.aet.hephaestus.practices.AppliesWhenClause;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * One review of a real pull request, from its request to its admitted results and the run that composes them, with only the container and the
 * model's judgement stubbed. The server requests the review's sandbox; the sandbox ({@link ReviewSandboxStub}) pulls its
 * run, which captures the work, and speaks the runner's protocol over the gateway with its own token; the review model
 * is a stub provider behind the real proxy. Between the capture and the first practice start the work may change, as it does on a live install.
 * A review whose readiness withholds every practice spends no attempt on it and shows no failed job.
 */
// The agent runtime without a cluster, as AgentRuntimeWithoutCluster builds it; the sandbox is the runner stub.
@MockitoBean(name = "sandboxKubernetesClient", types = KubernetesClient.class, enforceOverride = true)
// Its worker claims every queued review job of the shared database, so its context must not outlive the class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewEndToEndIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final MockWebServer PROVIDER = new MockWebServer();
    private static final int GATEWAY_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void agentJobs(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.agent.enabled", () -> "true");
        // The Kubernetes sandbox runtime needs a fixed gateway port.
        registry.add("hephaestus.sandbox.gateway.port", () -> GATEWAY_PORT);
        // A review's sandbox reaches the gateway at this address, as the claim passes it.
        registry.add("hephaestus.agent.sandbox.gateway-url", () -> "http://127.0.0.1:" + GATEWAY_PORT);
        registry.add("hephaestus.sandbox.kubernetes.namespace", () -> "review-end-to-end");
        // The stub provider listens on this host.
        registry.add("hephaestus.llm.egress.allow-loopback", () -> "true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Sandbox {
        @Bean
        @Primary
        ReviewSandboxStub reviewSandboxStub(ObjectMapper mapper) {
            return new ReviewSandboxStub(mapper);
        }
    }

    /** What happens to the work between its capture and the first practice start. */
    enum Between {
        NOTHING,
        THE_DISCUSSION_SYNC_FINISHES,
        /**
         * The provider's sync reads the head's merge state and its checks: GitHub's empty status-check rollup or
         * GitLab's report that the head has no pipeline. A webhook carries neither, so the occasion did not hold them.
         */
        THE_SYNC_READS_THE_HEAD_STATUS,
        A_REMARK_ARRIVES,
        /** A new head replaces the version that the occasion recorded. */
        A_COMMIT_IS_PUSHED
    }

    /** What the practice reads: the work and its discussion, or sources that leave the work record out. */
    enum Reads {
        THE_WORK,
        NOT_THE_RECORD
    }

    /** How the review was asked for: by a person, or by a provider event whose occasion the review then reads. */
    enum Asked {
        BY_REQUEST,
        BY_EVENT
    }

    @Autowired
    private ReviewSandboxStub sandbox;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private SandboxDispatcher dispatcher;

    @Autowired
    private ReviewComposeSandboxKind composeKind;

    @Autowired
    private CompositionClosing compositionClosing;

    @Autowired
    private AgentRuns agentRuns;

    @Autowired
    private InstanceLlmSettingsRepository instanceLlmSettings;

    @Autowired
    private WorkspaceLlmConnectionService connections;

    @Autowired
    private WorkspaceLlmModelService models;

    @Autowired
    private ModelAssignmentService bindings;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private PullRequestRepository pulls;

    @Autowired
    private ItemIndexService itemWriter;

    @Autowired
    private List<ItemSource> itemSources;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private ReviewJobCompletionService completion;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ResultFreshness freshness;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AgentRunReaper reaper;

    @BeforeAll
    static void startProvider() throws IOException {
        PROVIDER.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""
                            {"id":"stub","object":"chat.completion","created":1,"model":"review",
                             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"MET"}}],
                             "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}
                            """)
                        .build();
            }
        });
        PROVIDER.start();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @AfterEach
    void clearContext() {
        WorkspaceContextHolder.clearContext();
    }

    /**
     * What the review left: its status and stop reason, the practice's status and reason, its results and whether each
     * still describes the work, the requests that reached the model provider, and what the runner sent and got.
     */
    private record Reviewed(
            Map<String, @Nullable Object> review,
            Map<String, @Nullable Object> practice,
            List<@Nullable String> outcomes,
            List<Currentness> currentness,
            int modelRequests,
            String calls) {}

    @Test
    void shouldMeasureThePracticeWhenTheWorkDidNotChange() {
        assertMeasured(review(Between.NOTHING));
    }

    /**
     * Another workspace measured a practice of the same slug on its own pull request number 32. This workspace's sandbox
     * then starts, asks the model, stages and seals with its own credential, over the real gateway, while the tenancy
     * check throws on any statement that names no workspace. The other workspace's review stays as it was, and no
     * statement was refused, also none that a caller caught and only logged.
     */
    @Test
    void shouldLeaveAnotherWorkspacesReviewAsItWasWhileThisWorkspaceIsMeasured() {
        var other = work(IdentityProviderType.GITHUB);
        sandbox.prepare(() -> {});
        UUID otherReview = requested(other, practice(other.workspace(), Reads.THE_WORK));
        settle(otherReview);
        var recorded = recorded(otherReview);
        assertThat(recorded).anySatisfy(row -> assertThat(row).containsEntry("outcome", "MET"));
        double refused = TenancyViolations.count(meters);

        var reviewed = review(Between.NOTHING);

        assertMeasured(reviewed);
        assertThat(sandbox.calls).anySatisfy(call -> assertThat(call.path()).isEqualTo("stage-result"));
        assertThat(recorded(otherReview)).isEqualTo(recorded);
        assertThat(TenancyViolations.count(meters)).isEqualTo(refused);
    }

    /** What a review recorded: its status, each practice and membership, its results, and its job's seal. */
    private List<Map<String, @Nullable Object>> recorded(UUID reviewId) {
        var rows = new ArrayList<Map<String, @Nullable Object>>();
        rows.addAll(jdbc.queryForList("SELECT status, stop_reason FROM review WHERE id = ?", reviewId));
        rows.addAll(jdbc.queryForList(
                "SELECT practice_id, status, reason FROM review_practice WHERE review_id = ? ORDER BY practice_id",
                reviewId));
        rows.addAll(jdbc.queryForList(
                "SELECT practice_id, item_id, state, reason FROM review_item WHERE review_id = ?"
                        + " ORDER BY practice_id, item_id",
                reviewId));
        rows.addAll(jdbc.queryForList(
                "SELECT id, outcome, ended_at, end_reason FROM result WHERE review_id = ? ORDER BY id", reviewId));
        rows.addAll(jdbc.queryForList(
                "SELECT id, status, admission_attempt, admission_digest FROM review_job WHERE review_id = ?"
                        + " ORDER BY id",
                reviewId));
        return rows;
    }

    /**
     * The only server is lost while the sandbox measures. The runner exits retryable once its work ends, so the attempt
     * runs again: a lost server is never a finding about the work, nor a failed review.
     */
    @Test
    void shouldRunTheAttemptAgainWhenItsServerIsLostDuringExecution() {
        var lost = new AtomicReference<Map<String, @Nullable Object>>();
        sandbox.loseGatewayDuringNextMeasurement(runId -> lost.set(jdbc.queryForMap(
                "SELECT j.status AS job, j.exit_code, j.capture IS NOT NULL AS prepared, r.state AS run, r.attempt,"
                        + " v.status AS review, v.stop_reason FROM review_job j JOIN agent_run r ON r.id = j.run_id"
                        + " JOIN review v ON v.id = j.review_id WHERE j.run_id = ?::uuid",
                runId)));

        var reviewed = review(Between.NOTHING);

        assertThat(lost.get())
                .as("the lost attempt waits for its next one, with one retry spent: %s", reviewed.calls())
                .containsEntry("job", "QUEUED")
                .containsEntry("exit_code", null)
                .containsEntry("prepared", true)
                .containsEntry("run", "QUEUED")
                .containsEntry("attempt", 1)
                .containsEntry("stop_reason", null);
        assertThat(lost.get().get("review")).isIn("QUEUED", "RUNNING");
        assertMeasured(reviewed);
    }

    @Test
    void shouldMeasureThePracticeWhenTheDiscussionSyncFinishesAfterTheCapture() {
        assertMeasured(review(Between.THE_DISCUSSION_SYNC_FINISHES));
    }

    @Test
    void shouldWithholdThePracticeAsReplacedWhenARemarkArrivesAfterTheCapture() {
        assertReplaced(review(Between.A_REMARK_ARRIVES));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldMeasureTheEventPracticeWhenTheWorkDidNotChange(IdentityProviderType provider) {
        assertMeasured(review(provider, Asked.BY_EVENT, Between.NOTHING));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldMeasureTheEventPracticeWhenTheSyncReadsTheHeadStatusAfterTheOccasion(IdentityProviderType provider) {
        assertMeasured(review(provider, Asked.BY_EVENT, Between.THE_SYNC_READS_THE_HEAD_STATUS));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldWithholdTheEventPracticeAsReplacedWhenARemarkArrivesAfterTheCapture(IdentityProviderType provider) {
        assertReplaced(review(provider, Asked.BY_EVENT, Between.A_REMARK_ARRIVES));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldWithholdTheEventPracticeAsReplacedWhenACommitReplacesTheOccasion(IdentityProviderType provider) {
        assertReplaced(review(provider, Asked.BY_EVENT, Between.A_COMMIT_IS_PUSHED));
    }

    /**
     * Runs each queued composition of the review as its review sandbox's loop would, with a model that decides nothing:
     * the run withholds what is open and ends, so the review can end.
     */
    private void composeQueued(UUID reviewId) {
        var queued = jdbc.queryForList(
                "SELECT r.workspace_id, r.concurrency_key FROM agent_run r JOIN review_job j ON j.run_id = r.id"
                        + " WHERE j.review_id = ? AND r.kind = 'REVIEW_COMPOSE' AND r.state = 'QUEUED'",
                reviewId);
        for (var row : queued) {
            long workspaceId = ((Number) Objects.requireNonNull(row.get("workspace_id"))).longValue();
            String subject = (String) Objects.requireNonNull(row.get("concurrency_key"));
            var sandbox =
                    new AgentSandboxes.LiveSandbox(UUID.randomUUID(), workspaceId, subject, AgentSandboxKind.REVIEW);
            // The lease and the claim commit together, as the gateway's next does.
            var run = transactions.execute(status -> agentRuns
                    .claimSubject(workspaceId, subject, Set.of(AgentRunKind.REVIEW_COMPOSE))
                    .filter(lease -> composeKind.claimed(sandbox, lease))
                    .map(lease -> new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt()))
                    .orElse(null));
            if (run == null) continue;
            var composing = composeKind.prepare(run);
            compositionClosing.withholdOpen(composing.job(), composing.identity(), CompositionStop.NO_DECISION);
            transactions.executeWithoutResult(status -> composeKind.end(run, SandboxRunKind.Outcome.COMPLETED));
        }
    }

    private void assertReplaced(Reviewed reviewed) {
        assertThat(sandbox.calls)
                .as("the practice does not start on work it no longer shows: %s", reviewed.calls())
                .anySatisfy(call -> assertThat(call.path() + " " + call.body())
                        .contains("start-practice")
                        .contains("\"current\":false"))
                .noneSatisfy(call -> assertThat(call.path()).isEqualTo("completions"));
        assertThat(reviewed.practice())
                .as(reviewed.calls())
                .containsEntry("status", "WITHHELD")
                .containsEntry("reason", "SOURCES_MOVED");
        assertThat(reviewed.outcomes()).isEmpty();
        assertThat(reviewed.modelRequests()).isZero();
        assertThat(reviewed.review())
                .as("the review says the practice's sources moved, not that its runtime fell short")
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "SOURCES_MOVED");
    }

    private void assertMeasured(Reviewed reviewed) {
        assertThat(sandbox.calls)
                .as("the practice starts, asks the model and is admitted: %s", reviewed.calls())
                .anySatisfy(call -> assertThat(call.path() + " " + call.body())
                        .contains("start-practice")
                        .contains("\"current\":true"))
                .anySatisfy(call -> assertThat(call.path()).isEqualTo("completions"))
                .anySatisfy(call -> assertThat(call.path()).isEqualTo("admit-results"));
        assertThat(reviewed.practice()).as(reviewed.calls()).containsEntry("status", "DONE");
        assertThat(reviewed.outcomes()).as(reviewed.calls()).containsExactly("MET");
        assertThat(reviewed.review()).containsEntry("status", "COMPLETE").containsEntry("stop_reason", null);
        assertThat(reviewed.currentness())
                .as("the feedback still describes the work, so it can be delivered and shown")
                .containsExactly(Currentness.CURRENT);
        assertThat(reviewed.modelRequests())
                .as("the proxy reached the provider once")
                .isEqualTo(1);
    }

    private Reviewed review(Between between) {
        return review(IdentityProviderType.GITHUB, Asked.BY_REQUEST, between);
    }

    /**
     * A capture reads what its open practices declare, so a retry whose remaining practices read only the code once
     * captured no work record and could not prepare. Every practice's inputs carry that record, so the capture still
     * reads its source. Git checkouts are off here, so the practice requires the discussion instead of the diff.
     */
    @Test
    void shouldMeasureThePracticeWhenNoOpenPracticeReadsTheWorkRecord() {
        var reviewed = review(IdentityProviderType.GITHUB, Asked.BY_REQUEST, Between.NOTHING, Reads.NOT_THE_RECORD);
        // The stub cites the work record, which this practice does not declare, so admission withholds that citation.
        // What matters here is that the attempt prepares, starts the practice and asks the model.
        assertThat(sandbox.calls)
                .as("the attempt prepares and measures: %s", reviewed.calls())
                .anySatisfy(call -> assertThat(call.path() + " " + call.body())
                        .contains("start-practice")
                        .contains("\"current\":true"))
                .anySatisfy(call -> assertThat(call.path()).isEqualTo("completions"));
        assertThat(reviewed.modelRequests()).isEqualTo(1);
    }

    @Test
    void shouldCreateNoJobAndNoRunWhenSelectionWithholdsEveryPractice() {
        var work = work(IdentityProviderType.GITHUB);
        var assignee = practice(
                work.workspace(), "about-its-assignee", AboutRole.ASSIGNEE, List.of(read("scm.pull-request.core")));
        var checks = practice(
                work.workspace(),
                "waits-for-its-checks",
                AboutRole.AUTHOR,
                List.of(
                        read("scm.pull-request.core"),
                        new PracticeReadSource(PullRequestChecksRecord.KIND, ReadNeed.REQUIRED)));

        UUID reviewId = requested(work, assignee, checks);

        assertThat(reviewRow(reviewId))
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "READINESS_REFUSED");
        assertThat(membership(reviewId, assignee))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "ROLE_UNAVAILABLE");
        assertThat(membership(reviewId, checks))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "NO_CHECKS_REPORTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_job WHERE review_id = ?", Long.class, reviewId))
                .isZero();
        assertThat(runs(work)).isZero();
    }

    @Test
    void shouldRunOnlyTheReadyPracticesWhenSelectionWithholdsSome() {
        var work = work(IdentityProviderType.GITHUB);
        var ready = practice(work.workspace(), Reads.THE_WORK);
        var assignee = practice(
                work.workspace(), "about-its-assignee", AboutRole.ASSIGNEE, List.of(read("scm.pull-request.core")));
        sandbox.prepare(() -> {});

        UUID reviewId = requested(work, ready, assignee);

        assertThat(membership(reviewId, ready)).containsEntry("state", "LISTED");
        assertThat(membership(reviewId, assignee))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "ROLE_UNAVAILABLE");
        assertThat(await().atMost(Duration.ofSeconds(30))
                        .until(
                                () -> jdbc.queryForObject(
                                        "SELECT count(*) FROM review_job WHERE review_id = ?", Long.class, reviewId),
                                count -> count > 0))
                .isEqualTo(1);
        assertThat(runs(work)).isEqualTo(1);

        settle(reviewId);

        assertThat(sandbox.calls)
                .as("only the ready practice starts")
                .filteredOn(call -> call.path().equals("start-practice"))
                .singleElement()
                .satisfies(call -> assertThat(call.body()).contains(ready.getSlug()));
        assertThat(membership(reviewId, ready)).containsEntry("state", "JUDGED");
        assertThat(membership(reviewId, assignee))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "ROLE_UNAVAILABLE");
        assertThat(reviewRow(reviewId))
                .as("a practice withheld on purpose is not a runtime that fell short")
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "READINESS_REFUSED");
    }

    /** The mirror has not read the whole discussion, so a practice that requires it whole is not ready at capture. */
    @Test
    void shouldCompleteTheAttemptWithoutASandboxWhenCaptureReadinessWithholdsEveryPractice() {
        var work = work(IdentityProviderType.GITHUB);
        var whole = wholeDiscussion(work);
        sandbox.prepare(() -> {});
        int providerRequests = PROVIDER.getRequestCount();

        UUID reviewId = requested(work, whole);
        settle(reviewId);

        assertCompletedWithoutMeasurement(reviewId, 0);
        assertThat(PROVIDER.getRequestCount()).isEqualTo(providerRequests);
        assertThat(membership(reviewId, whole))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "READINESS_REFUSED");
        assertThat(reviewRow(reviewId))
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "READINESS_REFUSED");
    }

    /** Selection withholds one practice for its role, and capture withholds the other: the review was not ready. */
    @Test
    void shouldEndAsNotReadyWhenSelectionAndCaptureEachWithholdAPractice() {
        var work = work(IdentityProviderType.GITHUB);
        var assignee = practice(
                work.workspace(), "about-its-assignee", AboutRole.ASSIGNEE, List.of(read("scm.pull-request.core")));
        var whole = wholeDiscussion(work);
        sandbox.prepare(() -> {});

        UUID reviewId = requested(work, assignee, whole);
        settle(reviewId);

        assertCompletedWithoutMeasurement(reviewId, 0);
        assertThat(membership(reviewId, assignee))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "ROLE_UNAVAILABLE");
        assertThat(membership(reviewId, whole))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "READINESS_REFUSED");
        assertThat(reviewRow(reviewId))
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "READINESS_REFUSED");
    }

    /**
     * The only server crashes after the attempt staged its practice and before it sealed. The next attempt finds no
     * open practice: it seals the staged result without a sandbox, and the result's feedback is composed.
     */
    @Test
    void shouldComposeWhatALostAttemptStagedWhenTheNextAttemptFindsNoOpenPractice() {
        var work = work(IdentityProviderType.GITHUB);
        var practice = practice(work.workspace(), Reads.THE_WORK);
        sandbox.prepare(() -> {});
        sandbox.crashAfterNextStage(this::expireLease);
        int providerRequests = PROVIDER.getRequestCount();

        UUID reviewId = requested(work, practice);
        settle(reviewId);

        assertComposedWithoutMeasurement(reviewId, work);
        assertThat(membership(reviewId, practice)).containsEntry("state", "JUDGED");
        assertThat(reviewRow(reviewId)).containsEntry("status", "COMPLETE").containsEntry("stop_reason", null);
        assertThat(PROVIDER.getRequestCount() - providerRequests).isEqualTo(1);
    }

    /**
     * As above, but the pull request moved on before the next attempt. The next attempt seals the staged result
     * without capturing the work again, and composes its feedback from the inputs that staging kept: those of the
     * version it measured. Nothing fails.
     */
    @Test
    void shouldComposeWhatALostAttemptStagedWhenTheWorkMovedOnBeforeTheNextAttempt() {
        var work = work(IdentityProviderType.GITHUB);
        var practice = practice(work.workspace(), Reads.THE_WORK);
        sandbox.prepare(() -> {});
        sandbox.crashAfterNextStage(runId -> {
            jdbc.update(
                    "UPDATE issue SET head_ref_oid = ? WHERE id = ?",
                    "c".repeat(40),
                    work.pull().getId());
            expireLease(runId);
        });

        UUID reviewId = requested(work, practice);
        settle(reviewId);

        assertComposedWithoutMeasurement(reviewId, work);
        assertThat(membership(reviewId, practice)).containsEntry("state", "JUDGED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_composition_basis b JOIN review_job j ON j.id = b.job_id"
                                + " WHERE j.review_id = ?",
                        Long.class,
                        reviewId))
                .as("the composition reads the inputs that staging kept")
                .isEqualTo(1);
        assertThat(reviewRow(reviewId).get("status")).isNotEqualTo("FAILED");
    }

    /**
     * The only server crashes after the attempt staged its practice, and the staged copy expires before the next
     * attempt. That attempt finds no open practice, and its seal refuses the staged result. The seal admits only that
     * the other practice does not apply: nothing new was measured, so no feedback composition runs.
     */
    @Test
    void shouldCompleteWithoutCompositionWhenTheSealRefusesWhatALostAttemptStaged() {
        var work = work(IdentityProviderType.GITHUB);
        var practice = practice(work.workspace(), Reads.THE_WORK);
        var merged = practice(
                work.workspace(),
                "concerns-a-merge",
                AboutRole.AUTHOR,
                List.of(read("scm.pull-request.core")),
                new AppliesWhen(
                        List.of(new AppliesWhenClause(null, null, null, true)), "The pull request is not merged."));
        sandbox.prepare(() -> {});
        sandbox.crashAfterNextStage(runId -> {
            assertThat(jdbc.update(
                            "UPDATE review_item SET staged_payload = jsonb_set(staged_payload, '{evidence,expiresAt}',"
                                    + " to_jsonb(CAST(? AS text))) WHERE practice_id = ? AND state = 'STAGED'",
                            Instant.now().minusSeconds(1).toString(),
                            practice.getId()))
                    .isEqualTo(1);
            expireLease(runId);
        });

        UUID reviewId = requested(work, practice, merged);
        settle(reviewId);

        assertThat(jdbc.queryForList(
                        "SELECT kind || '/' || state || '/' || coalesce(stop_reason, '') FROM agent_run"
                                + " WHERE workspace_id = ? ORDER BY created_at",
                        String.class,
                        work.workspace().getId()))
                .as(sandbox.calls.toString())
                .containsExactly("REVIEW/ENDED/COMPLETED");
        assertThat(membership(reviewId, practice))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "COPY_AUTHORITY_UNAVAILABLE");
        assertThat(jdbc.queryForList("SELECT outcome FROM result WHERE review_id = ?", String.class, reviewId))
                .containsExactly("DOES_NOT_APPLY");
        assertThat(jdbc.queryForMap("SELECT status, error_message FROM review_job WHERE review_id = ?", reviewId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("error_message", null);
    }

    /**
     * A practice that only a merge can concern does not apply to open work. Its capture admits that result without a
     * sandbox, and the attempt completes: no feedback composition runs for a review that measured nothing new.
     */
    @Test
    void shouldCompleteWithoutCompositionWhenCaptureFindsEveryPracticeInapplicable() {
        var work = work(IdentityProviderType.GITHUB);
        var merged = practice(
                work.workspace(),
                "concerns-a-merge",
                AboutRole.AUTHOR,
                List.of(read("scm.pull-request.core")),
                new AppliesWhen(
                        List.of(new AppliesWhenClause(null, null, null, true)), "The pull request is not merged."));
        sandbox.prepare(() -> {});

        UUID reviewId = requested(work, merged);
        settle(reviewId);

        assertThat(jdbc.queryForList(
                        "SELECT kind || '/' || state || '/' || coalesce(stop_reason, '') FROM agent_run"
                                + " WHERE workspace_id = ? ORDER BY created_at",
                        String.class,
                        work.workspace().getId()))
                .as(sandbox.calls.toString())
                .containsExactly("REVIEW/ENDED/COMPLETED");
        assertThat(jdbc.queryForList("SELECT outcome FROM result WHERE review_id = ?", String.class, reviewId))
                .containsExactly("DOES_NOT_APPLY");
        assertThat(jdbc.queryForMap("SELECT status, error_message FROM review_job WHERE review_id = ?", reviewId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("error_message", null);
    }

    /**
     * As above, but one practice was still open after the crash, and the next capture finds it not ready: the staged
     * result is still composed, and the other practice keeps its readiness reason.
     */
    @Test
    void shouldComposeWhatALostAttemptStagedWhenCaptureReadinessWithholdsTheRest() {
        var work = work(IdentityProviderType.GITHUB);
        discussionReadWhole(work, true);
        var first = wholeDiscussion(work, "reads-the-whole-discussion");
        var second = wholeDiscussion(work, "reads-the-whole-discussion-too");
        sandbox.prepare(() -> {});
        sandbox.crashAfterNextStage(runId -> {
            discussionReadWhole(work, false);
            expireLease(runId);
        });
        int providerRequests = PROVIDER.getRequestCount();

        UUID reviewId = requested(work, first, second);
        settle(reviewId);

        assertComposedWithoutMeasurement(reviewId, work);
        assertThat(List.of(membership(reviewId, first), membership(reviewId, second)))
                .extracting(member -> member.get("state") + "/" + member.get("reason"))
                .containsExactlyInAnyOrder("JUDGED/null", "WITHHELD/READINESS_REFUSED");
        assertThat(reviewRow(reviewId))
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "READINESS_REFUSED");
        assertThat(PROVIDER.getRequestCount() - providerRequests).isEqualTo(1);
    }

    /**
     * The work changes between the capture and the practice start, so the start withholds the practice as replaced.
     * Then the only server is lost before the attempt seals, and the next attempt finds no open practice.
     */
    @Test
    void shouldCompleteTheNextAttemptWithoutASandboxWhenEveryPracticeEndedAtItsStart() {
        var work = work(IdentityProviderType.GITHUB);
        var practice = practice(work.workspace(), Reads.THE_WORK);
        sandbox.prepare(() -> remark(work.pull(), work.developer(), "A later remark", "2026-10-01T12:00:00Z"));
        var lost = new AtomicReference<Map<String, @Nullable Object>>();
        sandbox.loseGatewayBeforeNextSeal(runId -> lost.set(jdbc.queryForMap(
                "SELECT j.status AS job, r.state AS run, r.attempt FROM review_job j"
                        + " JOIN agent_run r ON r.id = j.run_id WHERE j.run_id = ?::uuid",
                runId)));
        int providerRequests = PROVIDER.getRequestCount();

        UUID reviewId = requested(work, practice);
        settle(reviewId);

        assertThat(sandbox.calls)
                .as("the first attempt starts the practice, and the start withholds it")
                .anySatisfy(call -> assertThat(call.path() + " " + call.body())
                        .contains("start-practice")
                        .contains("\"current\":false"));
        assertThat(lost.get())
                .as("the lost attempt waits for its next one, with one retry spent")
                .containsEntry("job", "QUEUED")
                .containsEntry("run", "QUEUED")
                .containsEntry("attempt", 1);
        assertCompletedWithoutMeasurement(reviewId, 1);
        assertThat(PROVIDER.getRequestCount()).isEqualTo(providerRequests);
        assertThat(membership(reviewId, practice))
                .containsEntry("state", "WITHHELD")
                .containsEntry("reason", "SOURCES_MOVED");
        assertThat(reviewRow(reviewId))
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "SOURCES_MOVED");
    }

    /**
     * The attempt ended while it prepared: its job completed with no error, and its run ended completed. No sandbox
     * measured, and the attempt spent no retry: the run's spent attempts stay at {@code spent}.
     */
    private void assertCompletedWithoutMeasurement(UUID reviewId, int spent) {
        assertThat(jdbc.queryForMap(
                        "SELECT j.status AS job, j.error_message, j.execution_started_at, r.state AS run,"
                                + " r.stop_reason AS run_stop, r.attempt AS spent"
                                + " FROM review_job j JOIN agent_run r ON r.id = j.run_id WHERE j.review_id = ?",
                        reviewId))
                .as(sandbox.calls.toString())
                .containsEntry("job", "COMPLETED")
                .containsEntry("error_message", null)
                .containsEntry("execution_started_at", null)
                .containsEntry("run", "ENDED")
                .containsEntry("run_stop", "COMPLETED")
                .containsEntry("spent", spent);
        assertThat(sandbox.calls)
                .as("the attempt stops at its preparation")
                .anySatisfy(
                        call -> assertThat(call.path() + " " + call.status()).isEqualTo("context 409"))
                .noneSatisfy(call -> assertThat(call.path()).isIn("completions", "stage-result", "admit-results"));
    }

    private Map<String, @Nullable Object> reviewRow(UUID reviewId) {
        return jdbc.queryForMap("SELECT status, stop_reason FROM review WHERE id = ?", reviewId);
    }

    private Map<String, @Nullable Object> membership(UUID reviewId, Practice practice) {
        return jdbc.queryForMap(
                "SELECT state, reason FROM review_item WHERE review_id = ? AND practice_id = ?",
                reviewId,
                practice.getId());
    }

    private long runs(Work work) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE workspace_id = ?",
                Long.class,
                work.workspace().getId()));
    }

    /** A practice that requires the whole discussion, on work whose mirror has not read it whole. */
    private Practice wholeDiscussion(Work work) {
        discussionReadWhole(work, false);
        return wholeDiscussion(work, "reads-the-whole-discussion");
    }

    private Practice wholeDiscussion(Work work, String slug) {
        return practice(
                work.workspace(),
                slug,
                AboutRole.AUTHOR,
                List.of(
                        read("scm.pull-request.core"),
                        new PracticeReadSource(new SourceKind("scm.general-review-comments"), ReadNeed.COMPLETE)));
    }

    private void discussionReadWhole(Work work, boolean whole) {
        jdbc.update(
                "UPDATE issue SET discussion_read_whole = ? WHERE id = ?",
                whole,
                work.pull().getId());
    }

    /** The crashed attempt's lease expires, and the reaper queues its run again, due at once. */
    private void expireLease(String runId) {
        jdbc.update(
                "UPDATE agent_run SET leased_until = clock_timestamp() - interval '1 second' WHERE id = ?::uuid",
                runId);
        reaper.reap();
        jdbc.update(
                "UPDATE agent_run SET available_at = clock_timestamp() - interval '1 second'"
                        + " WHERE id = ?::uuid AND state = 'QUEUED'",
                runId);
    }

    /**
     * The attempt after the crash sealed the staged results without a sandbox and handed them on: its review run ended
     * handed off, and the run that composed their feedback ended. No practice started again.
     */
    private void assertComposedWithoutMeasurement(UUID reviewId, Work work) {
        assertThat(jdbc.queryForList(
                        "SELECT kind || '/' || state || '/' || coalesce(stop_reason, '') FROM agent_run"
                                + " WHERE workspace_id = ? ORDER BY created_at",
                        String.class,
                        work.workspace().getId()))
                .as(sandbox.calls.toString())
                .containsExactly("REVIEW/ENDED/HANDED_OFF", "REVIEW_COMPOSE/ENDED/COMPLETED");
        assertThat(sandbox.calls)
                .as("the attempt after the crash stops at its preparation")
                .anySatisfy(
                        call -> assertThat(call.path() + " " + call.status()).isEqualTo("context 409"))
                .noneSatisfy(call -> assertThat(call.path()).isEqualTo("admit-results"))
                .filteredOn(call -> call.path().equals("start-practice"))
                .hasSize(1);
        assertThat(jdbc.queryForList("SELECT outcome FROM result WHERE review_id = ?", String.class, reviewId))
                .containsExactly("MET");
        assertThat(jdbc.queryForMap(
                        "SELECT j.status AS job, j.error_message, r.attempt AS spent FROM review_job j"
                                + " JOIN agent_run r ON r.workspace_id = j.workspace_id AND r.kind = 'REVIEW'"
                                + " WHERE j.review_id = ?",
                        reviewId))
                .as("the attempt after the crash spent no retry: only the crash did")
                .containsEntry("job", "COMPLETED")
                .containsEntry("error_message", null)
                .containsEntry("spent", 1);
    }

    private static PracticeReadSource read(String source) {
        return new PracticeReadSource(new SourceKind(source), ReadNeed.REQUIRED);
    }

    private Reviewed review(IdentityProviderType type, Asked asked, Between between) {
        return review(type, asked, between, Reads.THE_WORK);
    }

    /** A pull request by its developer, in a workspace that reviews it with the stub model. */
    private record Work(Workspace workspace, ItemRef item, PullRequest pull, User developer) {}

    private Work work(IdentityProviderType type) {
        var provider = type == IdentityProviderType.GITHUB ? ensureGitHubProvider() : ensureGitLabProvider();
        User persisted = persistUser("review-end-to-end-" + UUID.randomUUID());
        persisted.setProvider(provider);
        User developer = users.saveAndFlush(persisted);
        Workspace workspace = workspace(developer, provider);
        Repository repository = repository(workspace, provider);
        PullRequest pull = pullRequest(repository, developer);
        ItemRef holder = itemWriter.upsert(new ItemRegistration(
                workspace.getId(), new ItemKind("scm.repository"), repository.getId(), null, null));
        ItemRef item = itemWriter.upsert(new ItemRegistration(
                workspace.getId(), new ItemKind("scm.pull_request"), pull.getId(), holder.itemId(), null));
        return new Work(workspace, item, pull, developer);
    }

    private Reviewed review(IdentityProviderType type, Asked asked, Between between, Reads reads) {
        var work = work(type);
        Workspace workspace = work.workspace();
        ItemRef item = work.item();
        PullRequest pull = work.pull();
        User developer = work.developer();
        Practice practice = practice(workspace, reads);
        // A review can start while the discussion sync still runs: the mirror has not read the whole discussion yet.
        if (between == Between.THE_DISCUSSION_SYNC_FINISHES)
            jdbc.update("UPDATE issue SET discussion_read_whole=FALSE WHERE id=?", pull.getId());
        sandbox.prepare(() -> {
            switch (between) {
                case NOTHING -> {}
                // The sync finishes and finds nothing new: the work did not change.
                case THE_DISCUSSION_SYNC_FINISHES ->
                    jdbc.update("UPDATE issue SET discussion_read_whole=TRUE WHERE id=?", pull.getId());
                case THE_SYNC_READS_THE_HEAD_STATUS ->
                    jdbc.update(
                            "UPDATE issue SET merge_state_status='CLEAN', head_check_state=?, head_check_sha=?"
                                    + " WHERE id=?",
                            type == IdentityProviderType.GITHUB ? "NONE" : "NO_PIPELINE",
                            pull.getHeadRefOid(),
                            pull.getId());
                case A_REMARK_ARRIVES -> remark(pull, developer, "A later remark", "2026-10-01T12:00:00Z");
                case A_COMMIT_IS_PUSHED ->
                    jdbc.update("UPDATE issue SET head_ref_oid=? WHERE id=?", "c".repeat(40), pull.getId());
            }
        });
        int providerRequests = PROVIDER.getRequestCount();

        UUID reviewId =
                asked == Asked.BY_EVENT ? opened(workspace, item, pull, type, developer) : requested(work, practice);
        settle(reviewId);
        return new Reviewed(
                jdbc.queryForMap("SELECT status, stop_reason FROM review WHERE id = ?", reviewId),
                jdbc.queryForMap(
                        "SELECT status, reason FROM review_practice WHERE review_id = ? AND practice_id = ?",
                        reviewId,
                        practice.getId()),
                jdbc.queryForList(
                        "SELECT outcome FROM result WHERE review_id = ? AND practice_id = ?",
                        String.class,
                        reviewId,
                        practice.getId()),
                List.copyOf(freshness
                        .currentness(
                                workspace.getId(),
                                jdbc.query(
                                        "SELECT id FROM result WHERE review_id = ? AND practice_id = ? AND ended_at IS NULL",
                                        (row, index) -> new ResultFreshness.Claim(
                                                row.getObject("id", UUID.class),
                                                workspace.getId(),
                                                reviewId,
                                                practice.getId(),
                                                item.itemId(),
                                                null),
                                        reviewId,
                                        practice.getId()))
                        .values()),
                PROVIDER.getRequestCount() - providerRequests,
                sandbox.calls.toString());
    }

    /**
     * The dispatch starts the review's sandbox, the sandbox measures and ends its run, and the server's completion
     * settles each practice of the review: each pass here does what a scheduler does outside the test profile.
     */
    private void settle(UUID reviewId) {
        try {
            await().atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> {
                        dispatcher.dispatch();
                        sandbox.drive();
                        composeQueued(reviewId);
                        completion.tick();
                        // A practice withheld at its start is final before the review that held it ends.
                        return !jdbc.queryForList(
                                        "SELECT p.status FROM review_practice p JOIN review r ON r.id = p.review_id"
                                                + " WHERE p.review_id = ? AND p.status IN ('DONE', 'WITHHELD', 'FAILED')"
                                                + " AND r.status NOT IN ('QUEUED', 'RUNNING')",
                                        String.class,
                                        reviewId)
                                .isEmpty();
                    });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError("The review did not settle: " + state(reviewId) + " " + sandbox.calls, timeout);
        }
    }

    private UUID requested(Work work, Practice... practices) {
        var pinned = itemSources.stream()
                .filter(source -> source.supports(work.item()))
                .findFirst()
                .orElseThrow()
                .pin(work.item(), null);
        return reviews.request(new ReviewRequest(
                work.workspace().getId(),
                work.item().itemId(),
                ReviewKind.ITEM,
                pinned.version(),
                ReviewOrigin.REQUEST,
                null,
                accountId(work.developer()),
                null,
                null,
                null,
                null,
                Arrays.stream(practices).map(Practice::getId).toList(),
                false,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null));
    }

    /**
     * The provider reports the pull request as opened. Its occasion keeps the work as the webhook stored it, and the
     * occasion's review follows at once: the test profile has no quiet window.
     */
    private UUID opened(Workspace workspace, ItemRef item, PullRequest pull, IdentityProviderType type, User author) {
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                null,
                DataSource.WEBHOOK,
                "opened",
                UUID.randomUUID().toString(),
                type,
                author.getId());
        transactions.executeWithoutResult(ignored -> publisher.publishEvent(
                new ScmDomainEvent.PullRequestCreated(ScmEventPayload.PullRequestData.from(pull), context)));
        try {
            return await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(100))
                    .until(
                            () -> jdbc
                                    .queryForList(
                                            "SELECT r.id FROM review r JOIN item_event e ON e.workspace_id=r.workspace_id"
                                                    + " AND e.id=r.event_id"
                                                    + " WHERE r.workspace_id=? AND r.item_id=?"
                                                    + " AND e.evidence->'record' IS NOT NULL",
                                            UUID.class,
                                            workspace.getId(),
                                            item.itemId())
                                    .stream()
                                    .findFirst()
                                    .orElse(null),
                            reviewId -> reviewId != null);
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(
                    "The occasion did not start a review: "
                            + jdbc.queryForList(
                                    "SELECT name, state, reason, evidence->>'unretained' AS unretained FROM item_event"
                                            + " WHERE workspace_id=? AND item_id=?",
                                    workspace.getId(),
                                    item.itemId()),
                    timeout);
        }
    }

    private String state(UUID reviewId) {
        return jdbc.queryForList("""
                        SELECT j.status, j.processing_status, j.exit_code, j.error_message, r.state, r.stop_reason,
                            v.status AS review_status, v.stop_reason AS review_stop_reason
                        FROM review_job j JOIN agent_run r ON r.id = j.run_id JOIN review v ON v.id = j.review_id
                        WHERE j.review_id = ?
                        """, reviewId).toString();
    }

    private Workspace workspace(User developer, IdentityProvider provider) {
        InstanceLlmSettings settings = instanceLlmSettings.findById((short) 1).orElseGet(() -> {
            InstanceLlmSettings created = new InstanceLlmSettings();
            created.setId((short) 1);
            return created;
        });
        settings.setAllowWorkspaceConnections(true);
        instanceLlmSettings.save(settings);

        String slug = "review-end-to-end-" + System.nanoTime();
        Workspace workspace = createWorkspace(
                slug, "Review end to end", slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaces.saveAndFlush(workspace);
        ensureAdminMembership(workspace);
        ensureMembership(workspace, developer, Membership.WorkspaceRole.MEMBER);
        connectActivitySource(workspace, provider);
        WorkspaceContext context =
                WorkspaceContext.fromWorkspace(workspace, Set.of(Membership.WorkspaceRole.ADMIN), null);
        WorkspaceContextHolder.setContext(context);
        var connection = connections.create(
                context,
                new CreateWorkspaceLlmConnectionRequestDTO(
                        null,
                        "stub-provider",
                        PROVIDER.url("/v1").toString(),
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        LlmAuthMode.BEARER,
                        "stub-key",
                        true,
                        null));
        Long model = models.create(
                        context,
                        connection.getId(),
                        new CreateWorkspaceLlmModelRequestDTO(
                                null,
                                "review",
                                "review",
                                128_000,
                                4_096,
                                null,
                                null,
                                null,
                                true,
                                PricingMode.PRICED,
                                new BigDecimal("0.10"),
                                new BigDecimal("0.40"),
                                new BigDecimal("0.05"),
                                new BigDecimal("0.10"),
                                null,
                                null))
                .getId();
        bindings.upsertBinding(
                context,
                ModelPurpose.PRACTICE_REVIEW,
                DataHandlingTier.UNDECLARED,
                new ModelAssignmentRequestDTO(null, model, null, true));
        return workspace;
    }

    private Repository repository(Workspace workspace, IdentityProvider provider) {
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("quiet");
        repository.setNameWithOwner(workspace.getAccountLogin() + "/quiet");
        repository.setHtmlUrl(provider.getServerUrl() + "/" + workspace.getAccountLogin() + "/quiet");
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId()));
        return repository;
    }

    private PullRequest pullRequest(Repository repository, User author) {
        var issue = new Issue();
        issue.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber(31);
        issue.setTitle("A linked issue");
        issue.setBody("What the change resolves.");
        issue.setState(Issue.State.OPEN);
        issue.setAuthor(author);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/31");
        issue.setCreatedAt(Instant.parse("2026-10-01T09:00:00Z"));
        issue.setUpdatedAt(Instant.parse("2026-10-01T09:00:00Z"));
        issue = issues.saveAndFlush(issue);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(repository.getProvider());
        pull.setRepository(repository);
        pull.setNumber(32);
        pull.setTitle("A quiet change");
        pull.setBody("Nothing happens on this pull request after its review starts.");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(author);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/32");
        pull.setCreatedAt(Instant.parse("2026-10-01T10:00:00Z"));
        pull.setUpdatedAt(Instant.parse("2026-10-01T10:00:00Z"));
        pull.setHeadRefOid("e".repeat(40));
        pull.setBaseRefOid("f".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        pull.replaceClosingIssues(Set.of(issue));
        pull = pulls.saveAndFlush(pull);
        remark(pull, author, "A general remark", "2026-10-01T11:00:00Z");
        return pull;
    }

    private void remark(PullRequest pull, User author, String body, String at) {
        jdbc.update(
                """
                INSERT INTO issue_comment(native_id,provider_id,issue_id,body,html_url,author_association,created_at,author_id)
                VALUES (?,?,?,?,?,'MEMBER',?,?)
                """,
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                pull.getProvider().getId(),
                pull.getId(),
                body,
                pull.getHtmlUrl() + "#" + UUID.randomUUID(),
                Timestamp.from(Instant.parse(at)),
                author.getId());
    }

    private Practice practice(Workspace workspace, Reads reads) {
        return practice(
                workspace,
                "states-its-change",
                AboutRole.AUTHOR,
                reads == Reads.NOT_THE_RECORD
                        ? List.of(
                                new PracticeReadSource(
                                        new SourceKind("scm.general-review-comments"), ReadNeed.REQUIRED),
                                new PracticeReadSource(new SourceKind("scm.pull-request.diff"), ReadNeed.OPTIONAL),
                                new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.OPTIONAL))
                        : List.of(
                                new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED),
                                new PracticeReadSource(
                                        new SourceKind("scm.general-review-comments"), ReadNeed.OPTIONAL),
                                new PracticeReadSource(new SourceKind("scm.review-threads"), ReadNeed.OPTIONAL),
                                new PracticeReadSource(new SourceKind("scm.linked-work-items"), ReadNeed.OPTIONAL),
                                new PracticeReadSource(PullRequestChecksRecord.KIND, ReadNeed.OPTIONAL)));
    }

    private Practice practice(Workspace workspace, String slug, AboutRole about, List<PracticeReadSource> reads) {
        return practice(workspace, slug, about, reads, null);
    }

    private Practice practice(
            Workspace workspace,
            String slug,
            AboutRole about,
            List<PracticeReadSource> reads,
            @Nullable AppliesWhen appliesWhen) {
        var kind = new ItemKind("scm.pull_request");
        var base = DefinitionTestFixtures.definition(
                kind, "States its change", "# The title states the change\n\nThe title names what changes.", null);
        var definition = new PracticeDefinition(
                base.name(),
                base.reviewOn(),
                appliesWhen,
                about,
                new PracticeReads(ClasspathSourceCatalogRegistry.CURRENT_VERSION.value(), reads),
                base.reviewedBy(),
                base.criteria(),
                base.script(),
                base.scan(),
                base.lineNotes(),
                base.guidance());
        Practice practice = practiceVersions.create(
                new PracticeVersionService.NewPractice(workspace.getId(), slug, kind, definition, null, 0, null));
        practice.setFeedbackMode(FeedbackMode.OFF);
        return practices.saveAndFlush(practice);
    }
}
