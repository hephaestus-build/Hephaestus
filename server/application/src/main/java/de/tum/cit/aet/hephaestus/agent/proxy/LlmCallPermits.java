package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The permits of this worker's model calls in a {@link LlmCallPool}. A call waits for one before it reaches the
 * provider, and frees it when it ends. The pool's row lock decides each permit, so every worker counts against the same
 * limit. A permit is a lease: this worker renews all its permits with one statement every quarter lease. When no
 * renewal succeeded for three quarters of a lease, it cancels the calls itself, half a lease before the database frees
 * their permits for other workers. A rate limit answer pauses the whole pool for the provider's {@code Retry-After}.
 */
@Component
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Fleet-wide model call permits; a pool is shared across workspaces.")
public class LlmCallPermits {

    private static final Logger log = LoggerFactory.getLogger(LlmCallPermits.class);

    /** How long a waiting call sleeps before it asks the pool again, unless a call of this worker ends first. */
    static final Duration POLL = Duration.ofSeconds(1);

    /** The pause after a rate limit answer that names no valid time. */
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(5);

    private static final Duration FENCE_CHECK = Duration.ofSeconds(1);

    private final LlmCallPoolRepository pools;
    private final LlmCallPermitRepository permits;
    private final TransactionTemplate transactions;
    private final LlmCallPoolProperties properties;
    private final String workerId;
    private final LlmPoolMeters meters;

    /** The permits this worker holds a row for, by id. */
    private final Map<UUID, Permit> held = new ConcurrentHashMap<>();

    private final ReentrantLock released = new ReentrantLock();
    private final Condition anyReleased = released.newCondition();

    /** Counts releases, so a waiter tells a release from a spurious wakeup. Guarded by {@link #released}. */
    private long releases;

    private @Nullable ScheduledExecutorService scheduler;

