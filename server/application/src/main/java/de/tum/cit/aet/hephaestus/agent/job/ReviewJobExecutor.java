package de.tum.cit.aet.hephaestus.agent.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.StorableText;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
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

// Claim and terminal writes use short transactions; sandbox execution must hold no database connection.
@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Job poller processes jobs across all workspaces")
public class ReviewJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobExecutor.class);

    private static final String MDC_JOB_ID = StructuredLogKeys.JOB_ID;
    private static final String MDC_JOB_TYPE = "agent.jobType";
    /**
     * When the terminal accounting write is worth re-attempting. NOT the same question as
     * {@link #isRetryableExecutionFailure}, which requeues the whole JOB — conflating the two would charge
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

    // A failed requeue strands the claim until recovery; this side-effect-free write can be retried.
    private static final RetryPolicy REQUEUE_REJECTED_CLAIM_POLICY = RetryPolicy.builder()
            .includes(Exception.class)
            .maxRetries(2)
            .delay(Duration.ofMillis(200))
            .build();

    private final AgentProperties agentProperties;
    private final ReviewJobClaimService claims;
    private final ReviewJobRecovery recovery;
    private final ReviewJobRepository jobRepository;
    private final DefaultReviewExecutionHandler handler;
    private final PracticePiAdapter practiceAgent;
    private final WorkerJwtIssuer workerJwtIssuer;

    private final SandboxManager sandboxManager;
    private final SandboxProperties.DefaultResourceLimits reviewLimits;
    private final AsyncTaskExecutor sandboxExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ReviewAttemptUsageService attemptUsage;
    private final PracticeScriptRunRecorder practiceScriptRuns;
    private final @Nullable LlmAdmissionService llmAdmissionService;

    // RetryTemplate, not @Retryable: both wrap private methods called from inside this class, and a
    // self-invocation never reaches the proxy the annotation needs.
    private final RetryTemplate terminalPersistRetries = retryTemplate(TERMINAL_PERSIST_POLICY);
    private final RetryTemplate requeueRetries = retryTemplate(REQUEUE_REJECTED_CLAIM_POLICY);

    private final Counter infraRetryRequeued;
    private final ReviewJobTelemetry jobTelemetry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile @Nullable Thread pollThread;
    private final Phaser inFlight = new Phaser(1); // 1 = the executor itself; deregistered on stop
    // The attempt each locally running job runs, so a later attempt of the same job is never mistaken for it.
    private final Map<UUID, Integer> localRunningJobs = new ConcurrentHashMap<>();

    private final Optional<WorkerCapacityState> capacityState;
    // Worker identity fences terminal writes.
    private final String workerId;

    private @Nullable ReviewCompositionContinuation compositionContinuation;

    @Autowired(required = false)
    void setCompositionContinuation(ReviewCompositionContinuation compositionContinuation) {
        this.compositionContinuation = compositionContinuation;
    }

    @Autowired
    public ReviewJobExecutor(
            AgentProperties agentProperties,
            ReviewJobRepository jobRepository,
            DefaultReviewExecutionHandler handler,
            PracticePiAdapter practiceAgent,
            WorkerJwtIssuer workerJwtIssuer,
            SandboxManager sandboxManager,
            SandboxProperties sandboxProperties,
            @Qualifier("sandboxExecutor") AsyncTaskExecutor sandboxExecutor,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ReviewJobTelemetry jobTelemetry,
            ReviewAttemptUsageService attemptUsage,
            PracticeScriptRunRecorder practiceScriptRuns,
            @Nullable LlmAdmissionService llmAdmissionService,
            Optional<WorkerCapacityState> capacityState,
            WorkerProperties workerProperties,
            ReviewJobClaimService claims,
            ReviewJobRecovery recovery) {
        this.agentProperties = agentProperties;
        this.claims = claims;
        this.recovery = recovery;
        this.jobRepository = jobRepository;
        this.handler = handler;
        this.practiceAgent = practiceAgent;
        this.workerJwtIssuer = workerJwtIssuer;
        this.sandboxManager = sandboxManager;
        this.reviewLimits = sandboxProperties.defaultResourceLimits();
        this.sandboxExecutor = sandboxExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.jobTelemetry = jobTelemetry;
        this.attemptUsage = attemptUsage;
        this.practiceScriptRuns = practiceScriptRuns;
        this.llmAdmissionService = llmAdmissionService;
        this.capacityState = capacityState;
        this.workerId = workerProperties.resolvedWorkerId();

        this.infraRetryRequeued = Counter.builder(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED)
                .description("Jobs requeued (not failed) after a retryable preparation or infrastructure failure")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2) // WorkerLivenessReporter must register this worker before the first claim.
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

    /** Join the poll thread before drain so an in-flight claim cannot register after the worker appears idle. */
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
            return inFlight.getUnarrivedParties() <= 1;
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

    // Share one cleanup deadline across all containers in a drain.
    private static final Duration DRAIN_CLEANUP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Settles this worker's jobs before stopping their containers. Unadmitted attempts may requeue;
     * admitted attempts retain their results and fail without repeating the review. The drain
     * contract is in docs/admin/runtime-roles.mdx.
     *
     * @return {@code true} if every job this worker still owned was settled and every container stop
     *     it issued returned; {@code false} if a settlement failed or a stop failed, did not return in
     *     time, or was interrupted
     */
    public boolean cancelInFlight(ReviewJobCancellationReason reason) {
        return cancelInFlight(reason, DRAIN_CLEANUP_TIMEOUT);
    }

    // Settle custody before stopping containers; stop only attempts whose settlement transition this worker won.
    boolean cancelInFlight(ReviewJobCancellationReason reason, Duration cleanupTimeout) {
        Set<UUID> snapshot = Set.copyOf(localRunningJobs.keySet());
        if (snapshot.isEmpty()) return true;
        log.info("Draining {} in-flight job(s) owned by this worker with reason {}", snapshot.size(), reason);
        boolean settledAll = true;
        List<UUID> settled = new ArrayList<>();
        for (UUID jobId : snapshot) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> settleForDrain(jobId, reason)))) {
                    settled.add(jobId);
                }
            } catch (Exception e) {
                settledAll = false;
                log.warn(
                        "Failed to settle in-flight job {} for drain: {}",
                        jobId,
                        e.getClass().getSimpleName());
            }
        }
        boolean stopped = stopContainers(settled, cleanupTimeout);
        return settledAll && stopped;
    }

    private boolean settleForDrain(UUID jobId, ReviewJobCancellationReason reason) {
        ReviewJob job = jobRepository.findById(jobId).orElse(null);
        return job != null
                && recovery.recover(jobId, workerId, job.getAttempt(), reason) != ReviewJobRecovery.Outcome.NONE;
    }

    // docker-java blocking I/O pins virtual threads; use a separate platform-thread pool.
    // Native stops may ignore interruption; settled custody and daemon threads permit shutdown.
    private boolean stopContainers(List<UUID> jobIds, Duration timeout) {
        if (jobIds.isEmpty()) return true;
        List<Callable<Object>> stops = jobIds.stream()
                .map(jobId -> Executors.callable(() -> sandboxManager.cancel(jobId)))
                .toList();
        ExecutorService pool = Executors.newFixedThreadPool(
                stops.size(),
                Thread.ofPlatform().name("agent-drain-stop-", 0).daemon(true).factory());
        try {
            List<Future<Object>> results = pool.invokeAll(stops, timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean acknowledged = true;
            for (int i = 0; i < results.size(); i++) {
                Future<Object> result = results.get(i);
                if (result.state() == Future.State.SUCCESS) continue;
                acknowledged = false;
                log.warn(
                        "Container stop for drained job {} {}",
                        jobIds.get(i),
                        result.state() == Future.State.FAILED
                                ? "failed: " + result.exceptionNow().getClass().getSimpleName()
                                : "did not return within " + timeout);
            }
            return acknowledged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping {} drained job container(s)", jobIds.size());
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Stops the container only; whoever ended or requeued the attempt already wrote its status.
     *
     * @return true if this worker owns the job and a stop was requested
     */
    public boolean cancelLocalJob(UUID jobId, String reason) {
        if (!localRunningJobs.containsKey(jobId)) {
            return false;
        }
        log.info("Stopping locally running job {}: {}", jobId, reason);
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

                boolean anyStarted = false;
                boolean poolRejected = false;
                for (UUID jobId : candidates) {
                    if (!running.get()) {
                        break;
                    }
                    if (poolRejected) {
                        break;
                    }
                    StartOutcome outcome = processJob(jobId);
                    poolRejected = outcome == StartOutcome.POOL_REJECTED;
                    anyStarted |= outcome == StartOutcome.STARTED;
                }
                // Busy-spin protection: without this backoff, an all-skipped batch re-queries immediately
                // and a saturated pool claims-then-requeues in a tight loop, hammering the DB.
                if (!anyStarted || poolRejected) {
                    sleepPollInterval();
                }
            } catch (Exception e) {
                log.warn(
                        "Poll loop error, retrying in {}: {}",
                        agentProperties.pollInterval(),
                        e.getClass().getName());
                sleepPollInterval();
            }
        }
        log.info("Agent job executor poll loop stopped");
    }

    // Pool size bounds claims independently of configured worker capacity.
    int computeCapacity() {
        int concurrency = Math.min(
                capacityState.map(WorkerCapacityState::reviewMax).orElse(Integer.MAX_VALUE),
                sandboxExecutorConcurrency());
        int free = concurrency == Integer.MAX_VALUE
                ? agentProperties.claimBatchSize()
                : Math.max(0, concurrency - localRunningJobs.size());
        return Math.min(free, agentProperties.claimBatchSize());
    }

    private int sandboxExecutorConcurrency() {
        return sandboxExecutor instanceof ThreadPoolTaskExecutor pool ? pool.getMaxPoolSize() : Integer.MAX_VALUE;
    }

    // Jitter prevents replicas from polling together and amplifying claim contention.
    private void sleepPollInterval() {
        try {
            double jitterMultiplier = 0.9 + (ThreadLocalRandom.current().nextDouble() * 0.2);
            long millis = Math.round(agentProperties.pollInterval().toMillis() * jitterMultiplier);
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    enum StartOutcome {
        SKIPPED,
        STARTED,
        POOL_REJECTED
    }

    StartOutcome processJob(UUID jobId) {
        // The poll offers only queued jobs: an attempt of this job that still runs here was requeued. Stop it, and
        // claim
        // the job once it ended, so one job never has two sandboxes on this worker.
        if (localRunningJobs.containsKey(jobId)) {
            cancelLocalJob(jobId, "the job was requeued while this attempt still ran");
            return StartOutcome.SKIPPED;
        }
        ReviewJobClaimService.ClaimAttempt attempt;
        try {
            attempt = claims.claimJob(jobId);
        } catch (CannotAcquireLockException e) {
            log.debug("Lock timeout during claim for job {}, will retry on next poll", jobId);
            return StartOutcome.SKIPPED;
        } catch (Exception e) {
            log.warn(
                    "Claim failed for job {}, will retry on next poll: {}",
                    jobId,
                    e.getClass().getName());
            return StartOutcome.SKIPPED;
        }
        if (attempt instanceof ReviewJobClaimService.TerminalClaim terminal) {
            jobTelemetry.terminal(terminal.job(), terminal.status(), ReviewJobTelemetry.age(terminal.job()));
            return StartOutcome.SKIPPED;
        }
        if (!(attempt instanceof ReviewJobClaimService.ClaimResult claim)) {
            return StartOutcome.SKIPPED;
        }
        // Counted after the claim commits, never inside its transaction: a failed commit rolls the row
        // back to QUEUED and no execution then runs to release the count. Since the count is the hard
        // claim bound, one leaked entry would retire a slot for this worker's lifetime.
        localRunningJobs.put(jobId, claim.job().getAttempt());
        capacityState.ifPresent(WorkerCapacityState::claimReview);
        return startExecution(jobId, claim);
    }

    private StartOutcome startExecution(UUID jobId, ReviewJobClaimService.ClaimResult claim) {
        try {
            inFlight.register();
            sandboxExecutor.execute(() -> {
                try {
                    runClaimedJob(jobId, claim);
                } finally {
                    inFlight.arriveAndDeregister();
                }
            });
            return StartOutcome.STARTED;
        } catch (RejectedExecutionException e) {
            inFlight.arriveAndDeregister();
            log.warn(
                    "Sandbox executor rejected claimed job {} (pool smaller than configured worker capacity?) —"
                            + " requeuing",
                    jobId);
            requeueRejectedClaim(jobId);
            return StartOutcome.POOL_REJECTED;
        }
    }

    // Pool rejection must not consume an execution attempt.
    // If requeue fails, retain RUNNING ownership for dead-worker recovery rather than rerunning provider work.
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

    private void runClaimedJob(UUID jobId, ReviewJobClaimService.ClaimResult claim) {
        ReviewJob job = claim.job();
        var executionSpan = jobTelemetry.startExecution(job);
        var executionScope = jobTelemetry.executionScope(executionSpan);
        MDC.put(StructuredLogKeys.TRACE_ID, executionSpan.context().traceId());
        MDC.put(StructuredLogKeys.SPAN_ID, executionSpan.context().spanId());
        MDC.put(
                StructuredLogKeys.TRACE_FLAGS,
                Boolean.TRUE.equals(executionSpan.context().sampled()) ? "01" : "00");
        MDC.put(MDC_JOB_ID, jobId.toString());
        MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(job.getWorkspaceId()));
        MDC.put(MDC_JOB_TYPE, claim.itemKind());
        Instant startTime = Instant.now();
        String metricOutcome = "unknown";
        ReviewJobStatus recordedTerminal = null;
        boolean sandboxExecutionStarted = false;
        var preparationStage = new AtomicReference<>(PreparationFailure.Stage.RUN_SETUP);
        PreparedJobInputs stagedInputs = null;
        try {
            Duration queueDuration = Duration.between(job.getSubmittedAt(), job.getStartedAt());
            jobTelemetry.transition(
                    job,
                    "agent.job.started",
                    ReviewJobTelemetry.Phase.QUEUE,
                    ReviewJobTelemetry.ResultOutcome.STARTED,
                    queueDuration);

            PreparedSandbox preparedSandbox = prepareSandboxSpec(jobId, job, claim.snapshot(), preparationStage);
            stagedInputs = preparedSandbox.stagedInputs();
            SandboxSpec sandboxSpec = preparedSandbox.spec();
            if (sandboxSpec == null) {
                recordedTerminal = transactionTemplate.execute(status -> {
                    int updated =
                            transitionTerminal(jobId, job.getAttempt(), ReviewJobStatus.COMPLETED, Instant.now(), null);
                    if (updated == 0) return null;
                    var completed = jobRepository.findById(jobId).orElseThrow();
                    completed.setProcessingStatus(ProcessingStatus.PENDING);
                    jobRepository.saveAndFlush(completed);
                    return ReviewJobStatus.COMPLETED;
                });
                metricOutcome = recordedTerminal == null ? "OWNERSHIP_LOST" : recordedTerminal.name();
                return;
            }
            // Past this boundary provider usage may exist even if execute() throws, so it is persisted
            // for recovery on another process. A lost fence means the job was cancelled or requeued
            // while preparation ran, so its sandbox must not start.
            preparationStage.set(PreparationFailure.Stage.EXECUTION_FENCE);
            if (!markExecutionStarted(jobId, job.getAttempt())) {
                metricOutcome = "OWNERSHIP_LOST";
                log.info("Skipped sandbox start after execution fence was lost: jobId={}", jobId);
                return;
            }
            sandboxExecutionStarted = true;
            recordedTerminal = executePrepared(jobId, job, sandboxSpec);
            metricOutcome = recordedTerminal == null ? "REQUEUED_OR_OWNERSHIP_LOST" : recordedTerminal.name();
        } catch (SandboxCancelledException e) {
            recordedTerminal = handleCancellation(jobId, job) ? ReviewJobStatus.CANCELLED : null;
            metricOutcome = recordedTerminal != null ? recordedTerminal.name() : "OWNERSHIP_LOST";
        } catch (WorkspaceBudgetExceededException e) {
            JsonNode output = objectMapper.valueToTree(new RefusedExecution(
                    RefusalOutcome.WORKSPACE_REFUSED,
                    RefusalReason.WORKSPACE_BUDGET_EXCEEDED,
                    e.getBody().getProperties()));
            Integer updated = transactionTemplate.execute(status -> jobRepository.transitionToEvidenceRefused(
                    jobId, workerId, job.getAttempt(), Instant.now(), output));
            metricOutcome = updated != null && updated == 1 ? "WORKSPACE_BUDGET_EXCEEDED" : "OWNERSHIP_LOST";
            if (updated != null && updated == 1) {
                recordPracticeReviewRefusal("workspace_budget_exceeded");
                recordedTerminal = ReviewJobStatus.FAILED;
            }
        } catch (InsufficientEvidenceException e) {
            ExecutionOutcome refusal = persistEvidenceRefusal(jobId, job, e);
            metricOutcome = refusal.metricOutcome();
            recordedTerminal = refusal.terminal();
        } catch (TerminalPersistenceException e) {
            // Provider work already completed. Leave RUNNING for the zombie sweeper to terminalize
            // and account from proxy counts; never execute the provider a second time.
            log.error("Terminal job persistence failed after provider completion: jobId={}", jobId, e);
            metricOutcome = "PERSISTENCE_FAILED";
        } catch (Exception e) {
            FailureOutcome failure = handleExecutionFailure(
                    jobId,
                    job,
                    e,
                    sandboxExecutionStarted,
                    sandboxExecutionStarted ? null : PreparationFailure.of(preparationStage.get(), e));
            metricOutcome = failure.name();
            recordedTerminal = failure.terminal();
        } finally {
            try {
                recordAttemptOutcome(job, claim.itemKind(), metricOutcome, recordedTerminal, startTime, stagedInputs);
            } finally {
                releaseCapacity();
                localRunningJobs.remove(jobId, job.getAttempt());
                MDC.remove(MDC_JOB_ID);
                MDC.remove(StructuredLogKeys.WORKSPACE_ID);
                MDC.remove(MDC_JOB_TYPE);
                MDC.remove(StructuredLogKeys.TRACE_ID);
                MDC.remove(StructuredLogKeys.SPAN_ID);
                MDC.remove(StructuredLogKeys.TRACE_FLAGS);
                executionSpan.tag("hephaestus.job.outcome", metricOutcome);
                executionScope.close();
                executionSpan.end();
            }
        }
    }

    private void recordAttemptOutcome(
            ReviewJob job,
            String itemKind,
            String metricOutcome,
            @Nullable ReviewJobStatus terminal,
            Instant startTime,
            @Nullable PreparedJobInputs stagedInputs) {
        if (stagedInputs != null) stagedInputs.close();
        Duration duration = Duration.between(startTime, Instant.now());
        recordExecutionDuration(itemKind, metricOutcome, duration);
        jobTelemetry.record(ReviewJobTelemetry.Phase.EXECUTION, duration);
        if (terminal != null) jobTelemetry.terminal(job, terminal, ReviewJobTelemetry.age(job));
    }

    private record ExecutionOutcome(
            String metricOutcome, @Nullable ReviewJobStatus terminal) {}

    private ExecutionOutcome persistEvidenceRefusal(UUID jobId, ReviewJob job, InsufficientEvidenceException e) {
        // This evidence was not staged; no attempt owns its cleanup.
        try (PreparedJobInputs refused = e.preparedInputs()) {
            persistPreparedEvidence(jobId, job.getWorkspaceId(), job.getAttempt(), refused);
            JsonNode output =
                    objectMapper.valueToTree(new RefusedExecution(RefusalOutcome.INSUFFICIENT_EVIDENCE, null, null));
            Integer updated = transactionTemplate.execute(status -> jobRepository.transitionToEvidenceRefused(
                    jobId, workerId, job.getAttempt(), Instant.now(), output));
            if (updated != null && updated == 1) {
                recordPracticeReviewRefusal("insufficient_evidence");
                return new ExecutionOutcome("INSUFFICIENT_EVIDENCE", ReviewJobStatus.FAILED);
            } else {
                return new ExecutionOutcome("OWNERSHIP_LOST", null);
            }
        } catch (Exception persistenceFailure) {
            FailureOutcome failure = handleExecutionFailure(
                    jobId,
                    job,
                    persistenceFailure,
                    false,
                    PreparationFailure.of(PreparationFailure.Stage.PROVENANCE, persistenceFailure));
            return new ExecutionOutcome(failure.name(), failure.terminal());
        }
    }

    private enum RefusalReason {
        WORKSPACE_BUDGET_EXCEEDED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RefusedExecution(
            RefusalOutcome outcome,
            @Nullable RefusalReason reasonCode,
            @Nullable Map<String, Object> details) {}

    @Nullable
    ReviewJobStatus executePrepared(UUID jobId, ReviewJob job, SandboxSpec sandboxSpec) {
        SandboxResult result = sandboxManager.execute(sandboxSpec);
        AgentResult agentResult = practiceAgent.parseResult(result);

        // Two exits say the run could not reach something it needed, rather than anything about the
        // reviewed work: the review finished and could not admit it, because the sandbox holds an
        // address the server has since moved away from; or no practice was reached at all because
        // every model call went unanswered. Neither leaves anything to deliver, and neither is a
        // fact a second attempt would repeat. Past the retry cap both fall through and terminalize.
        String unreachable = unreachableReason(result.exitCode());
        if (unreachable != null && requeueForAnotherAttempt(job, true)) {
            log.warn(
                    "Requeuing job {} ({}): nothing it measured can be delivered from here (attempt {})",
                    jobId,
                    unreachable,
                    job.getAttempt() + 1);
            return null;
        }
        // An admitted run that stopped composing its feedback continues it as a new attempt when the locked job owes it
        // and its fences hold. A run the sandbox stopped at its time limit had no chance to say so; the same locked
        // job,
        // its recorded preparation and the same fences decide.
        if ((result.exitCode() == SandboxLayout.EXIT_COMPOSITION_OWED || result.timedOut())
                && continueComposition(job, agentResult, result)) {
            log.warn(
                    "Requeuing job {}: feedback composition stopped before it finished (attempt {})",
                    jobId,
                    job.getAttempt() + 1);
            return null;
        }

        ReviewJobStatus terminalStatus = persistTerminalState(jobId, job.getAttempt(), agentResult, result);
        ReviewCompositionContinuation kept = compositionContinuation;
        if (terminalStatus != null && kept != null) {
            // Its result is recorded: no writer reads the original inputs again. The retention sweep removes
            // what a failure here leaves.
            try {
                kept.forget(job);
            } catch (RuntimeException e) {
                log.warn("Could not remove original composition inputs: jobId={}", jobId, e);
            }
        }

        return terminalStatus;
    }

    static String randomSpanId() {
        long value;
        do {
            value = ThreadLocalRandom.current().nextLong();
        } while (value == 0);
        return "%016x".formatted(value);
    }

    private boolean markExecutionStarted(UUID jobId, int attempt) {
        Integer updated = transactionTemplate.execute(
                status -> jobRepository.markExecutionStarted(jobId, workerId, attempt, Instant.now()));
        return updated != null && updated == 1;
    }

    private void recordExecutionDuration(String itemKind, String outcome, Duration duration) {
        Timer.builder(AgentMetrics.AGENT_JOB_EXECUTION_DURATION)
                .description("Total duration of agent job execution")
                .tag("itemKind", itemKind)
                .tag("status", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    // The caller owns prepared-input cleanup through execution and admission, on every outcome.
    private record PreparedSandbox(@Nullable SandboxSpec spec, PreparedJobInputs stagedInputs) {}

    private PreparedSandbox prepareSandboxSpec(
            UUID jobId, ReviewJob job, ConfigSnapshot snapshot, AtomicReference<PreparationFailure.Stage> stage) {
        stage.set(PreparationFailure.Stage.INPUTS);

        ReviewJob preparedJob = jobRepository.findById(jobId).orElse(job);
        // A continuing writer captures nothing: it is staged from its admission's original inputs, and the job keeps
        // the evidence record that admission verified against.
        boolean continuation = ResultAdmissionService.isContinuation(preparedJob);
        ReviewCompositionContinuation continuing = compositionContinuation;
        if (continuation && continuing == null) {
            throw new JobPreparationException("The review cannot continue: this runtime cannot stage its writer");
        }
        PreparedJobInputs preparedInputs = continuation
                ? Objects.requireNonNull(continuing).prepareInputs(preparedJob)
                : handler.prepareInputs(preparedJob);

        try {
            var report = preparedInputs.automatedReviewReadinessReport();
            if (!continuation && report != null && report.decisions().stream().noneMatch(report::runs)) {
                stage.set(PreparationFailure.Stage.PROVENANCE);
                persistPreparedEvidence(jobId, job.getWorkspaceId(), job.getAttempt(), preparedInputs);
                handler.completeWithoutExecution(jobRepository.findById(jobId).orElseThrow());
                return new PreparedSandbox(null, preparedInputs);
            }
            stage.set(PreparationFailure.Stage.CREDENTIAL);
            // Sandboxes access providers through the LLM proxy with an attempt-scoped credential.
            Instant workDeadline =
                    Instant.now().plusSeconds(snapshot.timeoutSeconds()).truncatedTo(ChronoUnit.SECONDS);
            Instant tokenExpiry = workDeadline.plus(SandboxLayout.RESULT_UPLOAD_GRACE);
            String jobToken = workerJwtIssuer.issueForJobUntil(
                    jobId, job.getWorkspaceId(), job.getAttempt(), workDeadline, tokenExpiry);
            stage.set(PreparationFailure.Stage.SPEC);
            PracticeAgentRequest adapterRequest = new PracticeAgentRequest(
                    snapshot.model().apiProtocol().wire(),
                    snapshot.model().upstreamModelId(),
                    snapshot.contextWindow(),
                    snapshot.maxOutputTokens(),
                    snapshot.model().reasoningEffort(),
                    jobToken,
                    snapshot.timeoutSeconds(),
                    snapshot.practiceScriptModels());

            PracticeSandboxSpec agentSpec = practiceAgent.buildSandboxSpec(adapterRequest);
            SandboxSpec sandboxSpec = buildSandboxSpec(
                    jobId,
                    preparedInputs.filesOnDisk(),
                    preparedInputs.directories(),
                    agentSpec,
                    snapshot,
                    workDeadline,
                    job.getAttempt());
            stage.set(PreparationFailure.Stage.PROVENANCE);
            if (!continuation) {
                persistProvenanceDigests(
                        jobId,
                        job.getWorkspaceId(),
                        agentSpec.promptDigest(),
                        sandboxSpec.inputFiles(),
                        preparedInputs.filesOnDisk(),
                        preparedInputs.directories(),
                        job.getAttempt(),
                        preparedInputs.automatedReviewReadinessReport(),
                        preparedInputs.copiesExpireAt());
            }
            return new PreparedSandbox(sandboxSpec, preparedInputs);
        } catch (RuntimeException exception) {
            preparedInputs.close();
            throw exception;
        }
    }

    private void persistPreparedEvidence(UUID jobId, long workspaceId, int attempt, PreparedJobInputs preparedInputs) {
        persistProvenanceDigests(
                jobId,
                workspaceId,
                null,
                Map.of(),
                preparedInputs.filesOnDisk(),
                preparedInputs.directories(),
                attempt,
                preparedInputs.automatedReviewReadinessReport(),
                preparedInputs.copiesExpireAt());
    }

    // Persist provenance before provider execution; results without their original input identity cannot be admitted.
    private void persistProvenanceDigests(
            UUID jobId,
            long workspaceId,
            @Nullable String promptDigest,
            Map<String, byte[]> inputFiles,
            Map<String, Path> inputPaths,
            List<EvidenceDirectory> inputDirectories,
            int attempt,
            @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport,
            @Nullable Instant copiesExpireAt) {
        String inputsDigest = ProvenanceDigest.inputsDigestHex(inputFiles, inputPaths, inputDirectories, jobId);
        JsonNode capture = capture(snapshotMetadata(inputFiles, inputPaths), automatedReviewReadinessReport);
        var stamp = capture instanceof ObjectNode object ? object : objectMapper.createObjectNode();
        if (promptDigest != null) stamp.put("promptDigest", promptDigest);
        stamp.put("inputsDigest", inputsDigest);
        // The attempt's transcript binds to this, so no copy it keeps outlives the occasion record its inputs copy.
        if (copiesExpireAt != null) stamp.put(JobEvidenceFiles.COPIES_EXPIRE_AT, copiesExpireAt.toString());
        stamp.set(
                "directories",
                objectMapper.valueToTree(
                        inputDirectories.stream().map(EvidenceDirectory::target).toList()));
        Integer updated = transactionTemplate.execute(
                status -> jobRepository.updateCapture(jobId, workspaceId, workerId, attempt, stamp));
        if (updated == null || updated != 1) {
            throw new IllegalStateException("Provenance digest write matched no job row: jobId=" + jobId);
        }
        log.debug("Provenance digests: jobId={}, prompt={}, inputs={}", jobId, promptDigest, inputsDigest);
    }

    private static Map<String, byte[]> snapshotMetadata(Map<String, byte[]> scaffolding, Map<String, Path> paths) {
        var metadata = new HashMap<>(scaffolding);
        for (String path : List.of(
                SandboxLayout.MANIFEST_PATH,
                SandboxLayout.PRACTICES_PREFIX + "index.json",
                SandboxLayout.CONTEXT_PREFIX + "metadata.json",
                SandboxLayout.CONTEXT_PREFIX + "issue_metadata.json")) {
            Path source = paths.get(path);
            if (source != null) {
                try {
                    metadata.put(path, Files.readAllBytes(source));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return metadata;
    }

    private @Nullable JsonNode capture(
            Map<String, byte[]> inputFiles, @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        byte[] manifest = inputFiles.get(SandboxLayout.MANIFEST_PATH);
        byte[] practices = inputFiles.get(SandboxLayout.PRACTICES_PREFIX + "index.json");
        // Java null, not NullNode: NullNode serializes to the JSON value null, which is a non-SQL-NULL
        // jsonb and so passes every IS NOT NULL predicate a reader writes against this column.
        if (manifest == null && practices == null && automatedReviewReadinessReport == null) return null;
        if (manifest == null) {
            throw new IllegalStateException("Practice review inputs have an incomplete evidence snapshot");
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("manifest", objectMapper.readTree(manifest));
        if (practices != null) {
            snapshot.set("practices", objectMapper.readTree(practices));
        }
        if (automatedReviewReadinessReport != null)
            snapshot.set("readiness", objectMapper.valueToTree(automatedReviewReadinessReport));
        return snapshot;
    }

    private SandboxSpec buildSandboxSpec(
            UUID jobId,
            Map<String, Path> handlerFilesOnDisk,
            List<EvidenceDirectory> handlerDirectories,
            PracticeSandboxSpec agentSpec,
            ConfigSnapshot snapshot,
            Instant workDeadline,
            int attempt) {
        ResourceLimits limits = reviewLimits.forAttempt(Duration.ofSeconds(snapshot.timeoutSeconds()));

        return new SandboxSpec(
                jobId,
                attempt,
                agentSpec.image(),
                agentSpec.command(),
                agentSpec.environment(),
                agentSpec.gatewayToken(),
                limits,
                workDeadline,
                agentSpec.inputFiles(),
                handlerFilesOnDisk,
                handlerDirectories);
    }

    private boolean handleCancellation(UUID jobId, ReviewJob job) {
        Boolean cancelled = transactionTemplate.execute(status -> {
            int updated = transitionTerminal(
                    jobId, job.getAttempt(), ReviewJobStatus.CANCELLED, Instant.now(), "Cancelled during execution");
            if (updated > 0) billTerminatedJob(job, "cancelled during execution");
            return updated > 0;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            log.info("Agent job cancelled: jobId={}", jobId);
            return true;
        }
        return false;
    }

    // Read counters here only on terminal paths; requeue clears them.
    private void billTerminatedJob(ReviewJob job, String reason) {
        billTerminatedJob(
                job, reason, jobRepository.findLlmUsageById(job.getId()).orElse(null));
    }

    // The counters only start an attempt measurement that no proxied call registered.
    private void billTerminatedJob(ReviewJob job, String reason, @Nullable ReviewJobLlmUsage counts) {
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        LlmPriceSnapshot price = terminalPriceOrUnpriced(snapshot);
        // No runner report exists on this path by definition — the job never reached a clean finish.
        ReviewAttemptUsageService.Settled settled =
                attemptUsage.settleAttempt(job, FrozenCharge.admitted(snapshot, price), null, counts);
        if (settled.billed()) {
            log.info(
                    "Settled usage for terminated job ({}): jobId={}, calls={}",
                    reason,
                    job.getId(),
                    settled.usage().totalCalls());
        } else {
            log.info("Recorded UNPRICED usage ledger entry ({}): jobId={}", reason, job.getId());
        }
    }

    private FailureOutcome handleExecutionFailure(
            UUID jobId,
            ReviewJob job,
            Exception e,
            boolean sandboxExecutionStarted,
            @Nullable PreparationFailure preparation) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }

        String errorMessage = preparation != null
                ? preparation.message()
                : e instanceof SandboxInfrastructureException
                        ? "The sandbox infrastructure failed during execution."
                        : e instanceof IOException
                                ? "A file or network operation failed during execution."
                                : e instanceof InterruptedException
                                        ? "The worker interrupted execution."
                                        : "The job failed during execution.";
        if (preparation == null) {
            log.error("Agent job failed: jobId={}, error={}", jobId, errorMessage, e);
        }

        if (isRetryableExecutionFailure(e)) {
            int currentRetryCount = job.getAttempt();
            if (requeueForAnotherAttempt(job, sandboxExecutionStarted)) {
                log.warn(
                        "Requeuing job {} after retryable preparation or infrastructure failure (attempt {}): {}",
                        jobId,
                        currentRetryCount + 1,
                        errorMessage);
                if (preparation != null) jobTelemetry.preparationFailed(job, preparation, "REQUEUED");
                return FailureOutcome.REQUEUED;
            }
            log.warn(
                    "Job {} hit a retryable failure but could not be requeued (retry cap exhausted or fence lost) —"
                            + " failing terminally",
                    jobId);
        }

        Integer failed = transactionTemplate.execute(status -> {
            int updated =
                    transitionTerminal(jobId, job.getAttempt(), ReviewJobStatus.FAILED, Instant.now(), errorMessage);
            if (updated > 0 && preparation != null) {
                var diagnosis = objectMapper
                        .createObjectNode()
                        .put("attempt", job.getAttempt())
                        .put("stage", preparation.stage().name())
                        .put("kind", preparation.kind().name())
                        .put("reason", preparation.reason().name());
                jobRepository.recordPreparationFailure(
                        jobId,
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        workerId,
                        objectMapper.writeValueAsString(diagnosis));
            }
            if (updated > 0 && sandboxExecutionStarted) billTerminatedJob(job, "execution failure");
            return updated;
        });
        String outcome = failed != null && failed > 0 ? ReviewJobStatus.FAILED.name() : "OWNERSHIP_LOST";
        if (preparation != null) jobTelemetry.preparationFailed(job, preparation, outcome);
        return failed != null && failed > 0 ? FailureOutcome.FAILED : FailureOutcome.OWNERSHIP_LOST;
    }

    private enum FailureOutcome {
        REQUEUED,
        FAILED,
        OWNERSHIP_LOST;

        @Nullable
        ReviewJobStatus terminal() {
            return this == FAILED ? ReviewJobStatus.FAILED : null;
        }
    }

    /**
     * Deliberately narrower than {@link SandboxException}, which also covers deterministic
     * validation/config failures and a catch-all wrap of an unknown defect: retrying either would burn
     * the retry budget on a failure that was never going to resolve itself.
     */
    static boolean isRetryableExecutionFailure(Exception e) {
        return e instanceof ReviewSourceNotReadyException
                || e instanceof SandboxInfrastructureException
                || e instanceof IOException;
    }

    private static @Nullable String unreachableReason(int exitCode) {
        if (exitCode == SandboxLayout.EXIT_SERVER_UNREACHABLE) {
            return "server-unreachable";
        }
        if (exitCode == SandboxLayout.EXIT_PROVIDER_UNREACHABLE) {
            return "provider-unreachable";
        }
        return null;
    }

    // Bill within the requeue transaction before it clears usage counters.
    private boolean requeueForAnotherAttempt(ReviewJob job, boolean bill) {
        Boolean updated = transactionTemplate.execute(status -> recovery.retryUnadmitted(job, workerId, bill));
        if (!Boolean.TRUE.equals(updated)) return false;
        infraRetryRequeued.increment();
        return true;
    }

    private boolean continueComposition(ReviewJob job, AgentResult agentResult, SandboxResult result) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> recovery.continueComposition(
                job,
                workerId,
                agentResult.usage(),
                result.outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE),
                result.outputFiles().get(SandboxLayout.FEEDBACK_FILENAME))));
    }

    private void recordPracticeReviewRefusal(String reason) {
        ReviewRefusalRecorder.record(meterRegistry, "execution", reason);
    }

    private void releaseCapacity() {
        capacityState.ifPresent(WorkerCapacityState::releaseReview);
    }

    // Fence late terminal writes on worker and attempt so they cannot end an attempt that was requeued since.
    private int transitionTerminal(
            UUID jobId, int attempt, ReviewJobStatus status, Instant now, @Nullable String error) {
        return jobRepository.transitionStatusOwnedBy(jobId, status, now, error, workerId, attempt);
    }

    // A failed runner's draft can reach delivery only through results the server already admitted.
    ReviewJobStatus determineTerminalStatus(SandboxResult sandboxResult, AgentResult agentResult, ReviewJob job) {
        if (sandboxResult.timedOut()) {
            return ReviewJobStatus.TIMED_OUT;
        }
        if (sandboxResult.exitCode() == 0) {
            // A clean exit without a valid result is the runner's failure, not a completed review.
            return agentResult != null && agentResult.success() ? ReviewJobStatus.COMPLETED : ReviewJobStatus.FAILED;
        }
        // The runner exits 42 before any model call when it cannot use the staged task envelope. Operators need to see
        // this apart from agent crashes; the runner's own log line names which check failed.
        if (sandboxResult.exitCode() == SandboxLayout.EXIT_ENVELOPE_MISMATCH) {
            log.error(
                    "Pi runner could not use the staged task envelope (exit {}): jobId={}, attempt={}. It is missing or"
                            + " unreadable, has another schemaVersion than the image, or has an invalid field; the"
                            + " runner log names which.",
                    SandboxLayout.EXIT_ENVELOPE_MISMATCH,
                    job.getId(),
                    job.getAttempt());
            meterRegistry.counter(AgentMetrics.AGENT_PI_ENVELOPE_MISMATCH).increment();
            return ReviewJobStatus.FAILED;
        }
        if (agentResult != null && agentResult.output() != null) {
            Object rawOutput = agentResult.output().get("rawOutput");
            if (rawOutput instanceof String raw && !raw.isBlank() && ResultAdmissionService.isAdmitted(job)) {
                log.info(
                        "Agent exited with code {} after result admission — continuing delivery",
                        sandboxResult.exitCode());
                return ReviewJobStatus.COMPLETED;
            }
            if (rawOutput != null && !(rawOutput instanceof String)) {
                log.warn(
                        "Agent exited with code {} and rawOutput is present but not a String (type={})",
                        sandboxResult.exitCode(),
                        rawOutput.getClass().getSimpleName());
            }
        }
        return ReviewJobStatus.FAILED;
    }

    // Retain RUNNING state if accounting cannot commit; rerunning completed provider work would bill it twice.
    // The sweeper ends the attempt and records proxy usage.
    private @Nullable ReviewJobStatus persistTerminalState(
            UUID jobId, int attempt, AgentResult agentResult, SandboxResult sandboxResult) {
        JsonNode output = objectMapper.valueToTree(agentResult.output());
        boolean storable = textRepresentableInJsonb(output);
        if (!storable) {
            log.warn("Runner result has text jsonb cannot represent; recording it as not stored: jobId={}", jobId);
        }
        try {
            return terminalPersistRetries.execute(named(
                    "Terminal persistence for jobId=" + jobId,
                    () -> persistTerminalStateOnce(jobId, attempt, agentResult, sandboxResult, output, storable)));
        } catch (RetryException e) {
            throw new TerminalPersistenceException(e);
        }
    }

    private @Nullable ReviewJobStatus persistTerminalStateOnce(
            UUID jobId,
            int attempt,
            AgentResult agentResult,
            SandboxResult sandboxResult,
            JsonNode output,
            boolean storable) {
        return transactionTemplate.execute(status -> {
            ReviewJob locked = jobRepository.lockById(jobId).orElse(null);
            if (locked == null) return null;
            ReviewJobStatus determined = determineTerminalStatus(sandboxResult, agentResult, locked);
            ReviewJobStatus terminalStatus =
                    !storable && determined == ReviewJobStatus.COMPLETED ? ReviewJobStatus.FAILED : determined;
            String failureMessage = sandboxResult.exitCode() == 0
                    ? "The runner did not return a valid result."
                    : "The container exited with code " + sandboxResult.exitCode() + ".";
            String errorMessage =
                    switch (terminalStatus) {
                        case TIMED_OUT -> "The container timed out.";
                        case FAILED -> storable ? failureMessage : UNSTORABLE_RESULT_MESSAGE;
                        default -> null;
                    };
            Instant finishedAt = Instant.now();
            int updated = transitionTerminal(jobId, attempt, terminalStatus, finishedAt, errorMessage);
            if (updated == 0) {
                log.info("Job no longer owned/RUNNING, skipping output persist: jobId={}", jobId);
                return null;
            }

            ReviewJob freshJob = jobRepository.findById(jobId).orElseThrow();
            // Read the counters BEFORE the selected totals are written over them below: they start the attempt's
            // measurement when no proxied call registered one.
            ReviewJobLlmUsage proxyCounts =
                    jobRepository.findLlmUsageById(jobId).orElse(null);
            ConfigSnapshot snapshot = ConfigSnapshot.fromJson(freshJob.getConfigSnapshot(), objectMapper);
            LlmPriceSnapshot price = admittedPrice(snapshot);

            // Admitted claims require their complete supporting text.
            freshJob.setOutput(
                    storable ? output : objectMapper.createObjectNode().put("resultNotStored", UNSTORABLE_TEXT));
            freshJob.setExitCode(sandboxResult.exitCode());

            if (terminalStatus == ReviewJobStatus.COMPLETED) {
                freshJob.setProcessingStatus(ProcessingStatus.PENDING);
            }

            var runnerUsage = agentResult.usage();
            // The attempt's measurement keeps the runner's report and projects one coherent observed token vector,
            // including a proxy-only fallback when the runner never wrote usage.json.
            TerminalUsage usage = attemptUsage
                    .settleAttempt(freshJob, FrozenCharge.admitted(snapshot, price), runnerUsage, proxyCounts)
                    .usage();

            // Use the ledger totals: the runner may omit usage details counted by the proxy.
            if (runnerUsage != null && runnerUsage.totalCalls() > 0) {
                freshJob.setLlmCalls(usage.totalCalls());
                freshJob.setLlmInputTokens(Math.clamp(usage.inputTokens(), 0, Integer.MAX_VALUE));
                freshJob.setLlmOutputTokens(Math.clamp(usage.outputTokens(), 0, Integer.MAX_VALUE));
                freshJob.setLlmReasoningTokens(Math.clamp(usage.reasoningTokens(), 0, Integer.MAX_VALUE));
                freshJob.setLlmCacheReadTokens(Math.clamp(usage.cacheReadTokens(), 0, Integer.MAX_VALUE));
                freshJob.setLlmCacheWriteTokens(Math.clamp(usage.cacheWriteTokens(), 0, Integer.MAX_VALUE));
            }
            // Provider output is telemetry only. The admitted snapshot is authoritative identity.

            jobRepository.saveAndFlush(freshJob);

            practiceScriptRuns.record(
                    freshJob,
                    sandboxResult.outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE),
                    snapshot.practiceScriptModels(),
                    finishedAt);
            return terminalStatus;
        });
    }

    static final String UNSTORABLE_RESULT_MESSAGE =
            "The runner returned a result with text the database cannot store, so the result was not kept.";
    private static final String UNSTORABLE_TEXT = "The text contains a NUL character or an unpaired surrogate.";

    // jsonb rejects decoded NUL and malformed UTF-16 in values and keys; literal escape text remains valid.
    private static boolean textRepresentableInJsonb(JsonNode node) {
        if (node.isString()) return StorableText.isStorable(node.asString());
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!StorableText.isStorable(property.getKey()) || !textRepresentableInJsonb(property.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (!textRepresentableInJsonb(element)) return false;
            }
        }
        return true;
    }

    private static final class TerminalPersistenceException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private TerminalPersistenceException(Throwable cause) {
            super("Could not durably persist terminal job result and usage", cause);
        }
    }

    private LlmPriceSnapshot admittedPrice(ConfigSnapshot snapshot) {
        if (snapshot.model().priceSnapshot() != null) return snapshot.model().priceSnapshot();
        if (llmAdmissionService != null) {
            throw new IllegalStateException("Started job has no admitted LLM price snapshot");
        }
        return LlmPriceSnapshot.unpricedInstance();
    }

    // Price lookup must not throw and roll back a completed attempt; unavailable rates stay UNPRICED.
    private LlmPriceSnapshot terminalPriceOrUnpriced(ConfigSnapshot snapshot) {
        LlmPriceSnapshot price = snapshot.model().priceSnapshot();
        return price != null ? price : LlmPriceSnapshot.unpricedInstance();
    }
}
