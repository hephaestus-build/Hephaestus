package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
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
import java.util.concurrent.CompletableFuture;
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
                roomySandboxes(),
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
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        methods,
                        assignmentSnapshots,
                        transactions,
                        mapper,
                        context.getBean(ReviewStart.class),
                        budgets,
                        admission,
                        new ReviewJobTelemetry(metrics, Tracer.NOOP),
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
        long blockedPool = endpointPool(blockedBinding);
        var poolRows = context.getBean(LlmCallPermitRepository.class);
        UUID waitingCall = UUID.randomUUID();
        transactions.executeWithoutResult(status -> poolRows.enqueue(new LlmCallPermitRepository.Request(
                waitingCall,
                blockedPool,
                "blocked-waiter",
                LlmCallClass.REQUEST.name(),
                workspace.getId(),
                UUID.randomUUID(),
                0)));
        var openBinding = binding(DataHandlingTier.CLOUD);
        long openPool = endpointPool(openBinding);
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
            assertThat(stillQueued.getLeaseId()).isNull();
            assertThat(stillQueued.getExecutionStartedAt()).isNull();
            assertThat(blockedPool).isNotEqualTo(openPool);
        } finally {
            polling.stopAcceptingNewJobs();
            transactions.executeWithoutResult(status -> poolRows.abandon(waitingCall, "blocked-waiter"));
        }
    }

    @Test
    void shouldOfferAnOlderEligibleReviewBeforeFreshArrivals() {
        var unrelated = Set.copyOf(jobs.findAll().stream().map(ReviewJob::getId).toList());
        var selected = binding(DataHandlingTier.IN_HOUSE);
        var oldest = queued(selected);
        jdbc.update("UPDATE review_job SET available_at=now()-interval '2 minutes' WHERE id=?", oldest.getId());

        for (int round = 0; round < 6; round++) {
            queued(selected);
            assertThat(jobs.findQueuedIdsStartedFirst(1, Set.of(), unrelated)).containsExactly(oldest.getId());
        }
    }

    private long endpointPool(WorkspaceAgentBinding binding) {
        var pool = new LlmCallPool();
        pool.setSlug("endpoint-" + UUID.randomUUID());
        pool.setDisplayName("Endpoint scan fixture");
        pool.setMaxConcurrentCalls(2);
        pool = context.getBean(LlmCallPoolRepository.class).saveAndFlush(pool);
        var model = Objects.requireNonNull(binding.getInstanceModel());
        model.setCallPoolId(pool.getId());
        models.saveAndFlush(model);
        return pool.getId();
    }

    @Test
    void shouldKeepAFutureReviewQueuedWhileEligibleWorkStarts() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(selected);
        var future = queued(selected);
        future.setAvailableAt(Instant.now().plusSeconds(60));
        jobs.saveAndFlush(future);
        var runnable = queued(selected);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        var polling = pooledExecutor(properties, capacity, roomySandboxes());

        assertThat(polling.processJob(future.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(polling.processJob(runnable.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        assertThat(jobs.findById(future.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(jobs.findById(runnable.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(context.getBean(LlmCallPermitRepository.class).countUnclosed(poolId))
                .isZero();
    }

    @Test
    void shouldLeaveACappedBindingQueuedWithoutHoldingProviderCapacity() {
        var capped = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(capped);
        var waiting = queued(capped);
        var running = queued(capped);
        running.setStatus(ReviewJobStatus.RUNNING);
        jobs.saveAndFlush(running);
        var open = binding(DataHandlingTier.CLOUD);
        var model = Objects.requireNonNull(open.getInstanceModel());
        model.setCallPoolId(poolId);
        models.saveAndFlush(model);
        var runnable = queued(open);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        var polling = pooledExecutor(properties, capacity, roomySandboxes());

        assertThat(polling.processJob(waiting.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(polling.processJob(runnable.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        assertThat(jobs.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(jobs.findById(runnable.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(context.getBean(LlmCallPermitRepository.class).countUnclosed(poolId))
                .isZero();
    }

    @Test
    void shouldKeepAReviewQueuedUntilQuotaRoomReturns() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var sandboxes = mock(SandboxManager.class);
        when(sandboxes.roomFor(any())).thenReturn(0, 1);
        var polling = pooledExecutor(properties, unpooledCalls(), sandboxes);

        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertUnclaimed(job);
        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
    }

    @Test
    void shouldLeaveTheNextReviewQueuedWhenAPreparingClaimUsesTheObservedQuotaRoom() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        selected.setMaxConcurrentJobs(3);
        bindings.saveAndFlush(selected);
        var first = queued(selected);
        var second = queued(selected);
        var sandboxes = mock(SandboxManager.class);
        when(sandboxes.roomFor(any())).thenReturn(1);
        var polling = pooledExecutor(properties, unpooledCalls(), sandboxes);

        assertThat(polling.processJob(first.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        assertThat(polling.processJob(second.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertUnclaimed(second);
        when(sandboxes.roomFor(any())).thenReturn(2);
        assertThat(polling.processJob(second.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
    }

    @Test
    void shouldRetryAReviewAfterTheQuotaObservationFails() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var sandboxes = mock(SandboxManager.class);
        when(sandboxes.roomFor(any()))
                .thenThrow(new IllegalStateException("quota unavailable"))
                .thenReturn(1);
        var polling = pooledExecutor(properties, unpooledCalls(), sandboxes);

        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertUnclaimed(job);
        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
    }

    @Test
    void shouldRetryAReviewAfterThePoolObservationFails() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(selected);
        var job = queued(selected);
        var capacity = mock(LlmCallPermits.class);
        when(capacity.poolFor(any())).thenReturn(poolId);
        when(capacity.hasBackgroundWaiters(poolId))
                .thenThrow(new IllegalStateException("pool unavailable"))
                .thenReturn(false);
        var polling = pooledExecutor(properties, capacity, roomySandboxes());

        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertUnclaimed(job);
        assertThat(context.getBean(LlmCallPermitRepository.class).countUnclosed(poolId))
                .isZero();
        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        assertThat(context.getBean(LlmCallPermitRepository.class).countUnclosed(poolId))
                .isZero();
    }

    @Test
    void shouldClaimWithoutReservingEvenWhenEveryProviderSlotIsActive() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(selected);
        var pools = context.getBean(LlmCallPoolRepository.class);
        var pool = pools.findById(poolId).orElseThrow();
        pool.setMaxConcurrentCalls(1);
        pools.saveAndFlush(pool);
        var rows = context.getBean(LlmCallPermitRepository.class);
        UUID active = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            rows.enqueue(new LlmCallPermitRepository.Request(
                    active,
                    poolId,
                    "active-call",
                    LlmCallClass.REQUEST.name(),
                    workspace.getId(),
                    UUID.randomUUID(),
                    0));
            assertThat(rows.grant(active, 0, "active-call")).isEqualTo(1);
        });
        var job = queued(selected);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        try {
            assertThat(pooledExecutor(properties, capacity, roomySandboxes()).processJob(job.getId()))
                    .isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
            assertThat(rows.countActive(poolId)).isEqualTo(1);
            assertThat(rows.countUnclosed(poolId)).isEqualTo(1);
            assertThat(rows.rates(poolId).getRequests()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM llm_call_permit WHERE source_id=?", Long.class, job.getId()))
                    .isZero();
        } finally {
            transactions.executeWithoutResult(status -> rows.release(active, "active-call"));
        }
    }

    @Test
    void shouldClaimAReviewWhenOnlyProductFeedbackWaits() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        long poolId = endpointPool(selected);
        var rows = context.getBean(LlmCallPermitRepository.class);
        UUID feedback = UUID.randomUUID();
        transactions.executeWithoutResult(status -> rows.enqueue(new LlmCallPermitRepository.Request(
                feedback,
                poolId,
                "feedback-waiter",
                LlmCallClass.PRODUCT_FEEDBACK.name(),
                null,
                UUID.randomUUID(),
                0)));
        var job = queued(selected);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        try {
            assertThat(pooledExecutor(properties, capacity, roomySandboxes()).processJob(job.getId()))
                    .isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
            assertThat(rows.countActive(poolId)).isZero();
            assertThat(rows.countUnclosed(poolId)).isEqualTo(1);
            assertThat(rows.rates(poolId).getRequests()).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM llm_call_permit WHERE source_id=?", Long.class, job.getId()))
                    .isZero();
        } finally {
            transactions.executeWithoutResult(status -> rows.abandon(feedback, "feedback-waiter"));
        }
    }

    @Test
    void shouldResumeCompositionWithoutReviewQuotaOrBacklogAdmission() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(selected);
        jdbc.update(
                "UPDATE review_job SET attempt=1, admission_attempt=0, admission_digest=? WHERE id=?",
                "a".repeat(64),
                job.getId());
        var capacity = mock(LlmCallPermits.class);
        // These observations are invalid for a composition, which creates no review Pod.
        when(capacity.poolFor(any())).thenThrow(new IllegalStateException("review pool must not be observed"));
        var sandboxes = mock(SandboxManager.class);
        when(sandboxes.roomFor(any())).thenThrow(new IllegalStateException("review quota must not be observed"));
        when(sandboxes.ready()).thenReturn(true);
        var polling = pooledExecutor(properties, capacity, sandboxes);

        assertThat(polling.computeCapacity()).isPositive();
        assertThat(polling.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(claimed.getAttempt()).isEqualTo(1);
    }

    @Test
    void shouldStartTheParentInTheTransactionThatLeasesTheJob() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        assertThat(parentStatus(job)).isEqualTo("QUEUED");

        assertThat(claimService(jobs).claimJob(job.getId(), () -> true))
                .isInstanceOf(ReviewJobClaimService.ClaimResult.class);

        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(claimed.getLeasedUntil()).isNotNull();
        assertThat(parentStatus(job)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                        "SELECT checks->>'startedAt' FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        job.getWorkspaceId(),
                        job.getReviewId()))
                .isNotNull();
    }

    @Test
    void shouldRollBackTheParentAndJobWhenTheLeaseCannotBeCreated() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // Spring Data's repository proxy cannot be spied on, so a mock delegates every other call to it.
        var failing = mock(ReviewJobRepository.class, delegatesTo(jobs));
        doReturn(0).when(failing).lease(eq(job.getId()), anyLong());

        assertThatThrownBy(() -> claimService(failing).claimJob(job.getId(), () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The claimed attempt is no longer running");

        assertUnclaimed(job);
        assertThat(parentStatus(job)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "SELECT checks->>'startedAt' FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        job.getWorkspaceId(),
                        job.getReviewId()))
                .isNull();
    }

    @Test
    void shouldCancelTheJobWhenItsParentWasCancelledBeforeTheClaim() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // The review's own cancellation never reached this job, as after a restart between its commit and listener.
        jdbc.update(
                "UPDATE review SET status='CANCELLED',completed_at=clock_timestamp(),stop_reason='CANCELLED' WHERE workspace_id=? AND id=?",
                job.getWorkspaceId(),
                job.getReviewId());

        assertThat(claimService(jobs).claimJob(job.getId(), () -> true))
                .isInstanceOfSatisfying(
                        ReviewJobClaimService.TerminalClaim.class,
                        terminal -> assertThat(terminal.status()).isEqualTo(ReviewJobStatus.CANCELLED));

        var cancelled = jobs.findById(job.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
        assertThat(cancelled.getStartedAt()).isNull();
        assertThat(cancelled.getLeaseId()).isNull();
        assertThat(cancelled.getLeasedUntil()).isNull();
        assertThat(parentStatus(job)).isEqualTo("CANCELLED");
        assertThat(jobs.findQueuedIdsStartedFirst(10)).doesNotContain(job.getId());
    }

    @Test
    void shouldSkipAClaimWhileCancellationHoldsItsParentLock() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var claims = claimService(jobs);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            transactions.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM review WHERE workspace_id=? AND id=? FOR UPDATE",
                                String.class,
                                job.getWorkspaceId(),
                                job.getReviewId()))
                        .isEqualTo("QUEUED");
                assertThat(CompletableFuture.supplyAsync(() -> claims.claimJob(job.getId(), () -> true), requests)
                                .orTimeout(10, TimeUnit.SECONDS)
                                .join())
                        .isEqualTo(ReviewJobClaimService.ClaimOutcome.ALREADY_CLAIMED);
            });
        }
        assertUnclaimed(job);
        assertThat(parentStatus(job)).isEqualTo("QUEUED");
    }

    private String parentStatus(ReviewJob job) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM review WHERE workspace_id=? AND id=?",
                String.class,
                job.getWorkspaceId(),
                job.getReviewId()));
    }

    private ReviewJobClaimService claimService(ReviewJobRepository repository) {
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        return new ReviewJobClaimService(
                repository,
                policy,
                methods,
                assignments,
                transactions,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                unpooledCalls());
    }

    private void assertUnclaimed(ReviewJob job) {
        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.getAttempt()).isEqualTo(job.getAttempt());
        assertThat(queued.getLeaseId()).isNull();
        assertThat(queued.getStartedAt()).isNull();
        assertThat(queued.getExecutionStartedAt()).isNull();
        assertThat(queued.getLeasedUntil()).isNull();
    }

    private static SandboxManager roomySandboxes() {
        var sandboxes = mock(SandboxManager.class);
        when(sandboxes.roomFor(any())).thenReturn(Integer.MAX_VALUE);
        return sandboxes;
    }

    private ReviewJobExecutor pooledExecutor(
            AgentProperties settings, LlmCallPermits capacity, SandboxManager sandboxes) {
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var metrics = new SimpleMeterRegistry();
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var usage = new ReviewAttemptUsageService(jobs, attemptUsageRows, mock(LlmUsageRecorder.class));
        return new ReviewJobExecutor(
                settings,
                jobs,
                mock(DefaultReviewExecutionHandler.class),
                mock(PracticePiAdapter.class),
                mock(AttemptTokenIssuer.class),
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
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        methods,
                        assignments,
                        transactions,
                        mapper,
                        context.getBean(ReviewStart.class),
                        budgets,
                        admission,
                        new ReviewJobTelemetry(metrics, Tracer.NOOP),
                        capacity),
                new ReviewJobRecovery(jobs, settings, mapper, usage, Optional.empty()));
    }

    @Test
    void shouldWaitForBackgroundDemandWithoutReservingAProviderSlot() throws Exception {
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
        runningReview.setLeaseId(UUID.randomUUID());
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
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
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
                assertThat(claims.claimJob(job.getId(), () -> true))
                        .isNotInstanceOf(ReviewJobClaimService.ClaimResult.class);
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
                assertThat(jobs.findById(job.getId()).orElseThrow().getLeaseId())
                        .isNull();
                busy.close();
                var granted = Objects.requireNonNull(repeated.get(12, TimeUnit.SECONDS));
                assertThat(granted.isRevoked()).isFalse();
                assertThat(claims.claimJob(job.getId(), () -> true))
                        .isInstanceOf(ReviewJobClaimService.ClaimResult.class);
                assertThat(context.getBean(LlmCallPermitRepository.class).countActive(poolId))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM llm_call_permit WHERE source_id=?", Long.class, job.getId()))
                        .isZero();
            } finally {
                busy.close();
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
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                unpooledCalls());

        var claim = claims.claimJob(job.getId(), () -> true);

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
    void shouldLeaveTheReviewQueuedWhileItsEndpointHasBackgroundWaiters() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var capacity = mock(LlmCallPermits.class);
        when(capacity.poolFor(any())).thenReturn(42L);
        when(capacity.hasBackgroundWaiters(anyLong())).thenReturn(true);
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignmentSnapshots,
                transactions,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                capacity);

        var claim = claims.claimJob(job.getId(), () -> true);

        assertThat(claim).hasFieldOrPropertyWithValue("poolId", 42L);
        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.getAttempt()).isEqualTo(job.getAttempt());
        assertThat(queued.getLeaseId()).isNull();
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
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                unpooledCalls());

        assertThat(claims.claimJob(job.getId(), () -> true))
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
                context.getBean(ReviewStart.class),
                mock(LlmBudgetService.class),
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                unpooledCalls());

        assertThat(claims.claimJob(job.getId(), () -> true))
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
        assertThat(claims.claimJob(job.getId(), () -> true))
                .isEqualTo(ReviewJobClaimService.ClaimOutcome.ALREADY_CLAIMED);
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