    @Autowired
    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository permits,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            WorkerProperties workerProperties,
            MeterRegistry registry) {
        this(pools, permits, transactions, properties, workerProperties.resolvedWorkerId(), registry);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository permits,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            String workerId,
            MeterRegistry registry) {
        this.pools = pools;
        this.permits = permits;
        this.transactions = transactions;
        this.properties = properties;
        this.workerId = workerId;
        this.meters = new LlmPoolMeters(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            pools.findAll().forEach(pool -> meters.pool(pool.getSlug()));
        } catch (RuntimeException e) {
            // A pool's meters then appear with its first call.
            log.warn(
                    "Pool meters not registered at start: error={}",
                    e.getClass().getSimpleName());
        }
        // Two threads: a renewal that waits for the database must not hold back the fence check.
        ScheduledExecutorService started = Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "llm-call-permits");
            thread.setDaemon(true);
            return thread;
        });
        long renewMillis = properties.renewEvery().toMillis();
        var unusedRenewal =
                started.scheduleWithFixedDelay(this::renew, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        long fenceMillis = FENCE_CHECK.toMillis();
        var unusedFence = started.scheduleWithFixedDelay(this::fence, fenceMillis, fenceMillis, TimeUnit.MILLISECONDS);
        scheduler = started;
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /**
     * Waits until the pool admits the call and no cooldown holds it, or the call ends.
     *
     * @param ended whether the call has ended, for example because its attempt was cancelled
     * @param deadline the end of the call's work time; {@code null} for none
     * @return {@code null} when the call ended or reached its deadline first
     */
    public @Nullable Permit acquire(
            long poolId, LlmCallClass callClass, BooleanSupplier ended, @Nullable Instant deadline) {
        long started = System.nanoTime();
        String waitedFor = "none";
        while (true) {
            Decision decision = transactions.execute(status -> decide(poolId, callClass));
            String slug = decision == null ? null : decision.pool();
            if (decision == null || slug == null) {
                // The pool was deleted since the model was read: the model is no longer limited.
                return new Permit(null, poolId, null, callClass);
            }
            LlmPoolMeters.Pool pool = meters.pool(slug);
            Permit permit = decision.permit();
            if (permit != null) {
                if (permit.id != null) {
                    held.put(permit.id, permit);
                    pool.active(callClass).incrementAndGet();
                }
                long waited = System.nanoTime() - started;
                pool.waited(callClass, waitedFor, waited);
                if (waited >= POLL.toNanos()) waitedEvent(slug, callClass, waitedFor, waited, "admitted");
                return permit;
            }
            waitedFor = decision.waitedFor();
            if (decision.cooldownMillis() > 0) pool.coolingFor(decision.cooldownMillis());
            long waitMillis = decision.cooldownMillis() > 0
                    ? Math.min(POLL.toMillis(), decision.cooldownMillis())
                    : POLL.toMillis();
            if (deadline != null) {
                waitMillis = Math.min(
                        waitMillis, Duration.between(Instant.now(), deadline).toMillis());
            }
            if (ended.getAsBoolean() || waitMillis <= 0 || !awaitRelease(waitMillis)) {
                pool.abandoned(callClass, waitedFor);
                waitedEvent(slug, callClass, waitedFor, System.nanoTime() - started, "ended");
                return null;
            }
        }
    }

    /**
     * One look at the pool under its row lock.
     *
     * @param pool the pool's slug; {@code null} when the pool no longer exists
     * @param permit the permit when the pool admits the call
     * @param waitedFor why the call waits: {@code capacity} or {@code cooldown}
     */
    private record Decision(@Nullable String pool, @Nullable Permit permit, String waitedFor, long cooldownMillis) {}

    private Decision decide(long poolId, LlmCallClass callClass) {
        var admission = pools.lockAdmission(poolId).orElse(null);
        if (admission == null) return new Decision(null, null, "none", 0);
        String pool = admission.getSlug();
        if (admission.getCooldownMillis() > 0) {
            return new Decision(pool, null, "cooldown", admission.getCooldownMillis());
        }
        Integer max = admission.getMaxConcurrentCalls();
        if (max == null) return new Decision(pool, new Permit(null, poolId, pool, callClass), "none", 0);
        int reclaimed = permits.reclaimStale(poolId, properties.reclaimAfter().toSeconds());
        if (reclaimed > 0) {
            log.warn("Reclaimed model call permits that no worker renewed: pool={}, permits={}", pool, reclaimed);
            meters.pool(pool).reclaimed(reclaimed);
        }
        int limit = callClass.interactive() ? max : max - admission.getInteractiveReserve();
        if (permits.countByPoolId(poolId) >= limit) return new Decision(pool, null, "capacity", 0);
        UUID id = UUID.randomUUID();
        permits.insert(id, poolId, workerId, callClass.name());
        return new Decision(pool, new Permit(id, poolId, pool, callClass), "none", 0);
    }

    // One event for a wait that changed what happened to the call; the job, turn and workspace come from the MDC.
    private static void waitedEvent(String pool, LlmCallClass callClass, String reason, long nanos, String outcome) {
        log.atInfo()
                .addKeyValue("event.name", "llm.pool.permit.waited")
                .addKeyValue("pool", pool)
                .addKeyValue("class", callClass.tag())
                .addKeyValue("reason", reason)
                .addKeyValue("outcome", outcome)
                .addKeyValue("wait.ms", TimeUnit.NANOSECONDS.toMillis(nanos))
                .log("Model call waited for its pool");
    }

    /** @return {@code false} when interrupted */
    private boolean awaitRelease(long millis) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(millis);
        released.lock();
        try {
            long seen = releases;
            while (releases == seen && remaining > 0) {
                remaining = anyReleased.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            released.unlock();
        }
    }

    private void signalRelease() {
        released.lock();
        try {
            releases++;
            anyReleased.signalAll();
        } finally {
            released.unlock();
        }
    }

    /** Renews every permit this worker holds with one statement; a permit the database no longer has is cancelled. */
    void renew() {
        Set<UUID> ids = new HashSet<>(held.keySet());
        if (ids.isEmpty()) return;
        long attempted = System.nanoTime();
        try {
            Set<UUID> kept = transactions.execute(status -> {
                int renewed = permits.renew(workerId, ids);
                return renewed == ids.size() ? ids : new HashSet<>(permits.findHeld(workerId, ids));
            });
            for (UUID id : ids) {
                Permit permit = held.get(id);
                if (permit == null) continue;
                if (kept != null && kept.contains(id)) {
                    permit.confirmedAt = attempted;
                } else if (pools.existsById(permit.poolId)) {
                    permit.revoke("lease_lost");
                } else {
                    // The admin deleted the pool, and its permits with it: the call is no longer limited.
                    permit.forget();
                }
            }
        } catch (RuntimeException e) {
            meters.renewalFailed();
            log.warn(
                    "Model call permit renewal failed: workerId={}, permits={}, error={}",
                    workerId,
                    ids.size(),
                    e.getClass().getSimpleName());
        }
    }

    /** Cancels each call whose permit this worker could not renew in time, before the database frees it. */
    void fence() {
        fence(System.nanoTime());
    }

    void fence(long now) {
        long limit = properties.selfFenceAfter().toNanos();
        try {
            held.values().stream()
                    .filter(permit -> now - permit.confirmedAt > limit)
                    .forEach(permit -> permit.revoke("no_renewal"));
        } catch (RuntimeException e) {
            // A scheduled task that throws is never run again, and this one must keep running.
            log.warn(
                    "Model call permit fence check failed: error={}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Pauses the pool after its provider answered with a rate limit. {@code retry-after-ms} wins over
     * {@code Retry-After}; without either, the pause is {@link #DEFAULT_COOLDOWN}. A pause never exceeds the
     * configured maximum, and never shortens a longer pause that another call set.
     */
    public void coolDown(Permit permit, HttpHeaders headers) {
        if (permit.pool == null) return;
        Duration asked = RetryAfter.parse(headers, Instant.now());
        Duration pause = asked == null ? DEFAULT_COOLDOWN : asked;
        if (pause.compareTo(properties.maxCooldown()) > 0) pause = properties.maxCooldown();
        long millis = Math.max(pause.toMillis(), 1);
        LlmPoolMeters.Pool pool = meters.pool(permit.pool);
        pool.throttled();
        pool.coolingFor(millis);
        try {
            transactions.executeWithoutResult(status -> pools.coolDown(permit.poolId, millis));
        } catch (RuntimeException e) {
            log.warn(
                    "Pool cooldown not saved: pool={}, error={}",
                    permit.pool,
                    e.getClass().getSimpleName());
            return;
        }
        log.atInfo()
                .addKeyValue("event.name", "llm.pool.cooldown.started")
                .addKeyValue("pool", permit.pool)
                .addKeyValue("class", permit.callClass.tag())
                .addKeyValue("cooldown.ms", millis)
                .addKeyValue("retry_after.valid", asked != null)
                .log("Model provider answered with a rate limit; the pool pauses");
    }

    /** One call's share of its pool. Close it when the call ends. */
    public final class Permit implements AutoCloseable {

        /** {@code null} when the pool does not limit its calls, so no row is held. */
        private final @Nullable UUID id;

        private final long poolId;

        /** {@code null} when the pool was deleted while the call asked for it. */
        private final @Nullable String pool;

        private final LlmCallClass callClass;
        private final Sinks.Empty<Void> revoked = Sinks.empty();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();

        /** When this worker last knew the row to be renewed, on its own monotonic clock. */
        private volatile long confirmedAt = System.nanoTime();

        private Permit(@Nullable UUID id, long poolId, @Nullable String pool, LlmCallClass callClass) {
            this.id = id;
            this.poolId = poolId;
            this.pool = pool;
            this.callClass = callClass;
        }

        /** Completes when this worker can no longer vouch for the permit: the call must stop. */
        public Mono<Void> revoked() {
            return revoked.asMono();
        }

        public boolean isRevoked() {
            return lost.get();
        }

        private void revoke(String reason) {
            if (closed.get() || pool == null || !lost.compareAndSet(false, true)) return;
            log.warn("Model call cancelled, its permit is no longer held: pool={}, reason={}", pool, reason);
            meters.pool(pool).cancelled(reason);
            revoked.tryEmitEmpty();
        }

        /** Stops holding a permit whose row went with its deleted pool. */
        private void forget() {
            if (id == null || pool == null || !closed.compareAndSet(false, true)) return;
            LlmCallPermits.this.held.remove(id);
            meters.pool(pool).active(callClass).decrementAndGet();
            signalRelease();
        }

        @Override
        public void close() {
            UUID held = id;
            if (held == null || pool == null || !closed.compareAndSet(false, true)) return;
            LlmCallPermits.this.held.remove(held);
            meters.pool(pool).active(callClass).decrementAndGet();
            try {
                transactions.executeWithoutResult(status -> permits.release(held));
            } catch (RuntimeException e) {
                // This worker stops renewing it, so the database frees it after the lease.
                log.warn(
                        "Model call permit not released: pool={}, error={}",
                        pool,
                        e.getClass().getSimpleName());
            }
            signalRelease();
        }
    }
}
