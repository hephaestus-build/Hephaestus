package de.tum.cit.aet.hephaestus.agent.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.metrics.ReviewStageTelemetry;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.StorableText;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInitializationException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
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

    /**
     * How long past its time limit an attempt's run is still renewed: its results upload within this grace. After it,
     * its lease expires and the reaper hands the attempt on.
     */
    static final Duration ATTEMPT_GRACE = SandboxLayout.RESULT_UPLOAD_GRACE.plusMinutes(5);

    /** The most claims one poll declines, so that a pool that keeps refusing cannot hold the poll. */
    static final int MAX_DECLINED_PER_POLL = 32;

    /** How long the poll loop sleeps when there is nothing to claim. */
    static final Duration ROOM_WAIT = Duration.ofSeconds(30);

    /** How often the poll asks the runtime whether queued reviews can still get sandbox room. */
    static final Duration ROOM_CHECK = Duration.ofSeconds(30);

    /** How long the runtime may have no room for a review while no review runs, before queued reviews end. */
    static final Duration STARVATION_BOUND = Duration.ofHours(1);

    /** The longest wait between two refusals of sandbox room. */
    static final Duration MAX_ROOM_WAIT = Duration.ofMinutes(5);

    /** The job's error when the sandbox runtime had no room for it in time. */
    static final String NO_ROOM_IN_TIME = "Hephaestus had no room to run this review in time.";

    /** The job's error when the sandbox quota cannot hold one review sandbox at all. */
    static final String CANNOT_FIT = "The sandbox quota is smaller than one review sandbox.";

    // A failed requeue strands the claim until its lease expires and the run reaper takes it back; this
    // side-effect-free write can be retried.
    private static final RetryPolicy REQUEUE_REJECTED_CLAIM_POLICY = RetryPolicy.builder()
            .includes(Exception.class)
            .maxRetries(2)
            .delay(Duration.ofMillis(200))
            .build();

    private final AgentProperties agentProperties;
    private final ReviewJobClaimService claims;
    private final ReviewRunKind reviewRuns;
    private final AgentRuns runs;
    private final AgentRunNotifications notifications;
    private final ReviewJobRepository jobRepository;
    private final DefaultReviewExecutionHandler handler;
    private final PracticePiAdapter practiceAgent;
    private final AttemptTokenIssuer attemptTokenIssuer;

    private final SandboxManager sandboxManager;
    private final SandboxProperties.DefaultResourceLimits reviewLimits;
    private final AsyncTaskExecutor sandboxExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ReviewAttemptUsageService attemptUsage;
    private final ReviewJobTerminal terminal;
    private final PracticeScriptRunRecorder practiceScriptRuns;

    // RetryTemplate, not @Retryable: both wrap private methods called from inside this class, and a
    // self-invocation never reaches the proxy the annotation needs.
    private final RetryTemplate terminalPersistRetries = retryTemplate(TERMINAL_PERSIST_POLICY);
    private final RetryTemplate requeueRetries = retryTemplate(REQUEUE_REJECTED_CLAIM_POLICY);

    private final Map<RequeueCause, Counter> infraRetryRequeued = new EnumMap<>(RequeueCause.class);
    private final Counter serverErrors;

    /** Reviews that this worker's drain stopped: tried again without spending a retry. */
    private final Counter drainedRequeued;
    /** When the poll next asks the runtime about room for queued reviews ({@link #endReviewsWithoutRoom}). */
    private volatile Instant nextRoomCheck = Instant.EPOCH;

    /** Since when the runtime has had no room for a review while no review ran; empty while it is not starved. */
    private final AtomicReference<@Nullable Instant> starvedSince = new AtomicReference<>();

    /** Review sandboxes that could not start, by reason: {@code sandbox.start.failures{kind=review}}. */
    private final Map<SandboxCapacityException.Reason, Counter> sandboxStartFailures =
            new EnumMap<>(SandboxCapacityException.Reason.class);

    private final ReviewJobTelemetry jobTelemetry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** When an attempt's run stops being renewed is read on this clock; tests move it. */
    Clock clock = Clock.systemUTC();
    /** Released when a review run is queued, so an idle poll claims it at once rather than after its sleep. */
    private final Semaphore wake = new Semaphore(0);

    private final ScheduledExecutorService leaseRenewals = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("agent-lease-renewal").daemon(true).factory());
    private final ExecutorService endedAttemptStopper = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ended-attempt-stopper").daemon(true).factory());
    private volatile @Nullable Thread pollThread;
    private final Phaser inFlight = new Phaser(1); // 1 = the executor itself; deregistered on stop
    // The attempt and lease each locally running job runs, so a later attempt of the same job is never mistaken for it.
    private final Map<UUID, LocalAttempt> localRunningJobs = new ConcurrentHashMap<>();

    /**
     * Claimed jobs that still prepare their sandbox, which the runtime's room does not count yet. A job leaves at its
     * execution fence ({@link #markExecutionStarted}), before its sandbox is launched; for the time of that write and
     * the launch, a poll can read one room too many. That window is accepted: the runtime then refuses the sandbox, and
     * the refusal requeues the job without spending a retry.
     */
    private final Set<UUID> preparingJobs = ConcurrentHashMap.newKeySet();

    private final int sandboxSlots;

    private final Optional<WorkerCapacityState> capacityState;

    @Autowired
    public ReviewJobExecutor(
            AgentProperties agentProperties,
            ReviewJobRepository jobRepository,
            DefaultReviewExecutionHandler handler,
            PracticePiAdapter practiceAgent,
            AttemptTokenIssuer attemptTokenIssuer,
            SandboxManager sandboxManager,
            SandboxProperties sandboxProperties,
            @Qualifier("sandboxExecutor") AsyncTaskExecutor sandboxExecutor,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ReviewJobTelemetry jobTelemetry,
            ReviewAttemptUsageService attemptUsage,
            PracticeScriptRunRecorder practiceScriptRuns,
            Optional<WorkerCapacityState> capacityState,
            ReviewJobClaimService claims,
            ReviewRunKind reviewRuns,
            AgentRuns runs,
            AgentRunNotifications notifications,
            ReviewJobTerminal terminal) {
        this.agentProperties = agentProperties;
        this.terminal = terminal;
        this.claims = claims;
        this.reviewRuns = reviewRuns;
        this.runs = runs;
        this.notifications = notifications;
        this.jobRepository = jobRepository;
        this.handler = handler;
        this.practiceAgent = practiceAgent;
        this.attemptTokenIssuer = attemptTokenIssuer;
        this.sandboxManager = sandboxManager;
        this.reviewLimits = sandboxProperties.defaultResourceLimits();
        this.sandboxSlots = sandboxProperties.maxConcurrentContainers();
        this.sandboxExecutor = sandboxExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.jobTelemetry = jobTelemetry;
        this.attemptUsage = attemptUsage;
        this.practiceScriptRuns = practiceScriptRuns;
        this.capacityState = capacityState;

        this.drainedRequeued = Counter.builder(AgentMetrics.AGENT_JOB_DRAINED)
                .description("Reviews that a worker's drain stopped, by outcome")
                .tag("outcome", "requeued")
                .register(meterRegistry);
        Gauge.builder(AgentMetrics.SANDBOX_REVIEW_STARVED, starvedSince, ReviewJobExecutor::starvedSeconds)
                .description("How long the sandbox runtime has had no room for a review while no review ran")
                .baseUnit("seconds")
                .register(meterRegistry);
        for (SandboxCapacityException.Reason reason : SandboxCapacityException.Reason.values()) {
            sandboxStartFailures.put(
                    reason,
                    Counter.builder(AgentMetrics.SANDBOX_START_FAILURES)
                            .description("Sandboxes that could not start, by kind and reason")
                            .tag("kind", "review")
                            .tag("reason", reason.tag())
                            .register(meterRegistry));
        }
        serverErrors = Counter.builder(AgentMetrics.AGENT_JOB_SERVER_ERROR)
                .description("Attempts that ended because this server answered their results with its own error")
                .register(meterRegistry);
        for (RequeueCause cause : RequeueCause.values()) {
            infraRetryRequeued.put(
                    cause,
                    Counter.builder(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED)
                            .description("Jobs requeued (not failed) after a retryable preparation or infrastructure"
                                    + " failure, by cause")
                            .tag("cause", cause.tag())
                            .register(meterRegistry));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        pollThread = Thread.ofPlatform().name("agent-job-poll").daemon(true).start(this::pollLoop);
        notifications.subscribe(kind -> {
            if (kind == AgentRunKind.REVIEW) wake.release();
        });
        long renewal = runs.capacity().leaseRenewal().toMillis();
        var unused =
                leaseRenewals.scheduleWithFixedDelay(this::renewAndStopEnded, renewal, renewal, TimeUnit.MILLISECONDS);

        log.info(
                "Agent job executor started: pollInterval={}, claimBatchSize={}",
                agentProperties.pollInterval(),
                agentProperties.claimBatchSize());
    }

    @PreDestroy
    void stopRenewals() {
        leaseRenewals.shutdownNow();
        endedAttemptStopper.shutdownNow();
    }

    /** How long stopping waits for the poll thread, so an in-flight claim cannot register after the drain began. */
    public static final Duration POLL_THREAD_JOIN_TIMEOUT = Duration.ofSeconds(10);

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

    /** One cleanup deadline that all containers in a drain share. */
    public static final Duration DRAIN_CLEANUP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Settles this worker's jobs before stopping their containers. Unadmitted attempts may requeue;
     * admitted attempts keep their results and are handed on to compose what is open, without measuring again. The drain
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
        LocalAttempt local = localRunningJobs.get(jobId);
        if (local == null) return false;
        // Not the review's fault: the next attempt spends no retry, and keeps what this one admitted and staged.
        boolean requeued = reviewRuns.requeue(jobId, local.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null);
        if (requeued) {
            drainedRequeued.increment();
            log.info("Drained review attempt queued again: jobId={}, reason={}", jobId, reason);
        }
        return requeued;
    }

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
     * Renews the run of each attempt that runs here: this executor is each one's only lease writer, through
     * preparation, the sandbox, result collection and composition. An attempt past its own time limit is no longer
     * renewed, so its lease expires and the reaper hands it on. A cancel, requeue or reap ends its authority on the run
     * alone; the next renewal finds it ended and stops its sandbox.
     */
    public Map<UUID, LocalAttempt> renewLeases() {
        Map<UUID, LocalAttempt> ended = new HashMap<>();
        Instant now = clock.instant();
        Map.copyOf(localRunningJobs).forEach((jobId, local) -> {
            if (now.isAfter(local.renewUntil())) {
                ended.put(jobId, local);
                return;
            }
            Boolean held = transactionTemplate.execute(
                    status -> runs.renew(local.fence()).isPresent());
            if (!Boolean.TRUE.equals(held)) ended.put(jobId, local);
        });
        return ended;
    }

    private void renewAndStopEnded() {
        Map<UUID, LocalAttempt> ended;
        try {
            ended = renewLeases();
        } catch (RuntimeException e) {
            // A renewal that fails stops nothing: it does not know which leases are gone.
            log.warn("Review lease renewal failed: {}", e.getClass().getSimpleName());
            return;
        }
        if (ended.isEmpty()) return;
        // A stop waits on the sandbox backend, which must not delay the next renewal.
        endedAttemptStopper.execute(() -> stopEndedAttempts(ended));
    }

    /** Stops the sandbox of each attempt that lost its lease and still runs here; see {@link #renewLeases()}. */
    public void stopEndedAttempts(Map<UUID, LocalAttempt> ended) {
        ended.forEach((jobId, local) -> {
            // A later attempt of the job that this worker claimed since the renewal is not the one that ended.
            if (local.equals(localRunningJobs.get(jobId)))
                cancelLocalJob(jobId, "its attempt no longer holds its lease");
        });
    }

    /**
     * The claim of a locally running attempt: every fenced write of the attempt names its run and epoch.
     *
     * @param renewUntil after this, the attempt's run is no longer renewed: its time limit and the upload grace passed
     */
    public record LocalAttempt(AgentRunFence fence, Instant renewUntil) {
        int attempt() {
            return ReviewRunKind.attempt(fence.epoch());
        }
    }

    /**
     * The row of the attempt that runs here, reloaded, while it still carries this attempt's lease and number. A row that
     * a later claim took is never handed on: whatever reads its lease from it would act for that claim.
     *
     * @throws ResultAdmissionService.StaleAttemptException when the attempt no longer holds the row
     */
    private ReviewJob ownRow(UUID jobId) {
        LocalAttempt local = localRunningJobs.get(jobId);
        ReviewJob row = jobRepository.findById(jobId).orElse(null);
        if (local == null
                || row == null
                || row.getAttempt() != local.attempt()
                || !local.fence().runId().equals(row.getRunId()))
            throw new ResultAdmissionService.StaleAttemptException();
        return row;
    }

    /** Records an attempt that this worker claimed, so that each of its writes is fenced on its own claim. */
    void track(ReviewJobClaimService.ClaimResult claim) {
        localRunningJobs.put(claim.job().getId(), new LocalAttempt(claim.fence(), renewUntil(clock.instant(), claim)));
    }

    /** The attempt's time limit from {@code anchor}, plus the grace in which its results still upload. */
    private static Instant renewUntil(Instant anchor, ReviewJobClaimService.ClaimResult claim) {
        return anchor.plusSeconds(claim.snapshot().timeoutSeconds()).plus(ATTEMPT_GRACE);
    }

    /**
     * The fence of the attempt numbered {@code attempt} that runs here, for each of its writes. It names the attempt
     * that the writing thread runs, never the newest claim of the job: a thread of an attempt that this worker lost
     * and claimed again is refused by the run.
     */
    private AgentRunFence ownFence(UUID jobId, int attempt) {
        AgentRunFence tracked = Objects.requireNonNull(localRunningJobs.get(jobId), "The attempt does not run here")
                .fence();
        return new AgentRunFence(tracked.runId(), tracked.workspaceId(), ReviewRunKind.epoch(attempt));
    }

    /** The run of the attempt that runs here, which each of its fenced writes names with its attempt. */
    private UUID ownRun(UUID jobId) {
        return Objects.requireNonNull(localRunningJobs.get(jobId), "The attempt does not run here")
                .fence()
                .runId();
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

    private void pollLoop() {
        while (running.get()) {
            try {
                endReviewsWithoutRoom(Instant.now());
                int capacity = computeCapacity();
                if (capacity <= 0) {
                    sleepPollInterval();
                    continue;
                }

                if (!pollEndpoints(capacity)) sleepPollInterval();
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

    /**
     * Claims runs until this worker's capacity is full or nothing is offered. A claim that cannot start now rolls back,
     * and this poll skips its run.
     */
    private boolean pollEndpoints(int capacity) {
        int remaining = capacity;
        Set<UUID> declined = new HashSet<>();
        boolean anyStarted = false;
        while (remaining > 0 && running.get() && declined.size() < MAX_DECLINED_PER_POLL) {
            var attempt = tryClaim(declined);
            // A claim that failed waits for the next poll rather than retrying at once.
            if (attempt == null) break;
            StartOutcome outcome = processClaim(attempt, declined);
            if (outcome == StartOutcome.NOTHING) break;
            if (outcome == StartOutcome.POOL_REJECTED) return false;
            if (outcome == StartOutcome.STARTED) {
                anyStarted = true;
                remaining--;
            }
        }
        return anyStarted;
    }

    // The sandbox slots bound the jobs that run here: each one measures in a sandbox, or hands its review on at once.
    int computeCapacity() {
        if (!sandboxManager.ready()) {
            return 0;
        }
        long free = sandboxSlots - (long) localRunningJobs.size();
        if (free <= 0) {
            return 0;
        }
        return (int) Math.min(free, agentProperties.claimBatchSize());
    }

    // Jitter prevents replicas from polling together and amplifying claim contention. A queued run wakes it early.
    private void sleepPollInterval() {
        try {
            double jitterMultiplier = 0.9 + (ThreadLocalRandom.current().nextDouble() * 0.2);
            long millis = Math.round(agentProperties.pollInterval().toMillis() * jitterMultiplier);
            if (wake.tryAcquire(millis, TimeUnit.MILLISECONDS)) wake.drainPermits();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    enum StartOutcome {
        NOTHING,
        SKIPPED,
        STARTED,
        POOL_REJECTED
    }

    StartOutcome processNext() {
        return processClaim(tryClaim(new HashSet<>()), new HashSet<>());
    }

    private ReviewJobClaimService.@Nullable ClaimAttempt tryClaim(Set<UUID> declined) {
        try {
            return claims.claimNext(
                    declined,
                    () -> sandboxManager.roomFor(reviewLimits.forAttempt(ResourceLimits.MAX_RUNTIME))
                            > preparingJobs.size());
        } catch (CannotAcquireLockException e) {
            log.debug("Lock timeout during claim, will retry on next poll");
            return null;
        } catch (Exception e) {
            log.warn("Claim failed, will retry on next poll: {}", e.getClass().getName());
            return null;
        }
    }

    private StartOutcome processClaim(ReviewJobClaimService.@Nullable ClaimAttempt attempt, Set<UUID> declined) {
        return switch (attempt) {
            case null -> StartOutcome.SKIPPED;
            case ReviewJobClaimService.ClaimOutcome outcome
            when outcome == ReviewJobClaimService.ClaimOutcome.NOTHING -> StartOutcome.NOTHING;
            case ReviewJobClaimService.PoolWait waiting -> {
                declined.add(waiting.runId());
                yield StartOutcome.SKIPPED;
            }
            case ReviewJobClaimService.Declined skipped -> {
                declined.add(skipped.runId());
                yield StartOutcome.SKIPPED;
            }
            case ReviewJobClaimService.TerminalClaim terminal -> {
                jobTelemetry.terminal(terminal.job(), terminal.status(), ReviewJobTelemetry.age(terminal.job()));
                yield StartOutcome.SKIPPED;
            }
            case ReviewJobClaimService.ClaimResult claim -> started(claim);
            case ReviewJobClaimService.ClaimOutcome other -> StartOutcome.SKIPPED;
        };
    }

    private StartOutcome started(ReviewJobClaimService.ClaimResult claim) {
        UUID jobId = claim.job().getId();
        // A local attempt of this job lost its run, and its sandbox may still stop: one job never has two sandboxes
        // on one worker.
        if (localRunningJobs.containsKey(jobId)) cancelLocalJob(jobId, "the job was claimed again");
        // Counted after the claim commits, never inside its transaction: a failed commit rolls the row
        // back to QUEUED and no execution then runs to release the count. Since the count is the hard
        // claim bound, one leaked entry would retire a slot for this worker's lifetime.
        track(claim);
        preparingJobs.add(jobId);
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
            requeueRejectedClaim(jobId, claim.fence());
            return StartOutcome.POOL_REJECTED;
        }
    }

    // Pool rejection must not consume an execution attempt.
    // If requeue fails, the run's lease expires and the reaper hands the attempt on; no sandbox ran.
    private void requeueRejectedClaim(UUID jobId, AgentRunFence fence) {
        try {
            requeueRetries.execute(named("Requeue of rejected claim " + jobId, () -> {
                transactionTemplate.executeWithoutResult(
                        status -> reviewRuns.requeue(jobId, fence, AgentRuns.Charge.FREE, Duration.ZERO, null));
                return null;
            }));
        } catch (RetryException e) {
            log.error(
                    "Failed to requeue rejected claim {}; its run's lease expires and the reaper hands it on",
                    jobId,
                    e.getLastException());
        } finally {
            releaseCapacity();
            localRunningJobs.remove(jobId);
            preparingJobs.remove(jobId);
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
        var executionSpan = jobTelemetry.startExecution(job, claim.fence());
        var executionScope = jobTelemetry.executionScope(executionSpan);
        MDC.put(StructuredLogKeys.TRACE_ID, executionSpan.context().traceId());
        MDC.put(StructuredLogKeys.SPAN_ID, executionSpan.context().spanId());
        MDC.put(
                StructuredLogKeys.TRACE_FLAGS,
                Boolean.TRUE.equals(executionSpan.context().sampled()) ? "01" : "00");
        MDC.put(MDC_JOB_ID, jobId.toString());
        MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(job.getWorkspaceId()));
        MDC.put(MDC_JOB_TYPE, claim.itemKind());
        MDC.put(StructuredLogKeys.RUN_ID, claim.fence().runId().toString());
        MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(claim.fence().epoch()));
        MDC.put(StructuredLogKeys.SUBJECT_KEY, ReviewRunKind.subject(job.getReviewId()));
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

            // A review whose staged practices this attempt seals measures nothing again, and an admitted seal hands the
            // review on to the run that composes it, in the review's sandbox. A claim never starts an admitted review:
            // it hands it on. The row must still carry this attempt's lease: a seal or interruption acts for whoever
            // holds it.
            ReviewJob current;
            try {
                current = ownRow(jobId);
            } catch (ResultAdmissionService.StaleAttemptException lost) {
                metricOutcome = "OWNERSHIP_LOST";
                return;
            }
            boolean sealOnly = !ResultAdmissionService.isAdmitted(current)
                    && (current.spentAttempts() > runs.capacity().reviewRetries() || claim.withholdOnly() != null)
                    && handler.hasStaged(current);
            if (sealOnly)
                handler.finishInterrupted(
                        current,
                        claim.withholdOnly() == CompositionStop.BUDGET
                                ? ReviewStopReason.BUDGET
                                : claim.withholdOnly() == CompositionStop.MODEL_UNAVAILABLE
                                        ? ReviewStopReason.MODEL_UNAVAILABLE
                                        : ReviewStopReason.EXECUTION_INCOMPLETE);
            if (sealOnly || handler.completeStaged(current)) {
                boolean admitted;
                try {
                    admitted = ResultAdmissionService.isAdmitted(ownRow(jobId));
                } catch (ResultAdmissionService.StaleAttemptException lost) {
                    metricOutcome = "OWNERSHIP_LOST";
                    return;
                }
                if (admitted) {
                    metricOutcome = handOnToCompose(jobId, current.getAttempt(), null) ? "HANDED_ON" : "OWNERSHIP_LOST";
                    return;
                }
                // A seal that admitted nothing leaves no feedback to compose: the attempt ends here.
                preparationStage.set(PreparationFailure.Stage.EXECUTION_FENCE);
                if (!markExecutionStarted(jobId, current.getAttempt(), claim)) {
                    metricOutcome = "OWNERSHIP_LOST";
                    return;
                }
                sandboxExecutionStarted = true;
                recordedTerminal = persistComposedTerminal(jobId, current.getAttempt());
                metricOutcome = recordedTerminal == null ? "OWNERSHIP_LOST" : recordedTerminal.name();
                return;
            }
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
            // for the run reaper on another process. A lost fence means the job was cancelled or requeued
            // while preparation ran, so its sandbox must not start.
            preparationStage.set(PreparationFailure.Stage.EXECUTION_FENCE);
            if (!markExecutionStarted(jobId, job.getAttempt(), claim)) {
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
            Integer updated = transactionTemplate.execute(status -> refuse(jobId, job.getAttempt(), output));
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
            // Provider work already completed, and the row could not record it. The run is no longer renewed, so its
            // lease expires and the reaper hands the attempt on with its measured spend.
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
                localRunningJobs.computeIfPresent(
                        jobId, (id, local) -> local.fence().equals(claim.fence()) ? null : local);
                preparingJobs.remove(jobId);
                MDC.remove(MDC_JOB_ID);
                MDC.remove(StructuredLogKeys.WORKSPACE_ID);
                MDC.remove(MDC_JOB_TYPE);
                MDC.remove(StructuredLogKeys.RUN_ID);
                MDC.remove(StructuredLogKeys.RUN_EPOCH);
                MDC.remove(StructuredLogKeys.SUBJECT_KEY);
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
            Integer updated = transactionTemplate.execute(status -> refuse(jobId, job.getAttempt(), output));
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
        int endedBefore = handler.endedPractices(job);
        SandboxResult result;
        var sandbox = jobTelemetry.stage(ReviewStageTelemetry.Stage.SANDBOX);
        try {
            result = sandboxManager.execute(sandboxSpec);
        } catch (SandboxCapacityException e) {
            sandbox.discard();
            return withoutRoom(jobId, job, e);
        } catch (RuntimeException failed) {
            sandbox.end(ReviewStageTelemetry.Outcome.FAILED);
            throw failed;
        }
        sandbox.end(sandboxOutcome(result));
        AgentResult agentResult = practiceAgent.parseResult(result);

        ReviewJob retained;
        try {
            retained = ownRow(jobId);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return null;
        }
        // A server error is a defect here, not an outage, and another attempt would meet it again. The attempt keeps
        // what it staged and ends the rest, with no retry.
        boolean serverError = result.exitCode() == SandboxLayout.EXIT_SERVER_ERROR;
        if (serverError) {
            serverErrors.increment();
            log.error("The server refused a review's results with its own error, so the attempt ends: jobId={}", jobId);
        }
        if (!ResultAdmissionService.isAdmitted(retained)
                && handler.hasStaged(retained)
                && !handler.completeStaged(retained)) {
            if (!serverError) {
                // A slow provider can take many attempts. Each one that moved the review on keeps the retries for
                // the attempts that move nothing.
                if (handler.endedPractices(retained) > endedBefore && continueProgressed(retained)) return null;
                if (requeueForAnotherAttempt(retained, RequeueCause.SANDBOX)) return null;
            }
            handler.finishInterrupted(
                    retained,
                    serverError
                            ? ReviewStopReason.EXECUTION_FAILED
                            : result.timedOut() ? ReviewStopReason.DEADLINE : ReviewStopReason.EXECUTION_INCOMPLETE);
        }

        // An infrastructure or provider failure can leave the run without deliverable work. A bounded retry keeps
        // staged practices and makes no claim about the reviewed work. Past the cap, the attempt ends here.
        RequeueCause unreachable = unreachableReason(result.exitCode());
        if (unreachable != null && requeueForAnotherAttempt(job, unreachable)) {
            log.warn(
                    "Requeuing job {} ({}): nothing it measured can be delivered from here (attempt {})",
                    jobId,
                    unreachable.tag(),
                    job.getAttempt() + 1);
            return null;
        }
        // The sandbox has ended. A review that admitted results keeps what this attempt measured and is handed on to
        // the run that composes its feedback in the review's sandbox.
        ReviewJob measured;
        try {
            measured = ownRow(jobId);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return null;
        }
        if (ResultAdmissionService.isAdmitted(measured)) {
            handOnToCompose(jobId, job.getAttempt(), new Measured(agentResult, result));
            return null;
        }

        ReviewJobStatus terminalStatus = persistTerminalState(jobId, job.getAttempt(), agentResult, result);
        if (terminalStatus != null) forgetCompositionInputs(job);
        return terminalStatus;
    }

    /** Its feedback is decided: no attempt reads the retained inputs again. The retention sweep removes what this leaves. */
    private void forgetCompositionInputs(ReviewJob job) {
        terminal.forgetCompositionInputs(job);
    }

    /** What a measuring attempt's sandbox reported, which the attempt keeps when it hands its review on. */
    private record Measured(AgentResult agentResult, SandboxResult sandboxResult) {}

    /**
     * Hands the admitted review of the attempt that runs here on to the run that composes its feedback in the review's
     * sandbox ({@link ReviewRunKind#continueToCompose}). An attempt that measured keeps its output, exit code, usage
     * and practice-script runs first, in the same transaction, because no later attempt reports them again.
     *
     * @return false when the attempt no longer holds its job
     */
    private boolean handOnToCompose(UUID jobId, int attempt, @Nullable Measured measured) {
        try {
            return Boolean.TRUE.equals(terminalPersistRetries.execute(named(
                    "Hand-on to composition for jobId=" + jobId,
                    () -> transactionTemplate.execute(status -> {
                        AgentRunFence fence = ownFence(jobId, attempt);
                        reviewRuns.lock(fence.run());
                        if (measured != null && !keepMeasurement(jobId, attempt, measured)) return false;
                        return reviewRuns.continueToCompose(jobId, fence).isPresent();
                    }))));
        } catch (RetryException e) {
            throw new TerminalPersistenceException(e);
        }
    }

    /** Keeps a measuring attempt's report on its job, settles the attempt with it, and records its script runs. */
    private boolean keepMeasurement(UUID jobId, int attempt, Measured measured) {
        ReviewJob locked = jobRepository.lockById(jobId).orElse(null);
        if (locked == null || locked.getStatus() != ReviewJobStatus.RUNNING || locked.getAttempt() != attempt)
            return false;
        JsonNode output = objectMapper.valueToTree(measured.agentResult().output());
        boolean storable = textRepresentableInJsonb(output);
        ReviewJobLlmUsage proxyCounts = jobRepository.findLlmUsageById(jobId).orElse(null);
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(locked.getConfigSnapshot(), objectMapper);
        ObjectNode kept = storable && output instanceof ObjectNode report
                ? report
                : objectMapper.createObjectNode().put("resultNotStored", UNSTORABLE_TEXT);
        ReviewJobStatus determined = determineTerminalStatus(measured.sandboxResult(), measured.agentResult(), locked);
        ReviewJobStatus status =
                !storable && determined == ReviewJobStatus.COMPLETED ? ReviewJobStatus.FAILED : determined;
        // The composition run ends the job with what this attempt measured, so a timed-out measurement is not complete.
        if (status != ReviewJobStatus.COMPLETED)
            kept.set(
                    ReviewJobTerminal.MEASURED,
                    objectMapper
                            .createObjectNode()
                            .put("status", status.name())
                            .put("error", terminalError(status, measured.sandboxResult(), storable)));
        locked.setOutput(kept);
        locked.setExitCode(measured.sandboxResult().exitCode());
        // A later settlement of the same attempt, as the hand-on's own, keeps this report.
        attemptUsage.settleAttempt(
                locked,
                FrozenCharge.admitted(snapshot, admittedPrice(snapshot)),
                measured.agentResult().usage(),
                proxyCounts);
        jobRepository.saveAndFlush(locked);
        practiceScriptRuns.record(
                locked,
                measured.sandboxResult().outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE),
                snapshot.practiceScriptModels(),
                Instant.now());
        return true;
    }

    /**
     * Ends an attempt that measured nothing and whose seal admitted nothing: it completes, and bills the model calls it
     * made, which the proxy counted on the attempt.
     */
    private @Nullable ReviewJobStatus persistComposedTerminal(UUID jobId, int attempt) {
        ReviewJobStatus status;
        try {
            status = terminalPersistRetries.execute(named(
                    "Terminal persistence for jobId=" + jobId,
                    () -> transactionTemplate.execute(
                            transaction -> terminal.completeComposed(jobId, ownFence(jobId, attempt))
                                    ? ReviewJobStatus.COMPLETED
                                    : null)));
        } catch (RetryException e) {
            throw new TerminalPersistenceException(e);
        }
        return status;
    }

    static String randomSpanId() {
        long value;
        do {
            value = ThreadLocalRandom.current().nextLong();
        } while (value == 0);
        return "%016x".formatted(value);
    }

    private boolean markExecutionStarted(UUID jobId, int attempt, ReviewJobClaimService.ClaimResult claim) {
        preparingJobs.remove(jobId);
        Instant now = clock.instant();
        Integer updated = transactionTemplate.execute(
                status -> jobRepository.markExecutionStarted(jobId, ownRun(jobId), attempt, now));
        if (updated == null || updated != 1) return false;
        // The time limit counts from the sandbox's start, as the composer's does.
        localRunningJobs.computeIfPresent(
                jobId,
                (id, local) -> local.fence().equals(claim.fence())
                        ? new LocalAttempt(local.fence(), renewUntil(now, claim))
                        : local);
        return true;
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

        // Keeping a result commits at capture. If that attempt then failed, this one has nothing left to capture.
        if (handler.keptEveryResult(ownRow(jobId))) {
            handler.completeWithoutExecution(ownRow(jobId));
            return new PreparedSandbox(null, new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        }
        var capture = jobTelemetry.stage(ReviewStageTelemetry.Stage.CAPTURE);
        PreparedJobInputs preparedInputs;
        try {
            preparedInputs = handler.prepareInputs(ownRow(jobId));
        } catch (InsufficientEvidenceException refused) {
            // The capture ran and found too little evidence; the attempt ends with that, not with a fault.
            capture.end(ReviewStageTelemetry.Outcome.OK);
            throw refused;
        } catch (RuntimeException failed) {
            capture.end(ReviewStageTelemetry.Outcome.FAILED);
            throw failed;
        }
        capture.end(ReviewStageTelemetry.Outcome.OK);

        try {
            var report = preparedInputs.automatedReviewReadinessReport();
            if (report != null && report.decisions().stream().noneMatch(report::runs)) {
                stage.set(PreparationFailure.Stage.PROVENANCE);
                persistPreparedEvidence(jobId, job.getWorkspaceId(), job.getAttempt(), preparedInputs);
                handler.completeWithoutExecution(ownRow(jobId));
                return new PreparedSandbox(null, preparedInputs);
            }
            stage.set(PreparationFailure.Stage.CREDENTIAL);
            // Sandboxes access providers through the LLM proxy with an attempt-scoped credential.
            Instant workDeadline =
                    Instant.now().plusSeconds(snapshot.timeoutSeconds()).truncatedTo(ChronoUnit.SECONDS);
            Instant tokenExpiry = workDeadline.plus(SandboxLayout.RESULT_UPLOAD_GRACE);
            String jobToken = attemptTokenIssuer.issueForReview(
                    jobId, job.getWorkspaceId(), job.getAttempt(), ownRun(jobId), workDeadline, tokenExpiry);
            stage.set(PreparationFailure.Stage.SPEC);
            PracticeAgentRequest adapterRequest = new PracticeAgentRequest(
                    snapshot.model().apiProtocol().wire(),
                    snapshot.model().upstreamModelId(),
                    snapshot.contextWindow(),
                    snapshot.maxOutputTokens(),
                    snapshot.model().reasoningEffort(),
                    jobToken,
                    snapshot.timeoutSeconds(),
                    snapshot.practiceScriptModels(),
                    snapshot.model().forcedToolChoice());

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
        // Admission compares each search of the sandbox's checkout with these, read from this copy before it starts.
        stamp.set(
                JobEvidenceFiles.CHECKOUTS,
                objectMapper.valueToTree(JobEvidenceFiles.checkoutDigests(inputDirectories)));
        Integer updated = transactionTemplate.execute(
                status -> jobRepository.updateCapture(jobId, workspaceId, ownRun(jobId), attempt, stamp));
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
                : e instanceof SandboxInitializationException
                        ? "The sandbox could not prepare its workspace."
                        : e instanceof SandboxInfrastructureException
                                ? "The sandbox infrastructure failed during execution."
                                : e instanceof IOException
                                        ? "A file or network operation failed during execution."
                                        : e instanceof InterruptedException
                                                ? "The worker interrupted execution."
                                                : "The job failed during execution.";
        if (preparation == null) {
            log.error(
                    "Agent job failed: jobId={}, error={}, errorType={}",
                    jobId,
                    errorMessage,
                    e.getClass().getName(),
                    e);
        } else {
            // Bounded facts only, as on the job row: an exception message can name a private path or repository. The
            // exception types and the throwing location show an unclassified cause.
            log.error(
                    "Review preparation failed: jobId={}, stage={}, kind={}, reason={}, captureCode={}, capturedAt={},"
                            + " exceptionTypes={}",
                    jobId,
                    preparation.stage().name(),
                    preparation.kind().name(),
                    preparation.reason().name(),
                    preparation.captureCode(),
                    preparation.capturedAt(),
                    preparation.exceptionTypes());
        }

        if (isRetryableExecutionFailure(e)) {
            int currentRetryCount = job.getAttempt();
            RequeueCause cause = preparation != null ? RequeueCause.PREPARATION : RequeueCause.SANDBOX;
            if (requeueForAnotherAttempt(job, cause)) {
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
                        ownRun(jobId),
                        objectMapper.writeValueAsString(diagnosis));
            }
            if (updated > 0 && e instanceof SandboxInitializationException initialization) {
                jobRepository.recordInitializationFailure(
                        jobId,
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        ownRun(jobId),
                        initializationDiagnosis(objectMapper, job.getAttempt(), initialization));
            }
            if (updated > 0 && sandboxExecutionStarted) billTerminatedJob(job, "execution failure");
            return updated;
        });
        String outcome = failed != null && failed > 0 ? ReviewJobStatus.FAILED.name() : "OWNERSHIP_LOST";
        if (preparation != null) jobTelemetry.preparationFailed(job, preparation, outcome);
        return failed != null && failed > 0 ? FailureOutcome.FAILED : FailureOutcome.OWNERSHIP_LOST;
    }

    /**
     * What the operator needs to see why the initializer stopped: its exit, whether the deadline ended it, and the
     * bounded, credential-masked tail of its transcript. An infrastructure detail, never feedback about the work.
     */
    static String initializationDiagnosis(
            ObjectMapper objectMapper, int attempt, SandboxInitializationException initialization) {
        var diagnosis = objectMapper
                .createObjectNode()
                .put("attempt", attempt)
                .put("timedOut", initialization.timedOut())
                .put("transcriptTail", initialization.transcriptTail());
        Integer exitCode = initialization.exitCode();
        if (exitCode == null) {
            diagnosis.putNull("exitCode");
        } else {
            diagnosis.put("exitCode", exitCode);
        }
        return objectMapper.writeValueAsString(diagnosis);
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
                || e instanceof IOException
                || transientCapture(PreparationFailure.captureOf(e));
    }

    /**
     * A capture that failed, or whose source was not ready, can succeed on another attempt; the same item captured on
     * earlier attempts. Removed or hidden work, withheld consent, a lost version and an occasion that kept no record do
     * not come back.
     */
    private static boolean transientCapture(@Nullable ItemCaptureException capture) {
        return capture != null
                && switch (capture.code()) {
                    case CAPTURE_FAILED, SOURCE_NOT_READY -> true;
                    case ITEM_REMOVED,
                            ITEM_NOT_VISIBLE,
                            CONSENT_WITHHELD,
                            VERSION_UNAVAILABLE,
                            OCCASION_EVIDENCE_UNAVAILABLE -> false;
                };
    }

    private static ReviewStageTelemetry.Outcome sandboxOutcome(SandboxResult result) {
        if (result.timedOut()) return ReviewStageTelemetry.Outcome.FAILED;
        if (result.exitCode() == 0) return ReviewStageTelemetry.Outcome.OK;
        return result.exitCode() == SandboxLayout.EXIT_PARTIAL_REVIEW
                ? ReviewStageTelemetry.Outcome.PARTIAL
                : ReviewStageTelemetry.Outcome.FAILED;
    }

    private static @Nullable RequeueCause unreachableReason(int exitCode) {
        if (exitCode == SandboxLayout.EXIT_SERVER_UNREACHABLE) {
            return RequeueCause.SERVER_UNREACHABLE;
        }
        if (exitCode == SandboxLayout.EXIT_PROVIDER_UNREACHABLE) {
            return RequeueCause.PROVIDER_UNREACHABLE;
        }
        if (exitCode == SandboxLayout.EXIT_SIDE_LOST) {
            return RequeueCause.SANDBOX;
        }
        return null;
    }

    /** Why an attempt runs again: each cause asks the operator to look somewhere else. */
    enum RequeueCause {
        /** Before the sandbox started: the reviewed source, its inputs or the database. */
        PREPARATION,
        /** The sandbox could not start, initialize or finish, or a file or network operation failed in it. */
        SANDBOX,
        /** The sandbox could not reach the server's gateway. */
        SERVER_UNREACHABLE,
        /** No model call of the run was answered. */
        PROVIDER_UNREACHABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Hands an unfinished attempt that moved the review on to the next attempt, without spending a retry. */
    private boolean continueProgressed(ReviewJob job) {
        if (ResultAdmissionService.isAdmitted(job)) return false;
        Boolean updated = transactionTemplate.execute(status -> reviewRuns.requeue(
                job.getId(), ownFence(job.getId(), job.getAttempt()), AgentRuns.Charge.FREE, Duration.ZERO, null));
        return Boolean.TRUE.equals(updated);
    }

    // Bills within the requeue transaction before it clears usage counters; an attempt that started no sandbox has
    // none.
    private boolean requeueForAnotherAttempt(ReviewJob job, RequeueCause cause) {
        if (!reviewRuns.mayRetry(job)) return false;
        Boolean updated = transactionTemplate.execute(status -> reviewRuns.requeue(
                job.getId(), ownFence(job.getId(), job.getAttempt()), AgentRuns.Charge.SPEND, Duration.ZERO, null));
        if (!Boolean.TRUE.equals(updated)) return false;
        Objects.requireNonNull(infraRetryRequeued.get(cause)).increment();
        return true;
    }

    private void recordPracticeReviewRefusal(String reason) {
        ReviewRefusalRecorder.record(meterRegistry, "execution", reason);
    }

    private void releaseCapacity() {
        capacityState.ifPresent(WorkerCapacityState::releaseReview);
    }

    /**
     * Nothing of the sandbox ran, so the attempt is not spent. A sandbox that has no room now waits in the queue for
     * {@link #ROOM_WAIT} and longer; this hold covers what an admission before the claim cannot see. Only a sandbox
     * that no room can ever hold cancels it here; {@link #endReviewsWithoutRoom} ends a starved one.
     *
     * @return CANCELLED, or null when the job waits or this worker lost it
     */
    private @Nullable ReviewJobStatus withoutRoom(UUID jobId, ReviewJob job, SandboxCapacityException e) {
        Instant now = Instant.now();
        boolean neverFits = e.reason() == SandboxCapacityException.Reason.QUOTA_CANNOT_FIT
                || sandboxManager.neverFits(reviewLimits.forAttempt(ResourceLimits.MAX_RUNTIME));
        SandboxCapacityException.Reason reason =
                neverFits ? SandboxCapacityException.Reason.QUOTA_CANNOT_FIT : e.reason();
        Objects.requireNonNull(sandboxStartFailures.get(reason)).increment();
        if (neverFits) {
            log.error("No sandbox room can hold the review, so it is cancelled: reason={}", reason.tag());
            Boolean cancelled = transactionTemplate.execute(status -> {
                if (jobRepository.cancelForRoomOwnedBy(jobId, now, CANNOT_FIT, ownRun(jobId), job.getAttempt()) == 0)
                    return false;
                runs.end(ownFence(jobId, job.getAttempt()));
                return true;
            });
            return Boolean.TRUE.equals(cancelled) ? ReviewJobStatus.CANCELLED : null;
        }
        // The earlier attempts that spent no retry back the wait off. Most are refusals of room; a drain or a handed-on
        // attempt among them only lengthens a wait that MAX_ROOM_WAIT bounds.
        Duration wait = roomWait(Math.max(0, job.getAttempt() - job.spentAttempts()));
        transactionTemplate.executeWithoutResult(status -> reviewRuns.requeue(
                jobId, ownFence(jobId, job.getAttempt()), AgentRuns.Charge.FREE, wait, AgentRunHold.SANDBOX_ROOM));
        log.warn(
                "No room for the review's sandbox, so the job waits in the queue: reason={}, retryIn={}",
                reason.tag(),
                wait);
        return null;
    }

    /**
     * Ends the queued reviews that the sandbox runtime cannot hold, at most every {@link #ROOM_CHECK}: at once when no
     * room can ever hold one, and after {@link #STARVATION_BOUND} of starvation, when reviews waited and the runtime had
     * no room for one while no review ran anywhere, so nothing would free room. Waiting behind running reviews is
     * backlog, never starvation, and a full runtime with an empty queue starves nobody. Admission before the claim
     * keeps such reviews queued, so this check, not a refusal, ends them.
     */
    void endReviewsWithoutRoom(Instant now) {
        if (now.isBefore(nextRoomCheck)) {
            return;
        }
        nextRoomCheck = now.plus(ROOM_CHECK);
        ResourceLimits limits = reviewLimits.forAttempt(ResourceLimits.MAX_RUNTIME);
        if (sandboxManager.neverFits(limits)) {
            starvedSince.set(null);
            cancelQueuedForRoom(now, CANNOT_FIT, SandboxCapacityException.Reason.QUOTA_CANNOT_FIT);
            return;
        }
        if (sandboxManager.roomFor(limits) != 0
                || sandboxManager.reviewsRunning()
                || !jobRepository.existsQueuedForRoom()) {
            starvedSince.set(null);
            return;
        }
        Instant since = starvedSince.get();
        if (since == null) {
            starvedSince.set(now);
            log.warn(
                    "The sandbox quota has no room for a review while no review runs: other sandboxes hold it. Check a"
                            + " sandbox quota of your own (docs/admin/kubernetes.mdx); queued reviews end"
                            + " after {} without room",
                    STARVATION_BOUND);
            return;
        }
        if (Duration.between(since, now).compareTo(STARVATION_BOUND) >= 0) {
            cancelQueuedForRoom(now, NO_ROOM_IN_TIME, SandboxCapacityException.Reason.QUOTA_EXCEEDED);
        }
    }

    private void cancelQueuedForRoom(Instant now, String error, SandboxCapacityException.Reason reason) {
        Integer cancelled =
                transactionTemplate.execute(status -> runs.cancelAll(jobRepository.cancelQueuedForRoom(now, error)));
        if (cancelled != null && cancelled > 0) {
            Objects.requireNonNull(sandboxStartFailures.get(reason)).increment(cancelled);
            log.error("Cancelled {} queued reviews without sandbox room: reason={}", cancelled, reason.tag());
        }
    }

    /** How long the runtime has had no room for a review while no review ran; zero when it is not starved. */
    private static double starvedSeconds(AtomicReference<@Nullable Instant> starvedSince) {
        Instant since = starvedSince.get();
        return since == null ? 0 : Duration.between(since, Instant.now()).toSeconds();
    }

    /** The wait after the given number of earlier refusals: 30 seconds, doubling, at most 5 minutes. */
    static Duration roomWait(int refusals) {
        Duration wait = ROOM_WAIT.multipliedBy(1L << Math.min(refusals, 4));
        return wait.compareTo(MAX_ROOM_WAIT) > 0 ? MAX_ROOM_WAIT : wait;
    }

    // Fence late terminal writes on the run and attempt so they cannot end an attempt that was requeued since. The run
    // ends with its job, in the caller's transaction.
    private int transitionTerminal(
            UUID jobId, int attempt, ReviewJobStatus status, Instant now, @Nullable String error) {
        return terminal.transition(jobId, ownFence(jobId, attempt), status, now, error);
    }

    /** Ends the attempt as refused, with its run, in the caller's transaction. */
    private int refuse(UUID jobId, int attempt, JsonNode output) {
        int updated = jobRepository.transitionToEvidenceRefused(jobId, ownRun(jobId), attempt, Instant.now(), output);
        if (updated > 0) runs.end(ownFence(jobId, attempt));
        return updated;
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
    // Its lease expires, and the run reaper ends the attempt and records its proxy usage.
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
            String errorMessage = terminalError(terminalStatus, sandboxResult, storable);
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

    /** What a measuring attempt that ended with {@code status} reports as its error; null for a completed one. */
    private static @Nullable String terminalError(
            ReviewJobStatus status, SandboxResult sandboxResult, boolean storable) {
        return switch (status) {
            case TIMED_OUT -> "The container timed out.";
            case FAILED ->
                !storable
                        ? UNSTORABLE_RESULT_MESSAGE
                        : sandboxResult.exitCode() == 0
                                ? "The runner did not return a valid result."
                                : sandboxResult.exitCode() == SandboxLayout.EXIT_SERVER_ERROR
                                        ? "The server answered the review's results with its own error."
                                        : "The container exited with code " + sandboxResult.exitCode() + ".";
            default -> null;
        };
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
        return terminal.admittedPrice(snapshot);
    }

    // Price lookup must not throw and roll back a completed attempt; unavailable rates stay UNPRICED.
    private LlmPriceSnapshot terminalPriceOrUnpriced(ConfigSnapshot snapshot) {
        LlmPriceSnapshot price = snapshot.model().priceSnapshot();
        return price != null ? price : LlmPriceSnapshot.unpricedInstance();
    }
}
