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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    static final Duration LEASE = Duration.ofMinutes(2);

    /** A write starts only while this much of the lease is left, so no reclaim overlaps a provider call. */
    static final Duration WRITE_MARGIN = Duration.ofSeconds(60);

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
    }

    /**
     * Ends what can no longer run, then carries out up to {@link #SLOTS} due actions at once and returns when all of
     * them settled. The next run starts a second after this one ends, so a slow provider delays its batch by at most
     * a lease.
     */
    @Scheduled(fixedDelayString = "PT1S", initialDelayString = "PT10S")
    public void runDue() {
        store.endStale().forEach(this::recordEnd);
        var claims = store.claim(SLOTS, LEASE);
        if (claims.isEmpty()) return;
        try (ExecutorService batch = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ActionStore.Claim claim : claims) batch.execute(() -> run(claim));
        }
    }

    /**
     * Adds the action already claimed and carries it out on the caller's thread, for a caller that shows the result
     * at once. Empty when its idempotency key was already enqueued.
     */
    public Optional<ActionResult> runNow(ActionRequest request) {
        return store.start(request, LEASE).map(this::run);
    }

    ActionResult run(ActionStore.Claim claim) {
        ActionAdapter adapter = Objects.requireNonNull(adapters.get(claim.kind()));
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
            log.warn(
                    "Action attempt failed: id={}, kind={}, attempt={}, error={}",
                    claim.id(),
                    claim.kind(),
                    claim.attempts(),
                    failure.getClass().getSimpleName());
            // An exception after the fence leaves the provider's state unknown.
            result = fenced.get() || claim.writeStarted()
                    ? new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED)
                    : new ActionResult.Retry(backoff(claim.attempts()));
        }
        var settled = store.settle(claim.id(), claim.epoch(), result);
        if (settled.isEmpty() && !(result instanceof ActionResult.Abandoned))
            log.info("Action result arrived after its lease moved on: id={}, kind={}", claim.id(), claim.kind());
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

    private void recordEnd(ActionStore.Settled settled) {
        ActionStopReason reason = settled.reason();
        if (reason == null) return;
        Counter.builder(CoreMetrics.ACTION_ENDED)
                .description("Outbox actions that ended, by kind, state and reason")
                .tag("kind", settled.kind().name())
                .tag("state", settled.state().name())
                .tag("reason", reason.name())
                .register(meters)
                .increment();
        if (settled.state() == ActionState.UNCERTAIN)
            log.warn("Action ended uncertain: kind={}, reason={}", settled.kind(), reason);
    }

    /** 30 s, then doubling per attempt, at most an hour. */
    public static Duration backoff(int attempt) {
        return Duration.ofSeconds(Math.min(3600, 30L << Math.min(attempt - 1, 7)));
    }
}
