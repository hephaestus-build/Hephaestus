package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermitsTestFixtures;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewJobPolicyIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewAttemptUsageRepository attemptUsageRows;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelPriceRepository prices;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private LlmAdmissionService admission;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private ReviewMemberAiPolicy memberAiPolicy;

    @Autowired
    private ReviewJobAssignments frozenAssignments;

    private final ReviewMemberAiPolicy policy = mock(ReviewMemberAiPolicy.class);
    private Workspace workspace;
    private ReviewJobExecutor executor;
    private final ReviewMethodVerifier methods = mock(ReviewMethodVerifier.class);

    private static LlmCallPermits unpooledCalls() {
        var capacity = mock(LlmCallPermits.class);
        when(capacity.poolFor(any())).thenReturn(null);
        return capacity;
    }

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("tier-" + UUID.randomUUID()));
        doReturn(true).when(policy).permitsReview(any());
        when(policy.binding(any())).thenAnswer(invocation -> {
            ReviewJob selected = invocation.getArgument(0);
            var snapshot = ConfigSnapshot.fromJson(selected.getConfigSnapshot(), mapper);
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                    selected.getWorkspaceId(),
                    AgentPurpose.PRACTICE_REVIEW,
                    Objects.requireNonNull(snapshot.model().dataHandlingTier()));
        });
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var metrics = new SimpleMeterRegistry();
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        // Hold sandbox work outside this test: admission, locks and RUNNING rows are real.
        executor = new ReviewJobExecutor(
                properties,
                jobs,
                mock(DefaultReviewExecutionHandler.class),
                mock(PracticePiAdapter.class),
                mock(AttemptTokenIssuer.class),
                mock(SandboxManager.class),
                SandboxTestLimits.PROPERTIES,
                mock(AsyncTaskExecutor.class),
                transactions,
                mapper,
                metrics,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                new ReviewAttemptUsageService(jobs, attemptUsageRows, mock(LlmUsageRecorder.class)),
                mock(PracticeScriptRunRecorder.class),
                admission,
                Optional.empty(),
                WorkerPropertiesFixtures.minimal("1", "1"),
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        methods,
                        assignmentSnapshots,
                        transactions,
                        mapper,
                        metrics,
                        budgets,
                        admission,
                        new ReviewJobTelemetry(metrics, Tracer.NOOP),
                        Optional.empty(),
                        unpooledCalls()),
                new ReviewJobRecovery(
                        jobs,
                        properties,
                        mapper,
                        new ReviewAttemptUsageService(jobs, attemptUsageRows, mock(LlmUsageRecorder.class)),
                        Optional.empty()));
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldScanAnotherEndpointBeforeSleepingWhenTheFirstEndpointCannotClaim() {
        var blockedBinding = binding(DataHandlingTier.IN_HOUSE);
        long blockedPool = endpointPool(blockedBinding, true);
        var openBinding = binding(DataHandlingTier.CLOUD);
        long openPool = endpointPool(openBinding, false);
        var blockedJob = queued(blockedBinding);
        var runnable = queued(openBinding);
        jdbc.update("UPDATE review_job SET available_at=now()-interval '2 minutes' WHERE id=?", blockedJob.getId());
        jdbc.update("UPDATE review_job SET available_at=now()-interval '1 minute' WHERE id=?", runnable.getId());
        var slowPoll = new AgentProperties(
                true,
                Duration.ofSeconds(30),
                1,
                properties.maxRetries(),
                properties.heartbeatInterval(),
                properties.payloadRetention(),
                properties.rowRetention(),
                properties.maxConcurrentCompositions());
        var sandboxes = mock(SandboxManager.class);
        when(sandboxes.ready()).thenReturn(true);
        when(sandboxes.roomFor(any())).thenReturn(1);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        var polling = pooledExecutor(slowPoll, capacity, sandboxes);
        try {
            polling.start();
            // This must happen within one poll; an idle poll sleeps at least 24 seconds with jitter.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(jobs.findById(runnable.getId())
                                    .orElseThrow()
                                    .getStatus())
                            .isEqualTo(ReviewJobStatus.RUNNING));
            var stillQueued = jobs.findById(blockedJob.getId()).orElseThrow();
            assertThat(stillQueued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(stillQueued.getWorkerId()).isNull();
            assertThat(stillQueued.getExecutionStartedAt()).isNull();
            assertThat(blockedPool).isNotEqualTo(openPool);
        } finally {
            polling.stopAcceptingNewJobs();
            var unused = capacity.acquire(
                    openPool,
                    LlmCallClass.REQUEST,
                    new LlmCallPermits.Caller(workspace.getId(), runnable.getId(), runnable.getAttempt(), null),
                    () -> false,
                    Instant.now().plusMillis(100));
            if (unused != null) unused.close();
        }
    }

    @Test
    void shouldAdmitAnExistingFairClaimWhileFreshReviewsKeepArriving() {
        var unrelated = Set.copyOf(jobs.findAll().stream().map(ReviewJob::getId).toList());
        var selected = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(selected, true);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        var oldest = queued(selected);
        Boolean initial = transactions.execute(status -> capacity.reserve(
                        poolId,
                        LlmCallClass.REQUEST,
                        new LlmCallPermits.Caller(workspace.getId(), oldest.getId(), oldest.getAttempt(), null))
                .granted());
        assertThat(initial).isFalse();
        jdbc.update("UPDATE llm_call_pool SET no_call_before=NULL WHERE id=?", poolId);
        var polling = pooledExecutor(properties, capacity, mock(SandboxManager.class));
        int granted = 0;
        try {
            for (int round = 0; round < 6; round++) {
                var fresh = queued(selected);
                UUID candidate =
                        jobs.findQueuedIdsStartedFirst(1, Set.of(), unrelated).getFirst();
                assertThat(candidate).isEqualTo(fresh.getId());
                if (polling.processJob(candidate) == ReviewJobExecutor.StartOutcome.STARTED) {
                    granted++;
                    var stillQueued = jobs.findById(fresh.getId()).orElseThrow();
                    assertThat(stillQueued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
                    assertThat(stillQueued.getWorkerId()).isNull();
                    assertThat(stillQueued.getExecutionStartedAt()).isNull();
                    break;
                }
            }
            assertThat(granted)
                    .as("an idle endpoint must admit its waiting claim despite fresh arrivals on each poll")
                    .isPositive();
            assertThat(jobs.findById(oldest.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        } finally {
            var unused = capacity.acquire(
                    poolId,
                    LlmCallClass.REQUEST,
                    new LlmCallPermits.Caller(workspace.getId(), oldest.getId(), oldest.getAttempt(), null),
                    () -> false,
                    Instant.now().plusMillis(100));
            if (unused != null) unused.close();
        }
    }

    private long endpointPool(WorkspaceAgentBinding binding, boolean cooling) {
        var pool = new LlmCallPool();
        pool.setSlug("endpoint-" + UUID.randomUUID());
        pool.setDisplayName("Endpoint scan fixture");
        pool.setMaxConcurrentCalls(2);
        if (cooling) pool.setNoCallBefore(Instant.now().plusSeconds(60));
        pool = context.getBean(LlmCallPoolRepository.class).saveAndFlush(pool);
        var model = Objects.requireNonNull(binding.getInstanceModel());
        model.setCallPoolId(pool.getId());
        models.saveAndFlush(model);
        return pool.getId();
    }

    @Test
    void shouldWithdrawAWaitingClaimWhoseReadinessMovesIntoTheFuture() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(selected, true);
        var waiting = queued(selected);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        transactions.executeWithoutResult(status -> capacity.reserve(
                poolId,
                LlmCallClass.REQUEST,
                new LlmCallPermits.Caller(workspace.getId(), waiting.getId(), waiting.getAttempt(), null)));
        waiting.setAvailableAt(Instant.now().plusSeconds(60));
        jobs.saveAndFlush(waiting);
        var runnable = queued(selected);
        jdbc.update("UPDATE llm_call_pool SET no_call_before=NULL WHERE id=?", poolId);
        var polling = pooledExecutor(properties, capacity, mock(SandboxManager.class));
        try {
            assertThat(polling.processJob(runnable.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
            assertThat(jobs.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(jobs.findById(runnable.getId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewJobStatus.RUNNING);
        } finally {
            var unused = capacity.acquire(
                    poolId,
                    LlmCallClass.REQUEST,
                    new LlmCallPermits.Caller(workspace.getId(), runnable.getId(), runnable.getAttempt(), null),
                    () -> false,
                    Instant.now().plusMillis(100));
            if (unused != null) unused.close();
        }
    }

    @Test
    void shouldWithdrawAnUnclaimableWinnerSoAnotherBindingCanUseThePool() {
        var capped = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(capped, true);
        var waiting = queued(capped);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        transactions.executeWithoutResult(status -> capacity.reserve(
                poolId,
                LlmCallClass.REQUEST,
                new LlmCallPermits.Caller(workspace.getId(), waiting.getId(), waiting.getAttempt(), null)));
        var running = queued(capped);
        running.setStatus(ReviewJobStatus.RUNNING);
        jobs.saveAndFlush(running);
        var open = binding(DataHandlingTier.CLOUD);
        var model = Objects.requireNonNull(open.getInstanceModel());
        model.setCallPoolId(poolId);
        models.saveAndFlush(model);
        var runnable = queued(open);
        jdbc.update("UPDATE llm_call_pool SET no_call_before=NULL WHERE id=?", poolId);
        var polling = pooledExecutor(properties, capacity, mock(SandboxManager.class));
        try {
            assertThat(polling.processJob(runnable.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
            assertThat(polling.processJob(runnable.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
            assertThat(jobs.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(jobs.findById(runnable.getId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewJobStatus.RUNNING);
        } finally {
            var unused = capacity.acquire(
                    poolId,
                    LlmCallClass.REQUEST,
                    new LlmCallPermits.Caller(workspace.getId(), runnable.getId(), runnable.getAttempt(), null),
                    () -> false,
                    Instant.now().plusMillis(100));
            if (unused != null) unused.close();
        }
    }

    private ReviewJobExecutor pooledExecutor(
            AgentProperties settings, LlmCallPermits capacity, SandboxManager sandboxes) {
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var metrics = new SimpleMeterRegistry();
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var worker = WorkerPropertiesFixtures.minimal("1", "1");
        var usage = new ReviewAttemptUsageService(jobs, attemptUsageRows, mock(LlmUsageRecorder.class));
        return new ReviewJobExecutor(
                settings,
                jobs,
                mock(DefaultReviewExecutionHandler.class),
                mock(PracticePiAdapter.class),
                mock(WorkerJwtIssuer.class),
                sandboxes,
                SandboxTestLimits.PROPERTIES,
                mock(AsyncTaskExecutor.class),
                transactions,
                mapper,
                metrics,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                usage,
                mock(PracticeScriptRunRecorder.class),
                admission,
                Optional.empty(),
                worker,
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        methods,
                        assignments,
                        transactions,
                        mapper,
                        metrics,
                        budgets,
                        admission,
                        new ReviewJobTelemetry(metrics, Tracer.NOOP),
                        Optional.of(worker),
                        capacity),
                new ReviewJobRecovery(jobs, settings, mapper, usage, Optional.empty()));
    }

    @Test
    void shouldGiveAQueuedReviewAFairWorkspaceGrantBeforeBusyProxyCallsRepeat() throws Exception {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        var pool = new LlmCallPool();
        pool.setSlug("queued-claim-" + UUID.randomUUID());
        pool.setDisplayName("Queued claims");
        pool.setMaxConcurrentCalls(1);
        pool = context.getBean(LlmCallPoolRepository.class).saveAndFlush(pool);
        var selectedModel = Objects.requireNonNull(selected.getInstanceModel());
        selectedModel.setCallPoolId(pool.getId());
        models.saveAndFlush(selectedModel);
        var job = queued(selected);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        var busyWorkspace = workspaces.saveAndFlush(TestEntities.activeWorkspace("busy-pool-" + UUID.randomUUID()));
        var runningReview = new ReviewJob();
        RuntimeJobTestData.initialize(runningReview, busyWorkspace.getId());
        runningReview.setStatus(ReviewJobStatus.RUNNING);
        runningReview.setWorkerId("test-worker");
        runningReview.setConfigSnapshot(job.getConfigSnapshot());
        runningReview.setStartedAt(Instant.now());
        runningReview.setLeasedUntil(Instant.now().plusSeconds(60));
        runningReview = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, runningReview));
        var busyCaller = new LlmCallPermits.Caller(busyWorkspace.getId(), runningReview.getId(), 0, null);
        long poolId = pool.getId();
        var busy = Objects.requireNonNull(capacity.acquire(
                poolId,
                LlmCallClass.EVENT,
                busyCaller,
                () -> false,
                Instant.now().plusSeconds(5)));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignments,
                transactions,
                mapper,
                metrics,
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                Optional.of(WorkerPropertiesFixtures.minimal("2", "1")),
                capacity);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var repeated = requests.submit(() -> capacity.acquire(
                    poolId,
                    LlmCallClass.REQUEST,
                    busyCaller,
                    () -> false,
                    Instant.now().plusSeconds(10)));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING'",
                                    Long.class,
                                    poolId))
                            .isEqualTo(1L));
            try {
                assertThat(claims.claimJob(job.getId())).isNotInstanceOf(ReviewJobClaimService.ClaimResult.class);
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
                assertThat(jobs.findById(job.getId()).orElseThrow().getWorkerId())
                        .isNull();
                busy.close();
                assertThat(claims.claimJob(job.getId())).isInstanceOf(ReviewJobClaimService.ClaimResult.class);
                assertThat(repeated.isDone()).isFalse();
            } finally {
                busy.close();
                var reserved = capacity.acquire(
                        poolId,
                        LlmCallClass.REQUEST,
                        new LlmCallPermits.Caller(workspace.getId(), job.getId(), job.getAttempt(), null),
                        () -> false,
                        Instant.now().plusMillis(100));
                if (reserved != null) reserved.close();
                var repeat = repeated.get(12, TimeUnit.SECONDS);
                if (repeat != null) repeat.close();
            }
        }
    }

    @Test
    void shouldAllowIndependentTierSlotsButSerializeClaimsWithinEachSlot() throws Exception {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var cloud = binding(DataHandlingTier.CLOUD);
        var first = queued(inHouse);
        var second = queued(inHouse);
        var start = new CyclicBarrier(2);
        when(policy.permitsReview(any())).thenAnswer(invocation -> {
            start.await(30, TimeUnit.SECONDS);
            return true;
        });
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstClaim = threads.submit(() -> executor.processJob(first.getId()));
            var secondClaim = threads.submit(() -> executor.processJob(second.getId()));
            assertThat(List.of(firstClaim.get(30, TimeUnit.SECONDS), secondClaim.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            ReviewJobExecutor.StartOutcome.STARTED, ReviewJobExecutor.StartOutcome.SKIPPED);
        }
        assertThat(jobs.findAllById(List.of(first.getId(), second.getId())))
                .extracting(ReviewJob::getStatus)
                .containsExactlyInAnyOrder(ReviewJobStatus.RUNNING, ReviewJobStatus.QUEUED);

        doReturn(true).when(policy).permitsReview(any());
        var cloudJob = queued(cloud);
        assertThat(executor.processJob(cloudJob.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        assertThat(jobs.findById(cloudJob.getId()))
                .get()
                .extracting(ReviewJob::getStatus)
                .isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    void shouldLeaseTheAttemptThatAClaimStartsOnTheDatabaseClock() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignmentSnapshots,
                transactions,
                mapper,
                metrics,
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                Optional.empty(),
                unpooledCalls());

        var claim = claims.claimJob(job.getId());

        assertThat(claim).isInstanceOf(ReviewJobClaimService.ClaimResult.class);
        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(claimed.getLeasedUntil())
                .as("the claim leases the attempt for one lease from now")
                .isAfter(Instant.now().plus(AgentProperties.WORKER_LEASE_TTL).minusSeconds(60))
                .as("the claimed entity holds the lease that the row holds")
                .isEqualTo(((ReviewJobClaimService.ClaimResult) claim).job().getLeasedUntil());
    }

    @Test
    void shouldLeaveTheReviewQueuedWhenItsEndpointCannotReserveACall() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var capacity = mock(LlmCallPermits.class);
        when(capacity.poolFor(any())).thenReturn(42L);
        when(capacity.reserve(anyLong(), any(), any())).thenReturn(new LlmCallPermits.ClaimReservation(false, null));
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignmentSnapshots,
                transactions,
                mapper,
                metrics,
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                Optional.empty(),
                capacity);

        var claim = claims.claimJob(job.getId());

        assertThat(claim).hasFieldOrPropertyWithValue("poolId", 42L);
        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.getAttempt()).isEqualTo(job.getAttempt());
        assertThat(queued.getWorkerId()).isNull();
        assertThat(queued.getExecutionStartedAt()).isNull();
    }

    @Test
    void shouldKeepRunningOccupancyWhenTheSlotsModelIsReassigned() {
        var binding = binding(DataHandlingTier.IN_HOUSE);
        var running = queued(binding);
        assertThat(executor.processJob(running.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        var replacement = model(DataHandlingTier.IN_HOUSE);
        binding.setInstanceModel(replacement);
        binding = bindings.saveAndFlush(binding);
        var queued = queued(binding);
        assertThat(executor.processJob(queued.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(queued.getId()))
                .get()
                .extracting(ReviewJob::getStatus)
                .isEqualTo(ReviewJobStatus.QUEUED);
    }

    @Test
    void shouldCountAnUndeclaredModelAgainstOnlyTheUndeclaredSlot() {
        var binding = binding(DataHandlingTier.UNDECLARED);
        var historical = queued(binding);
        var snapshot = (ObjectNode) historical.getConfigSnapshot();
        ((ObjectNode) snapshot.get("model")).remove("dataHandlingTier");
        historical.setConfigSnapshot(snapshot);
        historical.setStatus(ReviewJobStatus.RUNNING);
        jobs.saveAndFlush(historical);

        assertThat(executor.processJob(queued(binding).getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(executor.processJob(
                        queued(binding(DataHandlingTier.IN_HOUSE)).getId()))
                .isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
    }

    @Test
    void shouldRefuseAQueuedJobWhoseSnapshotTierDiffersFromTheBindingResolvedNow() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(inHouse);
        var routedElsewhere = binding(DataHandlingTier.CLOUD);
        doReturn(Optional.of(routedElsewhere)).when(policy).binding(any(ReviewJob.class));

        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldCancelAQueuedJobWhenItsFrozenModelBecomesUnavailable() {
        var slot = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(slot);
        var model = Objects.requireNonNull(slot.getInstanceModel());
        model.setEnabled(false);
        models.saveAndFlush(model);
        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldKeepTheJobQueuedWhenModelAdmissionFailsUnexpectedly() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var failure = new IllegalStateException("Unexpected programming error");
        doThrow(failure).when(methods).require(any(), any(), any());
        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.QUEUED, null);
    }

    @Test
    void shouldClaimAContinuationOnlyToWithholdWhenTheBudgetIsSpent() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // Attempt 0 admitted the results and was handed on to compose them.
        jdbc.update(
                "UPDATE review_job SET attempt=1, admission_attempt=0, admission_digest=? WHERE id=?",
                "a".repeat(64),
                job.getId());
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong()))
                .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.EXHAUSTED));
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignmentSnapshots,
                transactions,
                mapper,
                metrics,
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                Optional.empty(),
                unpooledCalls());

        assertThat(claims.claimJob(job.getId()))
                .isInstanceOfSatisfying(
                        ReviewJobClaimService.ClaimResult.class,
                        claim -> assertThat(claim.withholdOnly()).isEqualTo(CompositionStop.BUDGET));

        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(claimed.getAttempt()).isEqualTo(1);
        assertThat(claimed.getExecutionStartedAt()).isNull();
        assertThat(claimed.getLeasedUntil())
                .as("the claim leases the attempt for one lease from now")
                .isAfter(Instant.now().plus(AgentProperties.WORKER_LEASE_TTL).minusSeconds(60));
    }

    @Test
    void shouldFailAJobWhoseFrozenAssignmentIsMissingOnItsFirstClaimAndNotClaimItAgain() {
        // The fixture pins an assignment that no row holds; the real policy reads it inside the claim transaction.
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                memberAiPolicy,
                methods,
                frozenAssignments,
                transactions,
                mapper,
                metrics,
                mock(LlmBudgetService.class),
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                Optional.empty(),
                unpooledCalls());

        assertThat(claims.claimJob(job.getId()))
                .isInstanceOfSatisfying(
                        ReviewJobClaimService.TerminalClaim.class,
                        terminal -> assertThat(terminal.status()).isEqualTo(ReviewJobStatus.FAILED));

        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isNotBlank();
        assertThat(Objects.requireNonNull(failed.getCapture())
                        .path("preparationFailure")
                        .path("stage")
                        .asString())
                .isEqualTo("INPUTS");
        assertThat(claims.claimJob(job.getId())).isEqualTo(ReviewJobClaimService.ClaimOutcome.ALREADY_CLAIMED);
    }

    @Test
    void shouldServeAMemberWhoAcceptsTheCloudFromAnInHouseBindingWhenItIsTheOnlyOne() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var preferences = mock(MemberAiPreferences.class);
        when(preferences.forDeveloper(workspace.getId(), 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, AiChoice.CLOUD));
        var routing = new MemberAiRoutingAdapter(
                bindings, preferences, resolver, workspaces, mock(PersonProcessingSuppression.class));

        assertThat(routing.binding(workspace.getId(), AgentPurpose.PRACTICE_REVIEW, 20L))
                .get()
                .extracting(WorkspaceAgentBinding::getId)
                .isEqualTo(inHouse.getId());
    }

    private WorkspaceAgentBinding binding(DataHandlingTier tier) {
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setDataHandlingTier(tier);
        binding.setInstanceModel(model(tier));
        binding.setEnabled(true);
        binding.setMaxConcurrentJobs(1);
        return bindings.saveAndFlush(binding);
    }

    private LlmModel model(DataHandlingTier tier) {
        String slug = "tier-" + UUID.randomUUID();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = LlmCatalogTestFixtures.model(connection, slug, slug);
        model.setDataHandling(
                switch (tier) {
                    case IN_HOUSE -> DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null);
                    case CLOUD -> DataHandlingFacts.of(LlmDataOperator.PROVIDER, null);
                    case UNDECLARED -> new DataHandlingFacts();
                });
        model = models.save(model);
        var price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setNote("No charge for the declared test model.");
        price.setEffectiveFrom(Instant.now());
        prices.saveAndFlush(price);
        return model;
    }

    private ReviewJob queued(WorkspaceAgentBinding binding) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var currentBinding = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspace.getId(), AgentPurpose.PRACTICE_REVIEW, binding.getDataHandlingTier())
                    .orElseThrow();
            var job = new ReviewJob();
            RuntimeJobTestData.initialize(job, workspace.getId());

            job.setConfigSnapshot(ConfigSnapshot.from(currentBinding, resolver)
                    .withPriceSnapshot(admission.admit(currentBinding).price())
                    .toJson(mapper));
            return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        }));
    }
}
