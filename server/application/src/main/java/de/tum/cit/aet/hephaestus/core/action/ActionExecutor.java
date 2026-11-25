package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.EvaluationObservations;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.MaintenanceInProgressException;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Carries out the outbox on every server replica, without a leader: replicas share the rows through
 * {@code SKIP LOCKED} claims and leases on the database clock. A crashed replica's lease expires, and another replica
 * takes the action again, or ends it {@link ActionState#UNCERTAIN} when its kind cannot read back.
 *
 * <p>Each replica runs up to {@link #SLOTS} attempts at once, and each run claims only into free slots, so one slow
 * attempt never holds back the others. Spring's graceful shutdown waits for the attempts in flight
 * ({@link SmartLifecycle}).
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The executor runs every workspace's actions; each adapter scopes its own provider call")
public class ActionExecutor implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    /** Longer than any provider call an adapter makes, with {@link #WRITE_MARGIN} to spare. */
    public static final Duration LEASE = Duration.ofMinutes(2);

    /**
     * A write starts only while this much of the lease is left. An adapter's provider call must end within it, or a
     * reclaim may overlap the call; an at-most-once kind is never reclaimed after its write started.
     */
    static final Duration WRITE_MARGIN = Duration.ofSeconds(60);

    /**
     * How long an ended action stays, except Slack outcomes kept with their conversation. Longer than any notice's
     * own expiry, so a late redelivery still finds its idempotency key.
     */
    static final Duration RETENTION = Duration.ofDays(30);

    /** Actions one replica claims and runs at the same time. */
    static final int SLOTS = 16;

    private @Nullable EvaluationObservations evaluation;

    @Autowired(required = false)
    void observeEvaluation(EvaluationObservations observer) {
        evaluation = observer;
    }

    private final ActionStore store;
    private final ObjectMapper mapper;
    private final Map<ActionKind, ActionAdapter> adapters = new EnumMap<>(ActionKind.class);
    private final MeterRegistry meters;
    private final ActionAdmission admission;
    private final Duration lease;

    /** One permit per attempt in flight; an attempt returns it when it ends or its lease runs out. */
    private final Semaphore slots = new Semaphore(SLOTS);

    private final ExecutorService attempts = Executors.newVirtualThreadPerTaskExecutor();
    /** The attempts in flight, each under its own key: a lost lease can run the same action twice at once. */
    private final Map<UUID, CompletableFuture<ActionResult>> inFlight = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    @Autowired
    public ActionExecutor(ActionStore store, ObjectMapper mapper, List<ActionAdapter> adapters, MeterRegistry meters) {
        this(store, mapper, adapters, meters, LEASE);
    }

    /** @param lease how long a claim holds its action, and so the longest an attempt holds its slot */
    ActionExecutor(
            ActionStore store,
            ObjectMapper mapper,
            List<ActionAdapter> adapters,
            MeterRegistry meters,
            Duration lease) {
        this.lease = lease;
        this.store = store;
        this.mapper = mapper;
        this.meters = meters;
        for (ActionAdapter adapter : adapters)
            if (this.adapters.put(adapter.kind(), adapter) != null)
                throw new IllegalStateException("Two adapters carry out " + adapter.kind());
        for (ActionKind kind : ActionKind.values())
            if (!this.adapters.containsKey(kind)) throw new IllegalStateException("No adapter carries out " + kind);
        admission = ActionAdmission.of(adapters);
        // Alerts read an uncertain end and a failed record. A counter that first appears at 1 shows no increase to
        // Prometheus, so each of these series exists at 0 from the start.
        for (ActionKind kind : ActionKind.values())
            for (ActionStopReason reason : ActionStopReason.values()) endCounter(kind, ActionState.UNCERTAIN, reason);
        recordFailures("end");
        recordFailures("settle");
    }

    /** Ends what can no longer run, then starts due actions in the free slots. The next run starts a second later. */
    @Scheduled(fixedDelayString = "PT1S", initialDelayString = "PT10S")
    public void runDue() {
        startDue();
    }

    /** {@link #runDue}, returning the attempts it started. */
    List<CompletableFuture<ActionResult>> startDue() {
        if (!running) return List.of();
        for (UUID id : store.findStale(SLOTS * 4, admission)) {
            try {
                store.endStale(id, admission, this::ended).ifPresent(this::recordEnd);
            } catch (RuntimeException failed) {
                // The action stays stale and is ended again on the next run; the others go on.
                log.atWarn()
                        .addKeyValue("event.name", "action.record.failed")
                        .addKeyValue("action.id", id)
                        .addKeyValue("action.step", "end")
                        .addKeyValue("error.type", failed.getClass().getName())
                        .log("Stale action could not end");
                recordFailure("end");
            }
        }
        store.deleteEnded(RETENTION);
        int free = slots.drainPermits();
        if (free == 0) return List.of();
        List<ActionStore.Claim> claims;
        try {
            claims = store.claim(free, lease, admission);
        } catch (RuntimeException failed) {
            slots.release(free);
            throw failed;
        }
        // Each claim keeps one of the permits taken; a claim that does not start returns its own.
        slots.release(free - claims.size());
        var started = new ArrayList<CompletableFuture<ActionResult>>();
        try {
            for (ActionStore.Claim claim : claims) started.add(launch(claim));
        } finally {
            slots.release(claims.size() - started.size());
        }
        return started;
    }

    /**
     * Runs a claim on its own thread. Its slot is free again when the attempt ends or its lease runs out, whichever
     * comes first: a provider that holds a call open past the lease keeps its thread but not its slot, because the
     * reaper ends its action and its late result names a lease that has moved on, so it records nothing.
     */
    private CompletableFuture<ActionResult> launch(ActionStore.Claim claim) {
        CompletableFuture<ActionResult> attempt;
        try {
            attempt = CompletableFuture.supplyAsync(() -> run(claim), attempts)
                    .orTimeout(lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopping) {
            // The replica stops: the lease runs out, and another replica takes the action again.
            slots.release();
            return CompletableFuture.failedFuture(stopping);
        }
        UUID key = UUID.randomUUID();
        inFlight.put(key, attempt);
        return attempt.whenComplete((result, failure) -> {
            slots.release();
            inFlight.remove(key);
            if (failure instanceof TimeoutException)
                event(log.atWarn(), "action.attempt.outlived", claim.id(), claim.workspaceId(), claim.kind())
                        .log("Outbox attempt outlived its lease and keeps running");
            else if (failure != null)
                // The class only, as for an adapter's failure; the lease recovers the action.
                event(log.atWarn(), "action.attempt.failed", claim.id(), claim.workspaceId(), claim.kind())
                        .addKeyValue("action.attempt", claim.attempts())
                        .addKeyValue(
                                "error.type",
                                (failure instanceof CompletionException wrapped && wrapped.getCause() != null
                                                ? wrapped.getCause()
                                                : failure)
                                        .getClass()
                                        .getName())
                        .log("Action attempt failed");
        });
    }

    /**
     * Waits for the attempts in flight to end, at most {@code timeout}.
     *
     * @return whether none is left
     */
    public boolean awaitIdle(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (!inFlight.isEmpty()) {
            long left = end - System.nanoTime();
            if (left <= 0) return false;
            try {
                CompletableFuture.allOf(inFlight.values().toArray(new CompletableFuture<?>[0]))
                        .get(left, TimeUnit.NANOSECONDS);
            } catch (TimeoutException late) {
                return false;
            } catch (ExecutionException failed) {
                // Each attempt records its own failure; waiting goes on for the others.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Claims again after a {@link #stop}: a test context that Spring paused and resumed, for example. */
    @Override
    public void start() {
        running = true;
    }

    /** Claims nothing more and waits for the attempts in flight. */
    @Override
    public void stop() {
        running = false;
        awaitIdle(lease);
    }

    /**
     * Spring waits for the callback at most one shutdown phase ({@code spring.lifecycle.timeout-per-shutdown-phase});
     * an attempt cut after that is recovered by its lease.
     */
    @Override
    public void stop(Runnable callback) {
        running = false;
        Thread.ofVirtual().start(() -> {
            awaitIdle(lease);
            callback.run();
        });
    }

    /** The attempt threads end with the bean, after the lifecycle stopped claiming. */
    @PreDestroy
    void close() {
        attempts.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Adds the action already claimed and carries it out on the caller's thread, for a caller that shows the result
     * at once. Empty when its idempotency key was already enqueued.
     *
     * @throws MaintenanceInProgressException while a database migration runs ({@link ActionStore#start})
     */
    public Optional<ActionResult> runNow(ActionRequest request) {
        return store.start(request, LEASE).map(this::run);
    }

    /** Carries out an admitted lane head now with its registered adapter. */
    public Optional<ActionResult> runNow(UUID id) {
        return store.claim(id, LEASE, admission).map(this::run);
    }

    ActionResult run(ActionStore.Claim claim) {
        return run(claim, Objects.requireNonNull(adapters.get(claim.kind())));
    }

    /**
     * Carries out a claim with the given adapter. The log context supplies the action, its attempt and its workspace
     * for the duration of the attempt. The caller's context is back when it returns: {@link
     * #runNow} runs on a request thread. The keys are the observability contract's names, written here because {@code
     * core} cannot depend on the observability module.
     */
    @SuppressWarnings("try") // The scope follows the attempt through its provider call and commit.
    ActionResult run(ActionStore.Claim claim, ActionAdapter adapter) {
        Map<String, String> caller = MDC.getCopyOfContextMap();
        MDC.put("action.id", claim.id().toString());
        MDC.put("action.kind", claim.kind().name());
        MDC.put("action.attempt", Integer.toString(claim.attempts()));
        if (claim.workspaceId() == null) MDC.remove("workspace.id");
        else MDC.put("workspace.id", claim.workspaceId().toString());
        try (var evaluationCall = evaluation == null
                ? null
                : evaluation.beginCall(
                        EvaluationObservations.CallKind.OUTBOX, claim.id().toString())) {
            return attempt(claim, adapter);
        } finally {
            if (caller == null) MDC.clear();
            else MDC.setContextMap(caller);
        }
    }

    private ActionResult attempt(ActionStore.Claim claim, ActionAdapter adapter) {
        var fenced = new AtomicBoolean();
        var payload = mapper.readTree(claim.payload());
        var attempt = new ActionAttempt(
                claim.id(),
                claim.workspaceId(),
                claim.kind(),
                claim.idempotencyKey(),
                payload,
                claim.attempts(),
                claim.epoch(),
                claim.writeStarted(),
                claim.readbackRef(),
                () -> {
                    boolean begun = store.beginWrite(
                            claim.id(),
                            claim.epoch(),
                            WRITE_MARGIN,
                            () -> claim.writeStarted() || adapter.sourceIsCurrent(claim.workspaceId(), payload));
                    if (begun) fenced.set(true);
                    return begun;
                },
                claim.nativeRecipient() == null
                        ? null
                        : mapper.readValue(claim.nativeRecipient(), PersonIdentity.class));
        ActionResult result;
        try {
            result = adapter.execute(attempt);
        } catch (ActionStore.SourceWithdrawn withdrawn) {
            result = new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        } catch (RuntimeException failure) {
            // The class only: a provider's exception message can carry the payload it refused.
            event(log.atWarn(), "action.attempt.failed", claim.id(), claim.workspaceId(), claim.kind())
                    .addKeyValue("action.attempt", claim.attempts())
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Action attempt failed");
            // An exception after the fence leaves the provider's state unknown: a kind that reads back looks again.
            if (!fenced.get() && !claim.writeStarted()) result = new ActionResult.Retry(backoff(claim.attempts()));
            else if (claim.kind().atMostOnce()) result = new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED);
            else result = new ActionResult.Reread(backoff(claim.attempts()));
        }
        if (evaluation != null) {
            String providerObjectId = result instanceof ActionResult.Done done ? done.providerObjectId() : null;
            evaluation.record(new EvaluationObservations.DeliveryAttempt(
                    claim.id(), claim.workspaceId(), claim.attempts(), providerObjectId));
        }
        Optional<ActionStore.Settled> settled;
        try {
            settled = store.settle(claim.id(), claim.epoch(), result, this::ended);
        } catch (RuntimeException failed) {
            // Nothing recorded: the lease runs out, and the next attempt reads back or ends the action.
            event(log.atWarn(), "action.record.failed", claim.id(), claim.workspaceId(), claim.kind())
                    .addKeyValue("action.step", "settle")
                    .addKeyValue("error.type", failed.getClass().getName())
                    .log("Action result could not be recorded");
            recordFailure("settle");
            settled = Optional.empty();
        }
        if (settled.isEmpty() && !(result instanceof ActionResult.Abandoned))
            event(log.atInfo(), "action.result.late", claim.id(), claim.workspaceId(), claim.kind())
                    .addKeyValue("action.attempt", claim.attempts())
                    .log("Action result arrived after its lease moved on");
        Counter.builder(CoreMetrics.ACTION_ATTEMPTS)
                .description("Outbox attempts by kind and result")
                .tag("kind", claim.kind().name())
                .tag("result", result.getClass().getSimpleName())
                .tag("recorded", Boolean.toString(settled.isPresent()))
                .register(meters)
                .increment();
        settled.ifPresent(this::recordEnd);
        return result;
    }

    /** Hands an action that ended to its adapter, in the transaction that ended it. */
    private void ended(ActionEnded ended) {
        Objects.requireNonNull(adapters.get(ended.kind())).ended(ended);
    }

    /** A result or an end that the database refused, such as a domain row whose end failed. */
    private void recordFailure(String step) {
        recordFailures(step).increment();
    }

    private Counter recordFailures(String step) {
        return Counter.builder(CoreMetrics.ACTION_RECORD_FAILED)
                .description("Outbox results and ends that could not be recorded, by step")
                .tag("step", step)
                .register(meters);
    }

    private void recordEnd(ActionStore.Settled settled) {
        ActionStopReason reason = settled.reason();
        if (reason == null) return;
        endCounter(settled.kind(), settled.state(), reason).increment();
        if (settled.state() == ActionState.UNCERTAIN)
            event(log.atWarn(), "action.ended.uncertain", settled.id(), settled.workspaceId(), settled.kind())
                    .addKeyValue("action.reason", reason.name())
                    .log("Action ended uncertain");
    }

    /**
     * An outbox event names its action; an instance-level action has no workspace. The keys are the observability
     * contract's names, written here because {@code core} cannot depend on the observability module.
     */
    private static LoggingEventBuilder event(
            LoggingEventBuilder builder, String name, UUID id, @Nullable Long workspaceId, ActionKind kind) {
        LoggingEventBuilder named = builder.addKeyValue("event.name", name)
                .addKeyValue("action.id", id)
                .addKeyValue("action.kind", kind.name());
        return workspaceId == null ? named : named.addKeyValue("workspace.id", workspaceId);
    }

    private Counter endCounter(ActionKind kind, ActionState state, ActionStopReason reason) {
        return Counter.builder(CoreMetrics.ACTION_ENDED)
                .description("Outbox actions that ended, by kind, state and reason")
                .tag("kind", kind.name())
                .tag("state", state.name())
                .tag("reason", reason.name())
                .register(meters);
    }

    /** 30 s, then doubling per attempt, at most an hour. */
    public static Duration backoff(int attempt) {
        return Duration.ofSeconds(Math.min(3600, 30L << Math.min(attempt - 1, 7)));
    }
}
