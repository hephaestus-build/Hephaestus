package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Polls the {@code agent_job} table for {@code QUEUED} work: the queue IS the table, so a QUEUED
 * insert is the enqueue. Claim runs synchronously on the poll thread; execution is handed to the
 * {@code sandboxExecutor} so the poll thread is never blocked by a running sandbox.
 *
 * <p>Claim and terminal write each get their own short transaction; execution runs between them with
 * no transaction and no DB connection held.
 */
@Component
// Expression rather than two @ConditionalOnProperty: Spring honors only one of those per element.
@ConditionalOnExpression(
        "${" + RuntimeRole.AGENT_ENABLED_PROPERTY + ":false} and ${" + RuntimeRole.WORKER_PROPERTY + ":true}")
@WorkspaceAgnostic("Job poller processes jobs across all workspaces")
public class AgentJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentJobExecutor.class);

    private static final String MDC_JOB_ID = StructuredLogKeys.JOB_ID;
    private static final String MDC_JOB_TYPE = "agent.jobType";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 4000;
    private static final int MAX_CONTAINER_LOGS_CHARS = 65536; // 64KB
    // How long a claim-blocked job waits before the poll loop re-evaluates the cap.
    private static final Duration BUDGET_HOLD_INTERVAL = Duration.ofHours(1);
    // Measured from submission, not from when the hold started: what goes stale is the work the job
    // would produce, and bounding the hold instead would let short repeated holds run forever.
    private static final Duration BUDGET_HOLD_MAX_JOB_AGE = Duration.ofDays(7);

    /**
     * When the terminal accounting write is worth re-attempting. NOT the same question as
     * {@link #isRetryableInfraFailure}, which requeues the whole JOB — conflating the two would charge
     * twice for a job that already spent money. {@code includes} matches nested causes, which is what
     * makes these usable directly against JPA's and {@code TransactionTemplate}'s wrapping.
     */
    static final RetryPolicy TERMINAL_PERSIST_POLICY = RetryPolicy.builder()
            .includes(
                    TransientDataAccessException.class,
                    RecoverableDataAccessException.class,
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class)
            .maxRetries(2)
            .delay(Duration.ofMillis(200))
            // Growing rather than fixed: a lock timeout or a failover outlasts an immediate retry.
            .multiplier(2)
            .build();

    /**
     * Any failure of the pool-rejection requeue is worth another try: one statement, no side effect to
     * undo, and not landing it strands a job until the zombie sweeper notices. Runs on the poll thread,
     * so full exhaustion parks polling for the summed delay.
     */
    private static final RetryPolicy REQUEUE_REJECTED_CLAIM_POLICY = RetryPolicy.builder()
            .includes(Exception.class)
            .maxRetries(2)
            .delay(Duration.ofMillis(200))
            .build();

    private final AgentProperties agentProperties;
    private final AgentJobRepository jobRepository;
    private final WorkspaceAgentBindingRepository bindingRepository;
    private final JobTypeHandlerRegistry handlerRegistry;
    private final PracticePiAdapter practiceAgent;
    private final WorkerJwtIssuer workerJwtIssuer;

    private final SandboxManager sandboxManager;
    private final AsyncTaskExecutor sandboxExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final PracticeReviewRefusalMetrics practiceReviewRefusalMetrics;
    private final LlmUsageRecorder usageRecorder;
    private final LlmBudgetService llmBudgetService;
    private final @Nullable LlmAdmissionService llmAdmissionService;

    // RetryTemplate, not @Retryable: both wrap private methods called from inside this class, and a
    // self-invocation never reaches the proxy the annotation needs.
    private final RetryTemplate terminalPersistRetries = retryTemplate(TERMINAL_PERSIST_POLICY);
    private final RetryTemplate requeueRetries = retryTemplate(REQUEUE_REJECTED_CLAIM_POLICY);

    private final Counter concurrencyRejected;
    private final Timer claimLatency;
    private final Counter infraRetryRequeued;
    private final AgentJobTelemetry jobTelemetry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile @Nullable Thread pollThread;
    private final Phaser inFlight = new Phaser(1); // 1 = the executor itself; deregistered on stop
    /** Scopes drain and hub-initiated cancels to this worker's own jobs; also its free-capacity signal. */
    private final Set<UUID> localRunningJobs = ConcurrentHashMap.newKeySet();

    private final Optional<WorkerCapacityState> capacityState;
    private final Optional<WorkerProperties> workerProperties;
    /** Null only when the worker role is off; stamped on claimed jobs to fence terminal writes. */
    private final @Nullable String workerId;
    /** Poll-thread-owned, hence unsynchronized. */
    private boolean lastClaimPoolRejected;

    @Autowired
    public AgentJobExecutor(
            AgentProperties agentProperties,
            AgentJobRepository jobRepository,
            WorkspaceAgentBindingRepository bindingRepository,
            JobTypeHandlerRegistry handlerRegistry,
            PracticePiAdapter practiceAgent,
            WorkerJwtIssuer workerJwtIssuer,
            SandboxManager sandboxManager,
            @Qualifier("sandboxExecutor") AsyncTaskExecutor sandboxExecutor,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            PracticeReviewRefusalMetrics practiceReviewRefusalMetrics,
            AgentJobTelemetry jobTelemetry,
            LlmUsageRecorder usageRecorder,
            LlmBudgetService llmBudgetService,
            @Nullable LlmAdmissionService llmAdmissionService,
            Optional<WorkerCapacityState> capacityState,
            Optional<WorkerProperties> workerProperties) {
        this.agentProperties = agentProperties;
        this.jobRepository = jobRepository;
        this.bindingRepository = bindingRepository;
        this.handlerRegistry = handlerRegistry;
        this.practiceAgent = practiceAgent;
        this.workerJwtIssuer = workerJwtIssuer;
        this.sandboxManager = sandboxManager;
        this.sandboxExecutor = sandboxExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.practiceReviewRefusalMetrics = practiceReviewRefusalMetrics;
        this.jobTelemetry = jobTelemetry;
        this.usageRecorder = usageRecorder;
        this.llmBudgetService = llmBudgetService;
        this.llmAdmissionService = llmAdmissionService;
        this.capacityState = capacityState;
        this.workerProperties = workerProperties;
        this.workerId = workerProperties.map(WorkerProperties::resolvedWorkerId).orElse(null);

        this.concurrencyRejected = Counter.builder(AgentMetrics.AGENT_JOB_CONCURRENCY_REJECTED)
                .description("Jobs rejected due to concurrency limits")
                .register(meterRegistry);
        this.claimLatency = Timer.builder(AgentMetrics.AGENT_JOB_CLAIM_LATENCY)
                .description("Time between a job becoming available (available_at) and being claimed")
                .register(meterRegistry);
        this.infraRetryRequeued = Counter.builder(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED)
                .description("Jobs requeued (not failed) after a classified sandbox-infrastructure failure")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2) // After WorkerLivenessReporter.start() (@Order(1)), so the registry row exists pre-first-claim
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        pollThread = Thread.ofPlatform().name("agent-job-poll").daemon(true).start(this::pollLoop);

        log.info(
                "Agent job executor started: workerId={}, pollInterval={}, claimBatchSize={}",
                workerId,
                agentProperties.pollInterval(),
                agentProperties.claimBatchSize());
    }

    private static final Duration POLL_THREAD_JOIN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Stops the poll loop so no new jobs are claimed. Idempotent.
     *
     * <p>Joining the poll thread is what closes the drain admission race: an in-flight claim could
     * otherwise register with {@link #inFlight} after {@link #awaitInFlight(Duration)} was called, and
     * the Phaser would put that registration in the NEXT phase — so the coordinator's await returns at
     * once believing drain was clean, and the late-claimed job is neither awaited nor cancelled.
     */
    public void stopAcceptingNewJobs() {
        running.set(false);
        Thread thread = pollThread;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(POLL_THREAD_JOIN_TIMEOUT.toMillis());
            if (thread.isAlive()) {
                log.warn(
                        "Poll thread did not stop within {} of stopAcceptingNewJobs() — a claim may still be in flight",
                        POLL_THREAD_JOIN_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Wait up to {@code timeout} for in-flight execution submissions to complete. Must be called
     * after {@link #stopAcceptingNewJobs()} so no new parties register.
     *
     * @return {@code true} if all in-flight work completed within {@code timeout}; {@code false}
     *     on timeout (caller should cancel remaining).
     */
    public boolean awaitInFlight(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return inFlight.getUnarrivedParties() <= 1; // only the executor party left
        }
        try {
            int phase = inFlight.arriveAndDeregister();
            inFlight.awaitAdvanceInterruptibly(phase, timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * Stops this worker's own containers and hands each job back to the queue for a sibling to pick up,
     * per the drain contract in docs/admin/runtime-roles.mdx. Falls back to a terminal cancel only when
     * the worker-fenced requeue loses its CAS or the retry cap is exhausted, so an exhausted job ends up
     * CANCELLED rather than requeued forever.
     */
    public void cancelInFlight(AgentJobCancellationReason reason) {
        Set<UUID> snapshot = Set.copyOf(localRunningJobs);
        if (snapshot.isEmpty()) return;
        log.info("Draining {} in-flight job(s) owned by this worker with reason {}", snapshot.size(), reason);
        for (UUID jobId : snapshot) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    AgentJob job =
                            jobRepository.findByIdWithWorkspaceForUpdate(jobId).orElse(null);
                    if (job == null) return;
                    // BEFORE requeuing: the requeue zeroes the accumulators, so a later read bills zero.
                    AgentJobLlmUsage drainCounts = job.getExecutionStartedAt() != null
                            ? jobRepository.findLlmUsageById(jobId).orElse(null)
                            : null;
                    int updated =
                            workerId != null ? requeueOrphanWithRotation(jobId, workerId, job.getRetryCount()) : 0;
                    if (updated > 0) {
                        if (job.getExecutionStartedAt() != null) {
                            billTerminatedJob(job, "worker draining", drainCounts);
                        }
                        return;
                    }
                    int cancelled = workerId != null
                            ? jobRepository.transitionToCancelledOwnedBy(
                                    jobId,
                                    Instant.now(),
                                    "worker draining",
                                    reason,
                                    Set.of(AgentJobStatus.RUNNING),
                                    workerId)
                            : jobRepository.transitionToCancelled(
                                    jobId, Instant.now(), "worker draining", reason, Set.of(AgentJobStatus.RUNNING));
                    if (cancelled > 0 && job.getExecutionStartedAt() != null) {
                        billTerminatedJob(job, "worker draining");
                    }
                });
                sandboxManager.cancel(jobId);
            } catch (Exception e) {
                log.warn(
                        "Failed to drain in-flight job {}: {}",
                        jobId,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Stops the container only; the authoritative status transition already happened hub-side before
     * the {@code CancelJob} frame was dispatched.
     *
     * @return true if this worker owns the job and a stop was requested
     */
    public boolean cancelLocalJob(UUID jobId, String reason) {
        if (!localRunningJobs.contains(jobId)) {
            return false;
        }
        log.info("Hub-initiated cancel for locally-running job {}: {}", jobId, reason);
        try {
            sandboxManager.cancel(jobId);
            return true;
        } catch (Exception e) {
            log.warn("Local cancel failed for job {}: {}", jobId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Used when no {@code WorkerDrainCoordinator} owns the worker lifecycle (monolith mode). */
    @PreDestroy
    public void stop() {
        stopAcceptingNewJobs();
        awaitInFlight(Duration.ofSeconds(30));
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                int capacity = computeCapacity();
                if (capacity <= 0) {
                    sleepPollInterval();
                    continue;
                }

                List<UUID> candidates = jobRepository.findQueuedIdsOldestFirst(capacity);
                if (candidates.isEmpty()) {
                    sleepPollInterval();
                    continue;
                }

                boolean anyDispatched = false;
                boolean poolRejected = false;
                for (UUID jobId : candidates) {
                    if (!running.get()) {
                        break;
                    }
                    if (poolRejected) {
                        break;
                    }
                    lastClaimPoolRejected = false;
                    if (processJob(jobId)) {
                        if (lastClaimPoolRejected) {
                            poolRejected = true;
                        } else {
                            anyDispatched = true;
                        }
                    }
                }
                // Busy-spin protection: without this backoff, an all-skipped batch re-queries immediately
                // and a saturated pool claims-then-requeues in a tight loop, hammering the DB.
                if (!anyDispatched || poolRejected) {
                    sleepPollInterval();
                }
            } catch (Exception e) {
                log.warn("Poll loop error, retrying in {}: {}", agentProperties.pollInterval(), e.getMessage());
                sleepPollInterval();
            }
        }
        log.info("Agent job executor poll loop stopped");
    }

    /**
     * The sandbox pool's size is the hard bound: worker capacity and pool size are separate knobs, so a
     * capacity larger than the pool must not claim jobs the pool would then reject. What occupies a slot
     * is a job this worker holds, not a thread the pool reports busy: the pool hands a task straight to
     * a thread, so between submit and that thread entering the task it reports the slot idle, and a
     * claim made on that reading is rejected on submit and requeued for nothing.
     */
    int computeCapacity() {
        int concurrency = Math.min(
                capacityState.map(WorkerCapacityState::reviewMax).orElse(Integer.MAX_VALUE),
                sandboxExecutorConcurrency());
        int free = concurrency == Integer.MAX_VALUE
                ? agentProperties.claimBatchSize()
                : Math.max(0, concurrency - localRunningJobs.size());
        return Math.min(free, agentProperties.claimBatchSize());
    }

    /** Only ever narrows {@link #computeCapacity()}: an unknown executor type imposes no bound. */
    private int sandboxExecutorConcurrency() {
        return sandboxExecutor instanceof ThreadPoolTaskExecutor pool ? pool.getMaxPoolSize() : Integer.MAX_VALUE;
    }

    /**
     * ±10% jitter, because replicas sharing one {@code pollInterval} would otherwise stay synchronized
     * and land every poll in the same instant, amplifying claim contention exactly when work exists.
     */
    private void sleepPollInterval() {
        try {
            double jitterMultiplier = 0.9 + (ThreadLocalRandom.current().nextDouble() * 0.2);
            long millis = Math.round(agentProperties.pollInterval().toMillis() * jitterMultiplier);
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return true if the job was claimed and dispatched. Anything else leaves it QUEUED for the next
     *     poll, except the {@link ClaimOutcome}s {@link #claimJob} has already cancelled outright.
     */
    boolean processJob(UUID jobId) {
        ClaimAttempt attempt;
        try {
            attempt = claimJob(jobId);
        } catch (CannotAcquireLockException e) {
            log.debug("Lock timeout during claim for job {}, will retry on next poll", jobId);
            return false;
        } catch (Exception e) {
            log.warn("Claim failed for job {}, will retry on next poll: {}", jobId, e.getMessage());
            return false;
        }
        if (attempt instanceof TerminalClaim terminal) {
            jobTelemetry.terminal(terminal.job(), terminal.status(), AgentJobTelemetry.age(terminal.job()));
            return false;
        }
        if (!(attempt instanceof ClaimResult claim)) {
            return false;
        }
        // Counted after the claim commits, never inside its transaction: a failed commit rolls the row
        // back to QUEUED and no execution then runs to release the count. Since the count is the hard
        // claim bound, one leaked entry would retire a slot for this worker's lifetime.
        localRunningJobs.add(jobId);
        capacityState.ifPresent(WorkerCapacityState::claimReview);
        dispatchExecution(jobId, claim);
        return true;
    }

    private void dispatchExecution(UUID jobId, ClaimResult claim) {
        try {
            inFlight.register();
            sandboxExecutor.execute(() -> {
                try {
                    runClaimedJob(jobId, claim);
                } finally {
                    inFlight.arriveAndDeregister();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.arriveAndDeregister();
            lastClaimPoolRejected = true;
            log.warn(
                    "Sandbox executor rejected claimed job {} (pool smaller than configured worker capacity?) — requeuing",
                    jobId);
            requeueRejectedClaim(jobId);
        }
    }

    /**
     * Undo a claim the sandbox executor couldn't accept: RUNNING → QUEUED, ownership cleared,
     * {@code retry_count} left untouched — the job never started executing, so this must not burn its
     * retry budget.
     *
     * <p>If every attempt fails the row is deliberately left RUNNING under this worker's id (capacity
     * and local tracking are still released). The same sustained DB outage also breaks
     * {@link WorkerLivenessReporter}'s heartbeat, so {@link AgentJobZombieSweeper} reclaims the row
     * through the normal dead-worker path once connectivity returns.
     */
    private void requeueRejectedClaim(UUID jobId) {
        try {
            requeueRetries.execute(named("Requeue of rejected claim " + jobId, () -> {
                transactionTemplate.executeWithoutResult(status -> jobRepository.requeueRejectedClaim(jobId, workerId));
                return null;
            }));
        } catch (RetryException e) {
            log.error(
                    "Failed to requeue rejected claim {} — row stays RUNNING under this worker until "
                            + "liveness/timeout recovery reclaims it",
                    jobId,
                    e.getLastException());
        } finally {
            releaseCapacity();
            localRunningJobs.remove(jobId);
        }
    }

    /**
     * A template that WARNs once per failed attempt. Without the listener a retried write is silent
     * until it gives up, so an operator cannot tell a database that wobbled from one that never
     * wobbled.
     */
    private static RetryTemplate retryTemplate(RetryPolicy policy) {
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new RetryListener() {
            @Override
            public void onRetryableExecution(RetryPolicy inUse, Retryable<?> operation, RetryState state) {
                // Fires after every execution, successful ones included; isSuccessful() is false
                // exactly when the attempt just recorded threw.
                if (state.isSuccessful()) {
                    return;
                }
                log.warn(
                        "{} failed (attempt {}): {}",
                        operation.getName(),
                        state.getExceptions().size(),
                        String.valueOf(state.getLastException()));
            }
        });
        return template;
    }

    /** Names a unit of work so the retry log says which write is failing. */
    private static <R> Retryable<R> named(String name, Supplier<@Nullable R> work) {
        return new Retryable<>() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public R execute() {
                return work.get();
            }
        };
    }

    /** Runs on the sandbox executor, not the poll thread. */
    private void runClaimedJob(UUID jobId, ClaimResult claim) {
        AgentJob job = claim.job;
        MDC.put(StructuredLogKeys.TRACE_ID, job.getTraceId());
        MDC.put(StructuredLogKeys.SPAN_ID, randomSpanId());
        MDC.put(MDC_JOB_ID, jobId.toString());
        MDC.put(StructuredLogKeys.WORKSPACE_ID, job.getWorkspace().getId().toString());
        MDC.put(MDC_JOB_TYPE, job.getJobType().name());
        Instant startTime = Instant.now();
        String metricOutcome = "unknown";
        boolean sandboxExecutionStarted = false;
        PreparedJobInputs stagedInputs = null;
        try {
            Duration queueDuration = Duration.between(job.getCreatedAt(), job.getStartedAt());
            jobTelemetry.transition(
                    job,
                    "agent.job.started",
                    AgentJobTelemetry.Phase.QUEUE,
                    AgentJobTelemetry.Outcome.STARTED,
                    queueDuration);

            PreparedSandbox preparedSandbox = prepareSandboxSpec(jobId, job, claim.snapshot);
            stagedInputs = preparedSandbox.stagedInputs();
            SandboxSpec sandboxSpec = preparedSandbox.spec();
            // Past this boundary provider usage may exist even if execute() throws, so it is persisted
            // for recovery on another process. A lost fence means the job was cancelled or requeued
            // while preparation ran, so its sandbox must not start.
            if (!markExecutionStarted(jobId)) {
                metricOutcome = "OWNERSHIP_LOST";
                log.info("Skipped sandbox start after execution fence was lost: jobId={}", jobId);
                return;
            }
            sandboxExecutionStarted = true;
            SandboxResult result = sandboxManager.execute(sandboxSpec);
            AgentResult agentResult = practiceAgent.parseResult(result);

            JobTypeHandler handler = handlerRegistry.getHandler(job.getJobType());
            AgentJobStatus terminalStatus = completeJob(jobId, agentResult, result, handler, job);
            metricOutcome = terminalStatus != null ? terminalStatus.name() : "unknown";

            log.info("Agent job completed: jobId={}, duration={}", jobId, Duration.between(startTime, Instant.now()));
        } catch (SandboxCancelledException e) {
            metricOutcome = handleCancellation(jobId, job) ? AgentJobStatus.CANCELLED.name() : "OWNERSHIP_LOST";
        } catch (InsufficientEvidenceException e) {
            try {
                persistRefusedEvidence(jobId, job.getJobType(), job.getRetryCount(), e.preparedInputs());
                ObjectNode output = objectMapper.createObjectNode().put("outcome", "INSUFFICIENT_EVIDENCE");
                Integer updated = transactionTemplate.execute(status -> jobRepository.transitionToEvidenceRefused(
                        jobId, workerId, job.getRetryCount(), Instant.now(), output));
                if (updated != null && updated == 1) {
                    recordPracticeReviewRefusal(job, "insufficient_evidence");
                    // Keep the pre-existing execution-duration outcome label; the lifecycle contract
                    // still records the committed terminal state as COMPLETED.
                    metricOutcome = "INSUFFICIENT_EVIDENCE";
                    jobTelemetry.terminal(job, AgentJobStatus.COMPLETED, AgentJobTelemetry.age(job));
                    log.info(
                            "Completed agent job without model execution: jobId={}, outcome=INSUFFICIENT_EVIDENCE",
                            jobId);
                } else {
                    metricOutcome = "OWNERSHIP_LOST";
                }
            } catch (Exception persistenceFailure) {
                metricOutcome = handleExecutionFailure(jobId, job, persistenceFailure, false);
            }
        } catch (TerminalPersistenceException e) {
            // Provider work already completed. Leave RUNNING for the zombie sweeper to terminalize
            // and account as UNPRICED; never execute the provider a second time.
            log.error("Terminal job persistence failed after provider completion: jobId={}", jobId, e);
            metricOutcome = "PERSISTENCE_FAILED";
        } catch (Exception e) {
            metricOutcome = handleExecutionFailure(jobId, job, e, sandboxExecutionStarted);
        } finally {
            // The sandbox has whatever it was going to get by now, so the staging directories behind any
            // disk-staged evidence are no longer referenced by anything.
            if (stagedInputs != null) {
                stagedInputs.close();
            }
            Duration executionDuration = Duration.between(startTime, Instant.now());
            recordExecutionDuration(job.getJobType(), metricOutcome, executionDuration);
            jobTelemetry.record(AgentJobTelemetry.Phase.EXECUTION, executionDuration);
            AgentJobStatus terminalStatus = terminalStatus(metricOutcome);
            if (terminalStatus != null) {
                jobTelemetry.terminal(job, terminalStatus, AgentJobTelemetry.age(job));
            }
            releaseCapacity();
            localRunningJobs.remove(jobId);
            MDC.remove(MDC_JOB_ID);
            MDC.remove(StructuredLogKeys.WORKSPACE_ID);
            MDC.remove(MDC_JOB_TYPE);
            MDC.remove(StructuredLogKeys.TRACE_ID);
            MDC.remove(StructuredLogKeys.SPAN_ID);
        }
    }

    private static @Nullable AgentJobStatus terminalStatus(String outcome) {
        try {
            AgentJobStatus status = AgentJobStatus.valueOf(outcome);
            return status.isTerminal() ? status : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Package-private: the zombie sweeper reuses it when it restores a reaped job's trace context. */
    static String randomSpanId() {
        long value;
        do {
            value = ThreadLocalRandom.current().nextLong();
        } while (value == 0);
        return "%016x".formatted(value);
    }

    private boolean markExecutionStarted(UUID jobId) {
        Integer updated = transactionTemplate.execute(
                status -> jobRepository.markExecutionStarted(jobId, workerId, Instant.now()));
        return updated != null && updated == 1;
    }

    private void recordExecutionDuration(AgentJobType jobType, String outcome, Duration duration) {
        Timer.builder(AgentMetrics.AGENT_JOB_EXECUTION_DURATION)
                .description("Total duration of agent job execution")
                .tag("jobType", jobType != null ? jobType.name() : "unknown")
                .tag("status", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * The staging directories must outlive {@code injectFiles} — which runs inside the sandbox
     * execution — so the caller closes them once the run is over, whatever its outcome.
     */
    private record PreparedSandbox(SandboxSpec spec, PreparedJobInputs stagedInputs) {}

    /** No provider execution happens here, so no LLM cost accrues if this throws. */
    private PreparedSandbox prepareSandboxSpec(UUID jobId, AgentJob job, ConfigSnapshot snapshot) {
        JobTypeHandler handler = handlerRegistry.getHandler(job.getJobType());

        // The claim transaction is long gone, so the handler needs a transaction of its own here to
        // resolve lazy JPA proxies, and a re-fetch that eagerly loads the workspace.
        TransactionTemplate readOnlyTx =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        readOnlyTx.setReadOnly(true);
        PreparedJobInputs preparedInputs = readOnlyTx.execute(status -> {
            AgentJob managedJob = jobRepository.findByIdWithWorkspace(jobId).orElse(job);
            return handler.prepareInputs(managedJob);
        });

        // Sandboxes access providers through the LLM proxy with an attempt-scoped credential.
        String jobToken = workerJwtIssuer.issueForJob(
                jobId,
                job.getWorkspace().getId(),
                job.getRetryCount(),
                Duration.ofSeconds(snapshot.timeoutSeconds()).plusMinutes(5));
        PracticeAgentRequest adapterRequest = new PracticeAgentRequest(
                snapshot.apiProtocol(),
                snapshot.upstreamModelId(),
                snapshot.contextWindow(),
                snapshot.maxOutputTokens(),
                snapshot.supportsReasoning(),
                jobToken,
                snapshot.allowInternet(),
                snapshot.timeoutSeconds());

        PracticeSandboxSpec agentSpec = practiceAgent.buildSandboxSpec(adapterRequest);
        SandboxSpec sandboxSpec =
                buildSandboxSpec(jobId, preparedInputs.files(), preparedInputs.filesOnDisk(), agentSpec, snapshot);
        persistProvenanceDigests(
                jobId,
                job.getJobType(),
                agentSpec.promptDigest(),
                sandboxSpec.inputFiles(),
                job.getRetryCount(),
                preparedInputs.automatedReviewReadinessReport());
        return new PreparedSandbox(sandboxSpec, preparedInputs);
    }

    private void persistRefusedEvidence(
            UUID jobId, AgentJobType jobType, int retryCount, PreparedJobInputs preparedInputs) {
        persistProvenanceDigests(
                jobId,
                jobType,
                null,
                preparedInputs.files(),
                retryCount,
                preparedInputs.automatedReviewReadinessReport());
    }

    /**
     * Deliberately not best-effort: an observation that cannot be tied to the inputs that produced it
     * is unfixable evaluation data, so a failed write fails the run before any LLM cost accrues.
     */
    private void persistProvenanceDigests(
            UUID jobId,
            AgentJobType jobType,
            @Nullable String promptDigest,
            Map<String, byte[]> inputFiles,
            int retryCount,
            @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        String inputsDigest = ProvenanceDigest.inputsDigestHex(inputFiles, jobId);
        JsonNode evidenceSnapshot = evidenceSnapshot(inputFiles, automatedReviewReadinessReport);
        Integer updated = transactionTemplate.execute(status -> jobRepository.updateProvenanceDigests(
                jobId,
                workerId,
                retryCount,
                new AgentJobRepository.ProvenanceStamp(
                        promptDigest,
                        inputsDigest,
                        evidenceSnapshot,
                        automatedReviewReadinessReport == null
                                ? null
                                : objectMapper.valueToTree(automatedReviewReadinessReport))));
        if (updated == null || updated != 1) {
            throw new IllegalStateException("Provenance digest write matched no job row: jobId=" + jobId);
        }
        log.debug("Provenance digests: jobId={}, prompt={}, inputs={}", jobId, promptDigest, inputsDigest);
    }

    private @Nullable JsonNode evidenceSnapshot(
            Map<String, byte[]> inputFiles, @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        byte[] manifest = inputFiles.get(SandboxLayout.MANIFEST_PATH);
        byte[] practices = inputFiles.get(SandboxLayout.PRACTICES_PREFIX + "index.json");
        // Java null, not NullNode: NullNode serializes to the JSON value null, which is a non-SQL-NULL
        // jsonb and so passes every IS NOT NULL predicate a reader writes against this column.
        if (manifest == null && practices == null && automatedReviewReadinessReport == null) return null;
        if (manifest == null || automatedReviewReadinessReport == null) {
            throw new IllegalStateException("Practice review inputs have an incomplete evidence snapshot");
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        if (manifest != null) snapshot.set("manifest", objectMapper.readTree(manifest));
        if (practices != null) {
            snapshot.set("practices", objectMapper.readTree(practices));
        }
        return snapshot;
    }

    private static SandboxSpec buildSandboxSpec(
            UUID jobId,
            Map<String, byte[]> handlerFiles,
            Map<String, java.nio.file.Path> handlerFilesOnDisk,
            PracticeSandboxSpec agentSpec,
            ConfigSnapshot snapshot) {
        Map<String, byte[]> allInputFiles = new HashMap<>(handlerFiles);
        allInputFiles.putAll(agentSpec.inputFiles());

        ResourceLimits limits = new ResourceLimits(
                ResourceLimits.DEFAULT.memoryBytes(),
                ResourceLimits.DEFAULT.cpus(),
                ResourceLimits.DEFAULT.pidsLimit(),
                Duration.ofSeconds(snapshot.timeoutSeconds()));

        return new SandboxSpec(
                jobId,
                agentSpec.image(),
                agentSpec.command(),
                agentSpec.environment(),
                agentSpec.networkPolicy(),
                limits,
                agentSpec.securityProfile(),
                allInputFiles,
                handlerFilesOnDisk,
                agentSpec.outputPath(),
                agentSpec.volumeMounts());
    }

    private boolean handleCancellation(UUID jobId, AgentJob job) {
        Boolean cancelled = transactionTemplate.execute(status -> {
            int updated =
                    transitionTerminal(jobId, AgentJobStatus.CANCELLED, Instant.now(), "Cancelled during execution");
            if (updated > 0) billTerminatedJob(job, "cancelled during execution");
            return updated > 0;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            log.info("Agent job cancelled: jobId={}", jobId);
            return true;
        }
        return false;
    }

    /** Reads the counts itself, so this overload is safe only on terminal (non-requeue) paths. */
    private void billTerminatedJob(AgentJob job, String reason) {
        billTerminatedJob(
                job, reason, jobRepository.findLlmUsageById(job.getId()).orElse(null));
    }

    /**
     * {@code counts} MUST be read BEFORE any requeue of this job: {@link
     * AgentJobRepository#requeueOrphan} zeroes the row's token accumulators, so a caller that requeues
     * first would bill zero and silently drop this attempt's spend.
     */
    private void billTerminatedJob(AgentJob job, String reason, @Nullable AgentJobLlmUsage counts) {
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        LlmPriceSnapshot price = terminalPriceOrUnpriced(snapshot);
        // No runner report exists on this path by definition — the job never reached a clean finish.
        TerminalUsage usage = TerminalUsage.resolve(null, counts);
        boolean billed =
                usage.appendTo(usageRecorder, job.getWorkspace().getId(), job, snapshot.upstreamModelId(), price);
        if (billed) {
            log.info(
                    "Recorded PRICED usage for terminated job ({}): jobId={}, calls={}",
                    reason,
                    job.getId(),
                    usage.totalCalls());
        } else {
            log.info("Recorded UNPRICED usage ledger entry ({}): jobId={}", reason, job.getId());
        }
    }

    /**
     * @param sandboxExecutionStarted whether provider execution may have started; only then can an
     *     unverifiable usage event be truthful
     * @return the {@code agent.job.execution.duration} outcome tag
     */
    private String handleExecutionFailure(UUID jobId, AgentJob job, Exception e, boolean sandboxExecutionStarted) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }

        String errorMessage = truncateErrorMessage(e.getMessage());
        log.error("Agent job failed: jobId={}, error={}", jobId, errorMessage, e);

        if (workerId != null && isRetryableInfraFailure(e)) {
            int currentRetryCount = job.getRetryCount();
            Integer updated = transactionTemplate.execute(status -> {
                // BEFORE requeuing: the requeue zeroes the accumulators, so a later read bills zero.
                AgentJobLlmUsage retryCounts = sandboxExecutionStarted
                        ? jobRepository.findLlmUsageById(jobId).orElse(null)
                        : null;
                int rows = requeueOrphanWithRotation(jobId, workerId, currentRetryCount);
                if (rows > 0 && sandboxExecutionStarted) {
                    billTerminatedJob(
                            job, "infra-failure retry (attempt " + (currentRetryCount + 1) + ")", retryCounts);
                }
                return rows;
            });
            if (updated != null && updated > 0) {
                infraRetryRequeued.increment();
                log.warn(
                        "Requeuing job {} after classified sandbox-infrastructure failure (attempt {}): {}",
                        jobId,
                        currentRetryCount + 1,
                        errorMessage);
                return "REQUEUED";
            }
            log.warn(
                    "Job {} hit an infra failure but could not be requeued (retry cap exhausted or fence lost) — failing terminally",
                    jobId);
        }

        Integer failed = transactionTemplate.execute(status -> {
            int updated = transitionTerminal(jobId, AgentJobStatus.FAILED, Instant.now(), errorMessage);
            if (updated > 0 && sandboxExecutionStarted) billTerminatedJob(job, "execution failure");
            return updated;
        });
        return failed != null && failed > 0 ? AgentJobStatus.FAILED.name() : "OWNERSHIP_LOST";
    }

    /**
     * Deliberately narrower than {@link SandboxException}, which also covers deterministic
     * validation/config failures and a catch-all wrap of an unknown defect: retrying either would burn
     * the retry budget on a failure that was never going to resolve itself.
     */
    static boolean isRetryableInfraFailure(Exception e) {
        return e instanceof SandboxInfrastructureException || e instanceof IOException;
    }

    /**
     * @param currentRetryCount sizes the backoff only — the UPDATE's own {@code retry_count <
     *     max-retries} WHERE clause is the authoritative cap, so a stale read here cannot let a job
     *     requeue past it
     */
    private int requeueOrphanWithRotation(UUID jobId, String owningWorkerId, int currentRetryCount) {
        int attemptNumber = currentRetryCount + 1;
        Instant availableAt = Instant.now().plus(AgentJobBackoff.compute(attemptNumber));
        String newToken = AgentJob.generateJobToken();
        String newTokenHash = AgentJob.computeTokenHash(newToken);
        return jobRepository.requeueOrphan(
                jobId, owningWorkerId, agentProperties.maxRetries(), availableAt, newToken, newTokenHash);
    }

    /** What one claim attempt produced: either a claimed job or the reason there is none. */
    private sealed interface ClaimAttempt {}

    private enum ClaimOutcome implements ClaimAttempt {
        ALREADY_CLAIMED,
        CONCURRENCY_FULL,
        BUDGET_HELD,
    }

    private record ClaimResult(AgentJob job, ConfigSnapshot snapshot) implements ClaimAttempt {}

    private record TerminalClaim(AgentJob job, AgentJobStatus status) implements ClaimAttempt {}

    private @Nullable ClaimAttempt claimJob(UUID jobId) {
        return transactionTemplate.execute(status -> {
            Optional<AgentJob> locked = jobRepository.findByIdQueuedForUpdateSkipLocked(jobId, Instant.now());
            if (locked.isEmpty()) {
                log.debug("Job already claimed or not QUEUED: jobId={}", jobId);
                return ClaimOutcome.ALREADY_CLAIMED;
            }

            AgentJob job = locked.get();

            LlmBudgetBlockReason blockReason =
                    llmBudgetService.decide(job.getWorkspace().getId()).forFunding(claimedFundingSource(job));
            if (blockReason != LlmBudgetBlockReason.NONE) {
                return holdOrCancelOverBudget(job, blockReason);
            }

            // Live-revalidate the catalog binding immediately before RUNNING. Submit-time behaviour
            // stays frozen; only availability/grants and the price are refreshed, and a changed binding
            // is refused rather than silently switching the queued job to another model.
            AgentPurpose purpose = job.getPurpose();
            WorkspaceAgentBinding binding = purpose == null
                    ? null
                    : bindingRepository
                            .findByWorkspaceIdAndPurpose(job.getWorkspace().getId(), purpose)
                            .filter(WorkspaceAgentBinding::isEnabled)
                            .orElse(null);
            if (binding == null) {
                return refuseUnavailableModel(job);
            }
            ConfigSnapshot snapshot;
            try {
                ConfigSnapshot submitted = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
                if (llmAdmissionService != null) {
                    var admitted = llmAdmissionService.admit(binding);
                    var ref = admitted.connection();
                    if (submitted.connectionScope() != ref.scope()
                            || !java.util.Objects.equals(submitted.connectionId(), ref.connectionId())
                            || !java.util.Objects.equals(submitted.modelId(), ref.modelId())
                            || !java.util.Objects.equals(submitted.workspaceId(), ref.workspaceId())
                            || !java.util.Objects.equals(
                                    submitted.upstreamModelId(),
                                    admitted.resolved().upstreamModelId())) {
                        return refuseUnavailableModel(job);
                    }
                    snapshot = submitted.withPriceSnapshot(admitted.price());
                } else {
                    snapshot = submitted;
                }
            } catch (IllegalStateException e) {
                return refuseUnavailableModel(job);
            }

            // Admission above holds the binding row lock (joined into this transaction), so this count
            // cannot race a sibling claim.
            {
                long runningCount = jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                        job.getWorkspace().getId(), purpose, Set.of(AgentJobStatus.RUNNING));
                if (runningCount >= binding.getMaxConcurrentJobs()) {
                    concurrencyRejected.increment();
                    log.info(
                            "Concurrency limit reached: jobId={}, workspaceId={}, purpose={}, running={}, max={}",
                            jobId,
                            job.getWorkspace().getId(),
                            purpose,
                            runningCount,
                            binding.getMaxConcurrentJobs());
                    return ClaimOutcome.CONCURRENCY_FULL;
                }
            }

            Instant claimedAt = Instant.now();
            // A job held for a future availableAt has no queue latency to record — it was waiting on
            // the clock, not on a worker.
            if (!job.getAvailableAt().isAfter(claimedAt)) {
                claimLatency.record(Duration.between(job.getAvailableAt(), claimedAt));
            }
            job.setStatus(AgentJobStatus.RUNNING);
            job.setStartedAt(claimedAt);
            job.setExecutionStartedAt(null);
            // The hold is over — clear its marker so it never outlives the hold it describes. A stale
            // 'BUDGET' marker would survive onto a later crash-retry requeue, and a cap raise would
            // then fast-forward a backoff that has nothing to do with the budget.
            job.setHoldReason(null);
            job.setWorkerId(workerId); // owner for cancel routing, orphan recovery, terminal-write fencing
            job.setConfigSnapshot(snapshot.toJson(objectMapper));
            jobRepository.save(job);

            return new ClaimResult(job, snapshot);
        });
    }

    /**
     * The budget is re-checked at claim even though submit already gated, because a workspace can
     * pre-queue jobs faster than the cap updates. It is never re-checked past this point — there is no
     * mid-execution kill on budget alone.
     *
     * <p>Held rather than cancelled: a month rollover or a raised cap clears the block without the job
     * having failed, so {@code retry_count} stays untouched. The age bound is what keeps that from
     * being unbounded when neither ever happens.
     */
    private ClaimAttempt holdOrCancelOverBudget(AgentJob job, LlmBudgetBlockReason blockReason) {
        Instant now = Instant.now();
        boolean tooOldToHold = job.getCreatedAt() != null
                && Duration.between(job.getCreatedAt(), now).compareTo(BUDGET_HOLD_MAX_JOB_AGE) > 0;
        if (tooOldToHold) {
            job.setStatus(AgentJobStatus.CANCELLED);
            job.setCompletedAt(now);
            job.setErrorMessage("Cancelled: over the monthly AI budget, and this job is more than "
                    + BUDGET_HOLD_MAX_JOB_AGE.toDays()
                    + " days old.");
            job.setCancellationReason(AgentJobCancellationReason.BUDGET_EXHAUSTED);
            jobRepository.save(job);
            log.info(
                    "Cancelling claim — over budget and older than {} days: jobId={}, workspaceId={}, blockReason={}",
                    BUDGET_HOLD_MAX_JOB_AGE.toDays(),
                    job.getId(),
                    job.getWorkspace().getId(),
                    blockReason);
            recordPracticeReviewRefusal(job, "budget_exhausted");
            meterRegistry.counter(AgentMetrics.AGENT_JOB_BUDGET_REFUSED).increment();
            return new TerminalClaim(job, AgentJobStatus.CANCELLED);
        }
        job.setAvailableAt(now.plus(BUDGET_HOLD_INTERVAL));
        // Marks this as a budget hold specifically, so raising the cap can release it at once instead
        // of leaving the admin to wait out BUDGET_HOLD_INTERVAL.
        job.setHoldReason(AgentJob.HOLD_REASON_BUDGET);
        jobRepository.save(job);
        log.info(
                "Holding claim — monthly LLM budget {}: jobId={}, workspaceId={}, retryAt={}",
                blockReason == LlmBudgetBlockReason.EXHAUSTED ? "exhausted" : "unverifiable (cap set)",
                job.getId(),
                job.getWorkspace().getId(),
                job.getAvailableAt());
        meterRegistry.counter(AgentMetrics.AGENT_JOB_BUDGET_HELD).increment();
        return ClaimOutcome.BUDGET_HELD;
    }

    private ClaimAttempt refuseUnavailableModel(AgentJob job) {
        String message = "Configured model is unavailable.";
        job.setStatus(AgentJobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(message);
        job.setCancellationReason(AgentJobCancellationReason.MODEL_UNAVAILABLE);
        jobRepository.save(job);
        meterRegistry.counter(AgentMetrics.AGENT_JOB_MODEL_REFUSED).increment();
        recordPracticeReviewRefusal(job, "model_unavailable");
        log.info("Refusing claim — configured model unavailable: jobId={}", job.getId());
        return new TerminalClaim(job, AgentJobStatus.CANCELLED);
    }

    private void recordPracticeReviewRefusal(AgentJob job, String reason) {
        if (job.getPurpose() == AgentPurpose.PRACTICE_REVIEW) {
            practiceReviewRefusalMetrics.recordExecutionRefusal(reason);
        }
    }

    private void releaseCapacity() {
        capacityState.ifPresent(WorkerCapacityState::releaseReview);
    }

    /**
     * Fenced on {@code worker_id}: if the job was orphan-requeued to a sibling, this worker's late
     * write no-ops instead of clobbering the sibling's run. Unfenced only when no worker identity
     * exists, where there are no siblings to fence against.
     *
     * @return rows updated (0 if no longer RUNNING or no longer owned by this worker)
     */
    private int transitionTerminal(UUID jobId, AgentJobStatus status, Instant now, @Nullable String error) {
        return workerId != null
                ? jobRepository.transitionStatusOwnedBy(
                        jobId, status, now, error, Set.of(AgentJobStatus.RUNNING), workerId)
                : jobRepository.transitionStatus(jobId, status, now, error, Set.of(AgentJobStatus.RUNNING));
    }

    private @Nullable AgentJobStatus completeJob(
            UUID jobId, AgentResult agentResult, SandboxResult sandboxResult, JobTypeHandler handler, AgentJob job) {
        AgentJobStatus terminalStatus = determineTerminalStatus(sandboxResult, agentResult);
        // persistTerminalState returns false when we lost the fence (cancelled / orphan-requeued); we
        // must not deliver then, or a stuck-then-recovered worker would double-post the sibling's findings.
        boolean persisted = persistTerminalState(jobId, agentResult, sandboxResult, terminalStatus);
        if (persisted) {
            deliverResults(jobId, terminalStatus, handler);
            return terminalStatus;
        } else {
            log.info("Skipping delivery: job no longer owned/RUNNING (requeued or cancelled): jobId={}", jobId);
            return null;
        }
    }

    /**
     * A non-zero exit that still produced valid output counts as COMPLETED so the findings are
     * delivered — the runner's validation is stricter than the Java-side parser, so an agent can write
     * result.json and still exit 1 for an unrelated reason.
     */
    private AgentJobStatus determineTerminalStatus(SandboxResult sandboxResult, AgentResult agentResult) {
        if (sandboxResult.timedOut()) {
            return AgentJobStatus.TIMED_OUT;
        }
        if (sandboxResult.exitCode() == 0) {
            return AgentJobStatus.COMPLETED;
        }
        // Distinguish envelope drift (exit 42) from generic failure — the runner emits this when
        // the task.json schemaVersion / kind doesn't match this image. Operators need to see
        // this distinctly from agent crashes; the secondary metric also alerts on image drift.
        if (sandboxResult.exitCode() == SandboxLayout.EXIT_ENVELOPE_MISMATCH) {
            log.error(
                    "Pi runner rejected task envelope (exit {}) — server/image schemaVersion or kind drift. "
                            + "Rebuild the agent-pi image or roll back the server.",
                    SandboxLayout.EXIT_ENVELOPE_MISMATCH);
            meterRegistry.counter(AgentMetrics.AGENT_PI_ENVELOPE_MISMATCH).increment();
            return AgentJobStatus.FAILED;
        }
        if (agentResult != null && agentResult.output() != null) {
            Object rawOutput = agentResult.output().get("rawOutput");
            if (rawOutput instanceof String raw && !raw.isBlank()) {
                log.info(
                        "Agent exited with code {} but produced output — treating as COMPLETED for delivery",
                        sandboxResult.exitCode());
                return AgentJobStatus.COMPLETED;
            }
            if (rawOutput != null) {
                log.warn(
                        "Agent exited with code {} and rawOutput is present but not a String (type={})",
                        sandboxResult.exitCode(),
                        rawOutput.getClass().getSimpleName());
            }
        }
        return AgentJobStatus.FAILED;
    }

    /**
     * The accounting write for provider work that has ALREADY happened, re-attempted while the failure
     * is one the database may recover from.
     *
     * <p>Giving up deliberately leaves the row RUNNING: the provider work is already done and paid for,
     * so re-running would charge for it twice. {@link AgentJobZombieSweeper} terminalises the row
     * instead and books the proxy-recorded spend.
     *
     * @return {@code true} if this worker won the terminal write; {@code false} if the job was
     *     cancelled or orphan-requeued to a sibling, in which case the caller must NOT deliver.
     */
    private boolean persistTerminalState(
            UUID jobId, AgentResult agentResult, SandboxResult sandboxResult, AgentJobStatus terminalStatus) {
        String errorMessage =
                switch (terminalStatus) {
                    case TIMED_OUT -> "Container timed out";
                    case FAILED -> "Container exited with code " + sandboxResult.exitCode();
                    default -> null;
                };
        try {
            return terminalPersistRetries.execute(named(
                    "Terminal persistence for jobId=" + jobId,
                    () -> persistTerminalStateOnce(jobId, agentResult, sandboxResult, terminalStatus, errorMessage)));
        } catch (RetryException e) {
            throw new TerminalPersistenceException(e);
        }
    }

    private boolean persistTerminalStateOnce(
            UUID jobId,
            AgentResult agentResult,
            SandboxResult sandboxResult,
            AgentJobStatus terminalStatus,
            @Nullable String errorMessage) {
        Boolean persisted = transactionTemplate.execute(status -> {
            int updated = transitionTerminal(jobId, terminalStatus, Instant.now(), errorMessage);
            if (updated == 0) {
                log.info("Job no longer owned/RUNNING, skipping output persist: jobId={}", jobId);
                return false;
            }

            AgentJob freshJob = jobRepository.findById(jobId).orElseThrow();
            // Read the proxy's committed per-call accumulations BEFORE the runner-reported totals are
            // written over them below — they are the fallback when the runner reported nothing.
            AgentJobLlmUsage proxyCounts = jobRepository.findLlmUsageById(jobId).orElse(null);
            ConfigSnapshot snapshot = ConfigSnapshot.fromJson(freshJob.getConfigSnapshot(), objectMapper);
            LlmPriceSnapshot price = admittedPrice(snapshot);

            freshJob.setOutput(objectMapper.valueToTree(agentResult.output()));
            freshJob.setExitCode(sandboxResult.exitCode());
            if (sandboxResult.logs() != null && !sandboxResult.logs().isBlank()) {
                String logs = sandboxResult.logs();
                freshJob.setContainerLogs(
                        logs.length() > MAX_CONTAINER_LOGS_CHARS
                                ? logs.substring(logs.length() - MAX_CONTAINER_LOGS_CHARS)
                                : logs);
            }
            if (terminalStatus == AgentJobStatus.COMPLETED) {
                freshJob.setDeliveryStatus(DeliveryStatus.PENDING);
            }

            var runnerUsage = agentResult.usage();
            // The runner's own report is preferred (it also covers streamed calls the proxy skips),
            // but an absent or empty one falls back to what the proxy actually watched go upstream —
            // otherwise a runner that never wrote usage.json would book real spend as zero.
            TerminalUsage usage = TerminalUsage.resolve(runnerUsage, proxyCounts);

            if (runnerUsage != null && runnerUsage.totalCalls() > 0) {
                freshJob.setLlmTotalCalls(runnerUsage.totalCalls());
                freshJob.setLlmTotalInputTokens(runnerUsage.inputTokens());
                freshJob.setLlmTotalOutputTokens(runnerUsage.outputTokens());
                freshJob.setLlmTotalReasoningTokens(runnerUsage.reasoningTokens());
                freshJob.setLlmCacheReadTokens(runnerUsage.cacheReadTokens());
                freshJob.setLlmCacheWriteTokens(runnerUsage.cacheWriteTokens());
            }
            // Provider output is telemetry only. The admitted snapshot is authoritative identity.
            freshJob.setLlmModel(snapshot.upstreamModelId());
            freshJob.setLlmModelVersion(snapshot.modelVersion());
            jobRepository.saveAndFlush(freshJob);

            usage.appendTo(usageRecorder, freshJob.getWorkspace().getId(), freshJob, snapshot.upstreamModelId(), price);
            return true;
        });
        return Boolean.TRUE.equals(persisted);
    }

    private static final class TerminalPersistenceException extends RuntimeException {

        private TerminalPersistenceException(Throwable cause) {
            super("Could not durably persist terminal job result and usage", cause);
        }
    }

    /**
     * A job whose purpose or binding is gone yields {@code null}, which
     * {@link LlmBudgetDecision#forFunding} judges against BOTH caps: an unattributable job must not be
     * a way around either one.
     */
    private @Nullable FundingSource claimedFundingSource(AgentJob job) {
        AgentPurpose purpose = job.getPurpose();
        if (purpose == null) {
            return null;
        }
        return bindingRepository
                .findByWorkspaceIdAndPurpose(job.getWorkspace().getId(), purpose)
                .map(WorkspaceAgentBinding::getFundingSource)
                .orElse(null);
    }

    private LlmPriceSnapshot admittedPrice(ConfigSnapshot snapshot) {
        if (snapshot.priceSnapshot() != null) return snapshot.priceSnapshot();
        if (llmAdmissionService != null) {
            throw new IllegalStateException("Started job has no admitted LLM price snapshot");
        }
        return LlmPriceSnapshot.unpricedInstance();
    }

    /**
     * Unlike {@link #admittedPrice}, this must NEVER throw: an exception here would roll back the very
     * transition that marks the job terminal, resurrecting a job that has already run (and can loop).
     * A missing price is billed UNPRICED, matching {@link AgentJobZombieSweeper}'s reaper.
     */
    private LlmPriceSnapshot terminalPriceOrUnpriced(ConfigSnapshot snapshot) {
        LlmPriceSnapshot price = snapshot.priceSnapshot();
        return price != null ? price : LlmPriceSnapshot.unpricedInstance();
    }

    /** Runs outside any transaction — it calls external APIs. */
    private void deliverResults(UUID jobId, AgentJobStatus terminalStatus, JobTypeHandler handler) {
        if (terminalStatus != AgentJobStatus.COMPLETED) {
            return;
        }

        // Reload to get the freshly persisted output
        AgentJob deliverJob = jobRepository.findById(jobId).orElse(null);
        if (deliverJob != null) {
            Instant deliveryStarted = Instant.now();
            try {
                handler.deliver(deliverJob);
                persistDeliveryStatus(jobId, DeliveryStatus.DELIVERED, deliverJob.getDeliveryCommentId());
                jobTelemetry.transition(
                        deliverJob,
                        "agent.job.delivery",
                        AgentJobTelemetry.Phase.DELIVERY,
                        AgentJobTelemetry.Outcome.DELIVERED,
                        Duration.between(deliveryStarted, Instant.now()));
            } catch (Exception e) {
                log.warn("Delivery failed for job {} (output saved, job still COMPLETED): {}", jobId, e.getMessage());
                // Preserve the comment id from a partial delivery: the comment may already be posted.
                persistDeliveryStatus(jobId, DeliveryStatus.FAILED, deliverJob.getDeliveryCommentId());
                jobTelemetry.transition(
                        deliverJob,
                        "agent.job.delivery",
                        AgentJobTelemetry.Phase.DELIVERY,
                        AgentJobTelemetry.Outcome.DELIVERY_FAILED,
                        Duration.between(deliveryStarted, Instant.now()));
                meterRegistry.counter(AgentMetrics.AGENT_JOB_DELIVERY_FAILURE).increment();
            }
        }
    }

    private void persistDeliveryStatus(UUID jobId, DeliveryStatus status, @Nullable String commentId) {
        try {
            transactionTemplate.executeWithoutResult(
                    tx -> jobRepository.updateDeliveryStatus(jobId, status, commentId));
        } catch (Exception e) {
            log.error("Failed to persist delivery status: jobId={}, status={}", jobId, status, e);
        }
    }

    private static String truncateErrorMessage(@Nullable String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "... [truncated]"
                : message;
    }
}
