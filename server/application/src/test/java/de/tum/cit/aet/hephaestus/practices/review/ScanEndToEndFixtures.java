package de.tum.cit.aet.hephaestus.practices.review;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxDispatcher;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.scan.ScanBudget;
import de.tum.cit.aet.hephaestus.agent.scan.ScanDriver;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubScmTokenSource;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeGuidance;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.ScanDefinition;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanMembershipQueries;
import de.tum.cit.aet.hephaestus.practices.review.setup.SetupReviewRequests;
import de.tum.cit.aet.hephaestus.practices.scan.ScanExportService;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportItemsService;
import de.tum.cit.aet.hephaestus.practices.scan.ScanRequestService;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportProblemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.StartScanRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.practices.spi.SetupReviewRequest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.awaitility.core.ConditionTimeoutException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.util.FileUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.TestSocketUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives a repository scan from its request to its closed review, its report and its SARIF, with test doubles at two
 * boundaries only. The agent runtime is {@link ReviewSandboxStub}, which judges each file as {@link
 * ReviewSandboxStub#judgeFiles} says, behind the real gateway and a stub model provider. The SCM provider is a local
 * Git upstream: the mirror fetches from it instead of the provider's URL, with a stub credential. Everything between
 * them is the server's own path: the request, the pin, the planner, the scan driver, admission and the report.
 */
// The agent runtime without a cluster, as AgentRuntimeWithoutCluster builds it; the sandbox is the runner stub.
@MockitoBean(name = "sandboxKubernetesClient", types = KubernetesClient.class, enforceOverride = true)
// Its worker claims every queued review job of the shared database, so its context must not outlive the class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({AgentRuntimeTestSupport.class, AgentRuntimeTestSupport.Sandbox.class})
abstract class ScanEndToEndFixtures extends AbstractWorkspaceIntegrationTest {

    private static final int GATEWAY_PORT = TestSocketUtils.findAvailableTcpPort();
    private static final Path ROOT = temporaryRoot();
    private static final ItemKind REPOSITORY = new ItemKind("scm.repository");

    /** The local upstream of each repository that a test created, by its id. */
    private final Map<Long, String> upstreams = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void agentJobs(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.agent.enabled", () -> "true");
        registry.add("hephaestus.sandbox.gateway.port", () -> GATEWAY_PORT);
        registry.add("hephaestus.agent.sandbox.gateway-url", () -> "http://127.0.0.1:" + GATEWAY_PORT);
        registry.add("hephaestus.sandbox.kubernetes.namespace", () -> "scan-end-to-end");
        registry.add("hephaestus.llm.egress.allow-loopback", () -> "true");
        // A scan pins and captures the repository from its mirror.
        registry.add("hephaestus.git.enabled", () -> "true");
        registry.add("hephaestus.fabric.root", () -> ROOT.resolve("fabric").toString());
    }

    /** A repository that a workspace monitors, on a local upstream at {@code source}, and the admin who scans it. */
    protected record Upstream(Workspace workspace, User admin, ItemRef repository, Path source, String commit) {}

    /** A scan that {@link ScanRequestService} started. */
    protected record Scan(long workspaceId, UUID id) {}

    @Autowired
    protected ReviewSandboxStub sandbox;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper mapper;

    @MockitoSpyBean
    private GitRepositoryManager mirrors;

    @MockitoSpyBean
    private GitHubScmTokenSource tokens;

    @Autowired
    private ScanRequestService scans;

    @Autowired
    private SetupReviewRequests setupReviews;

    @Autowired
    private ScanReportItemsService reports;

    @Autowired
    private ScanExportService exports;

    @Autowired
    private ScanDriver driver;

    @Autowired
    private SandboxDispatcher dispatcher;

    @Autowired
    private ReviewJobCompletionService completion;

    @Autowired
    private AgentRuntimeTestSupport runtime;

    @Autowired
    private ScanBudget budget;

    @Autowired
    private SandboxGatewayProperties gateway;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ItemIndexService itemWriter;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private PracticeRepository practices;

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("scan-end-to-end");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @AfterAll
    static void removeUpstreams() throws IOException {
        FileUtils.delete(ROOT.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
    }

    /**
     * The mirror of a repository that a test created fetches from its local upstream, with a stub credential. The stub
     * model reports its default usage again.
     */
    @BeforeEach
    void fetchFromTheLocalUpstream() {
        runtime.reportPromptTokens(AgentRuntimeTestSupport.PROMPT_TOKENS);
        doReturn(Optional.of("scan-end-to-end-token")).when(tokens).accessToken(anyLong());
        doAnswer(call -> {
                    String upstream = upstream(call.getArgument(0), call.getArgument(1));
                    if (upstream == null) return call.callRealMethod();
                    mirrors.ensureRepository(call.getArgument(0), upstream, call.getArgument(2));
                    return null;
                })
                .when(mirrors)
                .ensureRepository(any(), anyString(), any());
        doAnswer(call -> {
                    String upstream = upstream(call.getArgument(0), call.getArgument(1));
                    if (upstream == null) return call.callRealMethod();
                    mirrors.ensureRepository(call.getArgument(0), upstream, call.getArgument(2), call.getArgument(3));
                    return null;
                })
                .when(mirrors)
                .ensureRepository(any(), anyString(), any(), any());
    }

    @AfterEach
    void clearContext() {
        WorkspaceContextHolder.clearContext();
    }

    /** The upstream to fetch instead, or null when the call already fetches it or names no repository of a test. */
    private @Nullable String upstream(RepositoryKey key, String cloneUrl) {
        String upstream = upstreams.get(key.repositoryId());
        return upstream == null || upstream.equals(cloneUrl) ? null : upstream;
    }

    /**
     * A workspace with a priced review model, monitoring a GitHub repository whose default branch holds {@code files}
     * (path to content) at one commit.
     */
    protected Upstream upstream(Map<String, String> files) throws Exception {
        var github = ensureGitHubProvider();
        String slug = "scan-end-to-end-" + System.nanoTime();
        User admin = persistUser(slug + "-admin");
        Workspace workspace = createWorkspace(slug, "Scan end to end", slug + "-org", AccountType.ORG, admin);
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaces.saveAndFlush(workspace);
        ensureMembership(workspace, admin, Membership.WorkspaceRole.ADMIN);
        connectActivitySource(workspace, github);
        runtime.bindReviewModel(workspace);

        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(github);
        repository.setName("scanned");
        repository.setNameWithOwner(workspace.getAccountLogin() + "/scanned");
        repository.setHtmlUrl(github.getServerUrl() + "/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId()));

        Path source = Files.createDirectories(ROOT.resolve("upstream-" + repository.getId()));
        String commit;
        try (var git = Git.init()
                .setInitialBranch("main")
                .setDirectory(source.toFile())
                .call()) {
            GitTestFixtures.disableSigning(git.getRepository());
            commit = commit(git, files);
        }
        upstreams.put(repository.getId(), source.toUri().toString());
        ItemRef item =
                itemWriter.upsert(new ItemRegistration(workspace.getId(), REPOSITORY, repository.getId(), null, null));
        return new Upstream(workspace, admin, item, source, commit);
    }

    /** Pushes {@code files} (path to content) to the default branch as one commit, which a later scan pins. */
    protected String push(Upstream upstream, Map<String, String> files) throws Exception {
        try (var git = Git.open(upstream.source().toFile())) {
            GitTestFixtures.disableSigning(git.getRepository());
            return commit(git, files);
        }
    }

    private static String commit(Git git, Map<String, String> files) throws Exception {
        Path source = git.getRepository().getWorkTree().toPath();
        for (var file : files.entrySet()) {
            Path path = source.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        git.add().addFilepattern(".").call();
        return git.commit()
                .setSign(false)
                .setMessage("The scanned files")
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
                .name();
    }

    /** A file-scope practice that scans the files that {@code include} matches and {@code exclude} does not. */
    protected Practice practice(Workspace workspace, String slug, List<String> include, List<String> exclude) {
        var reads = new PracticeReads(
                "3.0.0", List.of(new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.COMPLETE)));
        var definition = new PracticeDefinition(
                "Keep each file clear",
                new ReviewOn(List.of(), Map.of()),
                null,
                null,
                reads,
                new ReviewedBy(ReviewedByKind.HEPHAESTUS, null, List.of()),
                "Each selected file clearly expresses its purpose.",
                null,
                new ScanDefinition(new ItemKind("scm.file"), include, exclude, List.of("**/*"), reads),
                false,
                new PracticeGuidance(null, null, null, null, null));
        Practice practice = practiceVersions.create(
                new PracticeVersionService.NewPractice(workspace.getId(), slug, REPOSITORY, definition, null, 0, null));
        practice.setFeedbackMode(FeedbackMode.OFF);
        return practices.saveAndFlush(practice);
    }

    /**
     * Requests a scan of the default branch through today's {@link ScanRequestService}, as an admin does: of the
     * {@code selected} practices, or of every scan practice when none is selected.
     */
    protected Scan scan(Upstream upstream, boolean reuse, Practice... selected) {
        return scan(upstream, reuse, new BigDecimal("10"), selected);
    }

    /** As {@link #scan(Upstream, boolean, Practice...)}, with the budget {@code budgetUsd}. */
    protected Scan scan(Upstream upstream, boolean reuse, BigDecimal budgetUsd, Practice... selected) {
        var summary = scans.start(
                upstream.workspace().getId(),
                accountId(upstream.admin()),
                UUID.randomUUID(),
                new StartScanRequestDTO(
                        Long.toString(upstream.repository().itemId()),
                        null,
                        null,
                        selected.length == 0
                                ? null
                                : Arrays.stream(selected).map(Practice::getSlug).toList(),
                        budgetUsd,
                        reuse));
        return new Scan(upstream.workspace().getId(), summary.id());
    }

    /**
     * A review setup of {@code workspace} for items of {@code itemKind}, read by {@code audience}, that selects {@code
     * selected}. The rows are written with SQL, because nothing writes setups yet.
     */
    protected UUID setup(
            Workspace workspace,
            String itemKind,
            String audience,
            @Nullable BigDecimal budgetUsd,
            Practice... selected) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review_setup(id, workspace_id, name, item_kind, audience, evidence_slots, budget_usd,"
                        + " enabled, created_at) VALUES (?, ?, 'Repository check', ?, ?, '[]'::jsonb, ?, true, now())",
                id,
                workspace.getId(),
                itemKind,
                audience,
                budgetUsd);
        for (Practice practice : selected)
            jdbc.update(
                    "INSERT INTO review_setup_practice(setup_id, practice_id, workspace_id) VALUES (?, ?, ?)",
                    id,
                    practice.getId(),
                    workspace.getId());
        return id;
    }

    /** Starts a review through {@link SetupReviewRequests}, as the setup's request route and scheduler will. */
    protected Scan review(SetupReviewRequest request) {
        return new Scan(request.workspaceId(), setupReviews.request(request));
    }

    /**
     * A budget that admits one file of one practice and no more: what the scan driver reserves before it queues a file
     * ({@link ScanBudget#permits}), rounded up to the 4 decimals a budget holds. Each stub model call then reports
     * 1,000 prompt tokens, which cost 0.0001 at the review price, so the first file spends more than the rounding left.
     */
    protected BigDecimal oneFileBudget() {
        runtime.reportPromptTokens(1_000);
        long calls = budget.callCap(1);
        long input = Math.min(AgentRuntimeTestSupport.CONTEXT_WINDOW, gateway.maxRequestBytes());
        return ScanBudget.cost(
                        AgentRuntimeTestSupport.PRICE, calls * input, calls * AgentRuntimeTestSupport.MAX_OUTPUT_TOKENS)
                .add(BigDecimal.valueOf(calls).movePointLeft(6))
                .setScale(4, RoundingMode.UP);
    }

    /**
     * Runs each pass that a scheduler runs outside the test profile until the scan closes: the driver enumerates and
     * queues the next file, the dispatcher starts a sandbox, the sandbox measures, and completion admits its results.
     * Each file's job also queues a composition on the sandbox's subject, which {@link
     * AgentRuntimeTestSupport#composeQueued} runs first.
     *
     * @return the closed review's status and stop reason
     */
    protected Map<String, @Nullable Object> awaitClosed(Scan scan) {
        try {
            await().atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> {
                        driver.tick();
                        // A sandbox whose scan just closed would wait out its pull of a next run (AgentNextWaits).
                        if (closed(scan)) return true;
                        dispatcher.dispatch();
                        runtime.composeQueued(scan.id());
                        sandbox.drive(scan.id());
                        runtime.composeQueued(scan.id());
                        completion.tick();
                        return closed(scan);
                    });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError("The scan did not close: " + state(scan) + " " + sandbox.calls, timeout);
        }
        return status(scan);
    }

    private boolean closed(Scan scan) {
        return !List.of("QUEUED", "RUNNING").contains(status(scan).get("status"));
    }

    private Map<String, @Nullable Object> status(Scan scan) {
        return jdbc.queryForMap(
                "SELECT status, stop_reason FROM review WHERE id = ? AND workspace_id = ?",
                scan.id(),
                scan.workspaceId());
    }

    private String state(Scan scan) {
        return jdbc.queryForList(
                        "SELECT status, processing_status, exit_code, error_message FROM review_job"
                                + " WHERE review_id = ? ORDER BY id",
                        scan.id())
                .toString();
    }

    /** The review that the closed scan's frozen report compares with, or null when it has no baseline. */
    protected @Nullable UUID baseline(Scan scan) {
        String baseline = jdbc.queryForObject(
                "SELECT report->>'baselineScanId' FROM review WHERE id = ? AND workspace_id = ?",
                String.class,
                scan.id(),
                scan.workspaceId());
        return baseline == null ? null : UUID.fromString(baseline);
    }

    /** The report's items, in report order. */
    protected List<ScanReportItemDTO> items(Scan scan) {
        return reports.items(
                        scan.workspaceId(),
                        scan.id(),
                        null,
                        200,
                        new ScanMembershipQueries.ItemFilter(null, null, null, null, null))
                .content();
    }

    /** The report's open problems, in report order. */
    protected List<ScanReportProblemDTO> problems(Scan scan) {
        return reports.problems(
                        scan.workspaceId(),
                        scan.id(),
                        null,
                        200,
                        new ScanMembershipQueries.ProblemFilter(null, null, null, null, true))
                .content();
    }

    /**
     * The scan's SARIF with exactly the ids that differ between two reviews of the same commit replaced: the scan, each
     * problem and result, the baseline, and each baseline state. Two scans whose findings agree read the same.
     */
    protected String sarif(Scan scan) {
        var sarif = (ObjectNode) mapper.readTree(exports.sarif(scan.workspaceId(), scan.id()));
        for (var run : sarif.path("runs")) {
            var facts = run.path("properties").path("hephaestus");
            if (facts instanceof ObjectNode hephaestus && hephaestus.has("scanId")) hephaestus.put("scanId", "scanId");
            if (run instanceof ObjectNode node && node.has("baselineGuid")) node.put("baselineGuid", "baselineGuid");
            for (var result : run.path("results")) {
                if (result instanceof ObjectNode node && node.has("baselineState"))
                    node.put("baselineState", "baselineState");
                if (result.path("properties") instanceof ObjectNode properties) {
                    if (properties.has("problemId")) properties.put("problemId", "problemId");
                    if (properties.has("resultId")) properties.put("resultId", "resultId");
                }
            }
        }
        return mapper.writeValueAsString(sarif);
    }
}
