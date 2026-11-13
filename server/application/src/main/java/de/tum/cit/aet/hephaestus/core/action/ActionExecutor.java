package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Carries out the outbox on every server replica, without a leader: replicas share the rows through
 * {@code SKIP LOCKED} claims and leases on the database clock. A crashed replica's lease expires, and another replica
 * takes the action again, or ends it {@link ActionState#UNCERTAIN} when its kind cannot read back.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The executor runs every workspace's actions; each adapter scopes its own provider call")
public class ActionExecutor {
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

    private final ActionStore store;
    private final ObjectMapper mapper;
    private final Map<ActionKind, ActionAdapter> adapters = new EnumMap<>(ActionKind.class);
    private final MeterRegistry meters;

    public ActionExecutor(ActionStore store, ObjectMapper mapper, List<ActionAdapter> adapters, MeterRegistry meters) {
        this.store = store;
        this.mapper = mapper;
        this.meters = meters;
        for (ActionAdapter adapter : adapters)
            if (this.adapters.put(adapter.kind(), adapter) != null)
                throw new IllegalStateException("Two adapters carry out " + adapter.kind());
        for (ActionKind kind : ActionKind.values())
            if (!this.adapters.containsKey(kind)) throw new IllegalStateException("No adapter carries out " + kind);
        // Alerts read an uncertain end and a failed record. A counter that first appears at 1 shows no increase to
        // Prometheus, so each of these series exists at 0 from the start.
        for (ActionKind kind : ActionKind.values())
            for (ActionStopReason reason : ActionStopReason.values()) endCounter(kind, ActionState.UNCERTAIN, reason);
        recordFailures("end");
        recordFailures("settle");
    }

    /**
     * Ends what can no longer run, then carries out up to {@link #SLOTS} due actions at once. The next run starts a
     * second after this one ends.
     */
    @Scheduled(fixedDelayString = "PT1S", initialDelayString = "PT10S")
    public void runDue() {
        for (UUID id : store.findStale(SLOTS * 4)) {
            try {
                store.endStale(id, this::ended).ifPresent(this::recordEnd);
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
        var claims = store.claim(SLOTS, LEASE);
        if (!claims.isEmpty()) runBatch(claims, LEASE);
    }

    /**
     * Runs the claims and waits for them at most {@code wait}. A provider that holds a call open past the lease cannot
     * stall this replica's executor: the attempt keeps running, the reaper ends its action, and its late result names
     * a lease that has moved on, so it records nothing.
     */
    void runBatch(List<ActionStore.Claim> claims, Duration wait) {
        ExecutorService batch = Executors.newVirtualThreadPerTaskExecutor();
        for (ActionStore.Claim claim : claims) batch.execute(() -> run(claim));
        batch.shutdown();
        try {
            if (!batch.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS))
                log.atWarn()
                        .addKeyValue("event.name", "action.batch.outlived")
                        .addKeyValue("action.claims", claims.size())
                        .log("Outbox attempts outlived their lease and keep running");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Adds the action already claimed and carries it out on the caller's thread, for a caller that shows the result
     * at once. Empty when its idempotency key was already enqueued.
     */
    public Optional<ActionResult> runNow(ActionRequest request) {
        return store.start(request, LEASE).map(this::run);
    }

    /** Carries out an admitted lane head now with its registered adapter. */
    public Optional<ActionResult> runNow(UUID id) {
        return store.claim(id, LEASE).map(this::run);
    }

    ActionResult run(ActionStore.Claim claim) {
        return run(claim, Objects.requireNonNull(adapters.get(claim.kind())));
    }

    /**
     * Carries out a claim with the given adapter. Each record of the attempt, the adapter's own included, names the
     * action, its attempt and its workspace in the log context. The caller's context is back when it returns: {@link
     * #runNow} runs on a request thread. The keys are the observability contract's names, written here because {@code
     * core} cannot depend on the observability module.
     */
    ActionResult run(ActionStore.Claim claim, ActionAdapter adapter) {
        Map<String, String> caller = MDC.getCopyOfContextMap();
        MDC.put("action.id", claim.id().toString());
        MDC.put("action.kind", claim.kind().name());
        MDC.put("action.attempt", Integer.toString(claim.attempts()));
        if (claim.workspaceId() == null) MDC.remove("workspace.id");
        else MDC.put("workspace.id", claim.workspaceId().toString());
        try {
            return attempt(claim, adapter);
        } finally {
            if (caller == null) MDC.clear();
            else MDC.setContextMap(caller);
        }
    }

    private ActionResult attempt(ActionStore.Claim claim, ActionAdapter adapter) {
        var fenced = new AtomicBoolean();
        var attempt = new ActionAttempt(
                claim.id(),
                claim.workspaceId(),
                claim.kind(),
                claim.idempotencyKey(),
                mapper.readTree(claim.payload()),
                claim.attempts(),
                claim.epoch(),
                claim.writeStarted(),
                claim.readbackRef(),
                () -> {
                    boolean begun = store.beginWrite(claim.id(), claim.epoch(), WRITE_MARGIN);
                    if (begun) fenced.set(true);
                    return begun;
                });
        ActionResult result;
        try {
            result = adapter.execute(attempt);
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
