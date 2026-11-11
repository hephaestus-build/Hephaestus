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
import de.tum.cit.aet.hephaestus.agent.composer.CompositionSessions;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.composer.ReviewComposeSandboxKind;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingService;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxDispatcher;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesIsolationProbe;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesSandboxCache;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 */
// The worker role without a cluster, as WorkerGraphWithoutCluster builds it; the sandbox is the runner stub.
@MockitoBean(name = "sandboxKubernetesClient", types = KubernetesClient.class, enforceOverride = true)
@MockitoBean(
        types = {KubernetesSandboxCache.class, KubernetesIsolationProbe.class},
        enforceOverride = true)
// Its worker claims every queued review job of the shared database, so its context must not outlive the class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewEndToEndIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final MockWebServer PROVIDER = new MockWebServer();
    private static final int GATEWAY_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void workerAndAgent(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.runtime.worker.enabled", () -> "true");
        registry.add("hephaestus.agent.enabled", () -> "true");
        // The Kubernetes worker needs a fixed gateway port.
        registry.add("hephaestus.sandbox.gateway.port", () -> GATEWAY_PORT);
        // A review's sandbox reaches the gateway at this address, as the claim passes it.
        registry.add("hephaestus.agent.sandbox.gateway-url", () -> "http://127.0.0.1:" + GATEWAY_PORT);
        registry.add("hephaestus.sandbox.kubernetes.namespace", () -> "review-end-to-end");
        registry.add("hephaestus.sandbox.kubernetes.pod-name", () -> "review-end-to-end");
        registry.add("hephaestus.sandbox.kubernetes.pod-ip", () -> "127.0.0.1");
        registry.add("hephaestus.agent.image.pull-policy", () -> "NEVER");
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
    private SandboxDispatcher dispatcher;

    @Autowired
    private ReviewComposeSandboxKind composeKind;

    @Autowired
    private CompositionSessions compositionSessions;

    @Autowired
    private AgentRuns agentRuns;

    @Autowired
    private InstanceLlmSettingsRepository instanceLlmSettings;

    @Autowired
    private WorkspaceLlmConnectionService connections;

    @Autowired
    private WorkspaceLlmModelService models;

    @Autowired
    private AgentBindingService bindings;

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
            // The lease and the claim commit together, as the gateway's next does.
            var run = transactions.execute(status -> agentRuns
                    .claimSubject(workspaceId, subject, Set.of(AgentRunKind.REVIEW_COMPOSE))
                    .map(lease -> new SandboxRun(
                            new AgentSandboxes.LiveSandbox(
                                    UUID.randomUUID(), workspaceId, subject, AgentSandboxKind.REVIEW),
                            lease.fence(),
                            AgentRunKind.REVIEW_COMPOSE,
                            lease.attempt()))
                    .filter(composeKind::claimed)
                    .orElse(null));
            if (run == null) continue;
            var composing = composeKind.prepare(run);
            compositionSessions.withholdOpen(composing.job(), composing.identity(), CompositionStop.NO_DECISION);
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
                .containsEntry("reason", "REPLACED");
        assertThat(reviewed.outcomes()).isEmpty();
        assertThat(reviewed.modelRequests()).isZero();
        assertThat(reviewed.review())
                .as("the review says the work changed, not that its runtime fell short")
                .containsEntry("status", "PARTIAL")
                .containsEntry("stop_reason", "REPLACED");
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

    private Reviewed review(IdentityProviderType type, Asked asked, Between between) {
        return review(type, asked, between, Reads.THE_WORK);
    }

    private Reviewed review(IdentityProviderType type, Asked asked, Between between, Reads reads) {
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

        UUID reviewId = asked == Asked.BY_EVENT
                ? opened(workspace, item, pull, type, developer)
                : requested(workspace, item, practice, developer);

        // The dispatch starts the review's sandbox, the sandbox measures and ends its run, and the server's completion
        // settles each practice of the review: each pass here does what a scheduler does outside the test profile.
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
                        .statesFor(
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

    private UUID requested(Workspace workspace, ItemRef item, Practice practice, User developer) {
        var pinned = itemSources.stream()
                .filter(source -> source.supports(item))
                .findFirst()
                .orElseThrow()
                .current(item)
                .orElseThrow();
        return reviews.request(new ReviewRequest(
                workspace.getId(),
                item.itemId(),
                ReviewKind.ITEM,
                pinned.version(),
                ReviewOrigin.REQUEST,
                null,
                accountId(developer),
                null,
                null,
                null,
                null,
                List.of(practice.getId()),
                false,
                Set.of(),
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
                                null,
                                null,
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
                AgentPurpose.PRACTICE_REVIEW,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(null, model, null, true));
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
        var kind = new ItemKind("scm.pull_request");
        var base = DefinitionTestFixtures.definition(
                kind, "States its change", "# The title states the change\n\nThe title names what changes.", null);
        var definition = new PracticeDefinition(
                base.name(),
                base.reviewOn(),
                base.appliesWhen(),
                base.about(),
                new PracticeReads(
                        ClasspathSourceCatalogRegistry.CURRENT_VERSION.value(),
                        reads == Reads.NOT_THE_RECORD
                                ? List.of(
                                        new PracticeReadSource(
                                                new SourceKind("scm.general-review-comments"), ReadNeed.REQUIRED),
                                        new PracticeReadSource(
                                                new SourceKind("scm.pull-request.diff"), ReadNeed.OPTIONAL),
                                        new PracticeReadSource(
                                                new SourceKind("scm.repository.tree"), ReadNeed.OPTIONAL))
                                : List.of(
                                        new PracticeReadSource(
                                                new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED),
                                        new PracticeReadSource(
                                                new SourceKind("scm.general-review-comments"), ReadNeed.OPTIONAL),
                                        new PracticeReadSource(new SourceKind("scm.review-threads"), ReadNeed.OPTIONAL),
                                        new PracticeReadSource(
                                                new SourceKind("scm.linked-work-items"), ReadNeed.OPTIONAL),
                                        new PracticeReadSource(PullRequestChecksRecord.KIND, ReadNeed.OPTIONAL))),
                base.reviewedBy(),
                base.criteria(),
                base.script(),
                base.scan(),
                base.lineNotes(),
                base.guidance());
        Practice practice = practiceVersions.create(new PracticeVersionService.NewPractice(
                workspace, "states-its-change", kind, definition, null, 0, null));
        practice.setFeedbackMode(FeedbackMode.OFF);
        return practices.saveAndFlush(practice);
    }
}
