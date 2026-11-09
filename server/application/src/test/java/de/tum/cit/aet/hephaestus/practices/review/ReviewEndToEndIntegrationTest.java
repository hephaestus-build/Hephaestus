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
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingService;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesIsolationProbe;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesSandboxCache;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.TestSocketUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * One review of a real pull request, from its request to its admitted results, with only the container and the
 * model's judgement stubbed. The worker claims the job, captures the work, and launches a sandbox whose runtime speaks
 * the runner's protocol over the gateway ({@link RunnerStubSandbox}); the review model is a stub provider behind the
 * real proxy. Between the capture and the first practice start the work may change, as it does on a live install.
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
        RunnerStubSandbox runnerStubSandbox(ObjectMapper mapper) {
            return new RunnerStubSandbox(mapper);
        }
    }

    /** What happens to the work between its capture and the first practice start. */
    enum Between {
        NOTHING,
        THE_DISCUSSION_SYNC_FINISHES,
        A_REMARK_ARRIVES
    }

    @Autowired
    private RunnerStubSandbox sandbox;

    @Autowired
    private SandboxGatewayConnector gateway;

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
     * What the review left: the practice's status and reason, its results, the requests that reached the model
     * provider, and what the runner sent and got.
     */
    private record Reviewed(
            Map<String, @Nullable Object> practice, List<@Nullable String> outcomes, int modelRequests, String calls) {}

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
        var reviewed = review(Between.A_REMARK_ARRIVES);
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
        assertThat(reviewed.modelRequests())
                .as("the proxy reached the provider once")
                .isEqualTo(1);
    }

    private Reviewed review(Between between) {
        User developer = persistUser("review-end-to-end-" + UUID.randomUUID());
        Workspace workspace = workspace(developer);
        Repository repository = repository(workspace);
        PullRequest pull = pullRequest(repository, developer);
        ItemRef holder = itemWriter.upsert(new ItemRegistration(
                workspace.getId(), new ItemKind("scm.repository"), repository.getId(), null, null));
        ItemRef item = itemWriter.upsert(new ItemRegistration(
                workspace.getId(), new ItemKind("scm.pull_request"), pull.getId(), holder.itemId(), null));
        Practice practice = practice(workspace);
        // A review can start while the discussion sync still runs: the mirror has not read the whole discussion yet.
        if (between == Between.THE_DISCUSSION_SYNC_FINISHES)
            jdbc.update("UPDATE issue SET discussion_read_whole=FALSE WHERE id=?", pull.getId());
        sandbox.prepare("http://127.0.0.1:" + gateway.port(), () -> {
            switch (between) {
                case NOTHING -> {}
                // The sync finishes and finds nothing new: the work did not change.
                case THE_DISCUSSION_SYNC_FINISHES ->
                    jdbc.update("UPDATE issue SET discussion_read_whole=TRUE WHERE id=?", pull.getId());
                case A_REMARK_ARRIVES -> remark(pull, developer, "A later remark", "2026-10-01T12:00:00Z");
            }
        });
        int providerRequests = PROVIDER.getRequestCount();

        var pinned = itemSources.stream()
                .filter(source -> source.supports(item))
                .findFirst()
                .orElseThrow()
                .current(item)
                .orElseThrow();
        UUID reviewId = reviews.request(new ReviewRequest(
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

        // The worker ends the run; the server's completion, which its scheduler runs and the test profile does not,
        // then settles each practice of the review.
        try {
            await().atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> {
                        completion.tick();
                        return !jdbc.queryForList(
                                        "SELECT status FROM review_practice WHERE review_id = ?"
                                                + " AND status IN ('DONE', 'WITHHELD', 'FAILED')",
                                        String.class,
                                        reviewId)
                                .isEmpty();
                    });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError("The review did not settle: " + state(reviewId) + " " + sandbox.calls, timeout);
        }
        return new Reviewed(
                jdbc.queryForMap(
                        "SELECT status, reason FROM review_practice WHERE review_id = ? AND practice_id = ?",
                        reviewId,
                        practice.getId()),
                jdbc.queryForList(
                        "SELECT outcome FROM result WHERE review_id = ? AND practice_id = ?",
                        String.class,
                        reviewId,
                        practice.getId()),
                PROVIDER.getRequestCount() - providerRequests,
                sandbox.calls.toString());
    }

    private String state(UUID reviewId) {
        return jdbc.queryForList("""
                        SELECT j.status, j.processing_status, j.exit_code, j.error_message, r.state, r.stop_reason,
                            v.status AS review_status, v.stop_reason AS review_stop_reason
                        FROM review_job j JOIN agent_run r ON r.id = j.run_id JOIN review v ON v.id = j.review_id
                        WHERE j.review_id = ?
                        """, reviewId).toString();
    }

    private Workspace workspace(User developer) {
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
        connectActivitySource(workspace, ensureGitHubProvider());
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

    private Repository repository(Workspace workspace) {
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(ensureGitHubProvider());
        repository.setName("quiet");
        repository.setNameWithOwner(workspace.getAccountLogin() + "/quiet");
        repository.setHtmlUrl("https://github.com/" + workspace.getAccountLogin() + "/quiet");
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

    private Practice practice(Workspace workspace) {
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
                        List.of(
                                new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED),
                                new PracticeReadSource(
                                        new SourceKind("scm.general-review-comments"), ReadNeed.OPTIONAL),
                                new PracticeReadSource(new SourceKind("scm.review-threads"), ReadNeed.OPTIONAL),
                                new PracticeReadSource(new SourceKind("scm.linked-work-items"), ReadNeed.OPTIONAL))),
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
