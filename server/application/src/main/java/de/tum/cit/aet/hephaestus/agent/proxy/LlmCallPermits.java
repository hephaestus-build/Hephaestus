package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Shared admission, fair grants and rate accounting. Revocation never releases an active call. */
@Component
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Instance-owned endpoint capacity, shared across all workers and workspaces.")
public class LlmCallPermits {
    private static final Logger log = LoggerFactory.getLogger(LlmCallPermits.class);
    static final Duration POLL = Duration.ofMillis(250);
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(5);
    private final LlmCallPoolRepository pools;
    private final LlmCallPermitRepository rows;
    private final TransactionTemplate transactions;
    private final LlmCallPoolProperties properties;
    private final String workerId;
    private final LlmPoolMeters meters;
    private final Map<UUID, Permit> held = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRelease = ConcurrentHashMap.newKeySet();
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private final Map<Long, ReentrantLock> localAdmissions = new ConcurrentHashMap<>();
    private @Nullable ScheduledExecutorService scheduler;
    private final LlmModelResolver models;

    @Autowired
    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            WorkerProperties worker,
            MeterRegistry registry,
            LlmModelResolver models) {
        this(pools, rows, transactions, properties, worker.resolvedWorkerId(), registry, models);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            String workerId,
            MeterRegistry registry,
            LlmModelResolver models) {
        this.pools = pools;
        this.models = models;
        this.rows = rows;
        this.transactions = transactions;
        this.properties = properties;
        this.workerId = workerId;
        this.meters = new LlmPoolMeters(registry);
    }

    /** The exact attempt fence, including its tenant, for a pending call or claim reservation. */
    public record Caller(
            @Nullable Long workspaceId, UUID sourceId, int attempt, LlmModelResolver.@Nullable ConnectionRef model) {}

    // Artifact profiles expose controllers without a migrated database or worker activity.
    @EventListener(
            value = ApplicationReadyEvent.class,
            condition = "!@environment.matchesProfiles('specs', 'cds-training')")
    public void start() {
        pools.findAll().forEach(pool -> meters.pool(pool.getSlug()));
        ScheduledExecutorService started = Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "llm-call-permits");
            thread.setDaemon(true);
            return thread;
        });
        long renewMillis = properties.renewEvery().toMillis();
        var unusedRenewal =
                started.scheduleWithFixedDelay(this::renew, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        var unusedFence = started.scheduleWithFixedDelay(this::fence, 250, 250, TimeUnit.MILLISECONDS);
        scheduler = started;
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** No provider call starts without a committed grant, and an ended caller never receives one. */
    public @Nullable Permit acquire(
            long poolId, LlmCallClass callClass, Caller caller, BooleanSupplier ended, @Nullable Instant deadline) {
        if ((caller.workspaceId() == null) != (callClass == LlmCallClass.PRODUCT_FEEDBACK))
            throw new IllegalArgumentException("Product feedback calls have no workspace; all other calls require one");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model calls must acquire capacity outside a database transaction");
        if (ended.getAsBoolean() || expired(deadline)) return null;
        relinquishOtherReservations(poolId, caller);
        var activated = new AtomicReference<@Nullable Permit>();
        Permit reserved;
        try {
            reserved = transactions.execute(status -> {
                Permit candidate = activateReservation(poolId, callClass, caller);
                activated.set(candidate);
                return candidate;
            });
        } catch (RuntimeException | Error failed) {
            Permit candidate = activated.get();
            if (candidate != null) candidate.close();
            throw failed;
        }
        if (reserved != null) return handOff(reserved, ended, deadline);
        UUID id = UUID.randomUUID();
        String poolSlug = Objects.requireNonNull(transactions.execute(status -> {
            var admission = pools.lockAdmission(poolId)
                    .orElseThrow(() -> new IllegalStateException("Configured call pool is unavailable"));
            if (!samePool(poolId, caller))
                throw new IllegalStateException("The configured model call pool changed before admission");
            rows.enqueue(new LlmCallPermitRepository.Request(
                    id,
                    poolId,
                    workerId,
                    callClass.name(),
                    caller.workspaceId(),
                    caller.sourceId(),
                    caller.attempt(),
                    "WAITING"));
            return admission.getSlug();
        }));
        waiting.add(id);
        long began = System.nanoTime();
        boolean handedOff = false;
        Permit admitted = null;
        try {
            while (!ended.getAsBoolean() && !expired(deadline)) {
                admitted = tryGrant(id, poolId, callClass, "ACTIVE");
                if (admitted != null) {
                    meters.pool(admitted.pool).waited(callClass, "capacity", System.nanoTime() - began);
                    Permit result = handOff(admitted, ended, deadline);
                    handedOff = result != null;
                    return result;
                }
                if (!pause()) return null;
            }
            return null;
        } finally {
            waiting.remove(id);
            if (!handedOff && admitted != null) admitted.close();
            if (!handedOff && admitted == null) {
                meters.pool(poolSlug).abandoned(callClass, "capacity");
                meters.pool(poolSlug).waited(callClass, "capacity", System.nanoTime() - began);
                try {
                    // No transport can have opened before handoff, including an uncertain grant commit.
                    pendingRelease.add(id);
                    transactions.executeWithoutResult(status -> {
                        rows.release(id, workerId);
                        rows.abandon(id, workerId);
                    });
                    pendingRelease.remove(id);
                } catch (RuntimeException failed) {
                    log.warn(
                            "Queued model request awaits cleanup: poolId={}, error={}",
                            poolId,
                            failed.getClass().getSimpleName());
                }
            }
        }
    }

    /** Until return succeeds, this method owns cleanup even if the final ownership read throws. */
    private @Nullable Permit handOff(Permit permit, BooleanSupplier ended, @Nullable Instant deadline) {
        boolean handedOff = false;
        try {
            register(permit);
            permit.ended = ended;
            if (ended.getAsBoolean() || expired(deadline) || permit.isRevoked()) return null;
            handedOff = true;
            return permit;
        } finally {
            if (!handedOff) permit.close();
        }
    }

    private void relinquishOtherReservations(long requestedPool, Caller caller) {
        // Commit before taking the requested pool lock. An unused reservation must never wait on another pool.
        var released = transactions.execute(status -> rows.releaseOtherReservations(
                requestedPool, workerId, caller.workspaceId(), caller.sourceId(), caller.attempt()));
        if (released == null) return;
        for (UUID id : released) {
            Permit permit = held.get(id);
            if (permit != null) permit.forgetClosed();
        }
    }

    public @Nullable Long poolFor(LlmModelResolver.ConnectionRef ref) {
        return models.resolveCallPool(ref);
    }

    private boolean samePool(long poolId, Caller caller) {
        var model = caller.model();
        return model == null || Objects.equals(poolFor(model), poolId);
    }

    /** A fair claim can be dispatched now; a full pool or a queued proxy call needs another poll. */
    public record ClaimReservation(
            boolean granted, @Nullable UUID nextClaim) {}

    /** A claim held by another admission check must not strand the pool's fair queue. */
    public void withdrawClaim(Caller caller) {
        if (caller.workspaceId() == null) throw new IllegalArgumentException("Review claims require a workspace");
        rows.withdrawClaim(caller.workspaceId(), caller.sourceId(), caller.attempt());
    }

    /** Called inside the review claim transaction, before any Pod can be created. */
    public ClaimReservation reserve(long poolId, LlmCallClass callClass, Caller caller) {
        if (caller.workspaceId() == null || callClass == LlmCallClass.PRODUCT_FEEDBACK)
            throw new IllegalArgumentException("Only workspace review calls can reserve capacity");
        LlmCallPoolRepository.Admission pool = pools.lockAdmission(poolId).orElseThrow();
        if (!samePool(poolId, caller)) return new ClaimReservation(false, null);
        rows.prune(poolId, properties.permitLease().toSeconds());
        UUID id = rows.waitingClaim(poolId, caller.sourceId(), caller.workspaceId(), caller.attempt())
                .orElse(null);
        if (id == null) {
            id = UUID.randomUUID();
            rows.enqueue(new LlmCallPermitRepository.Request(
                    id,
                    poolId,
                    workerId,
                    callClass.name(),
                    caller.workspaceId(),
                    caller.sourceId(),
                    caller.attempt(),
                    "CLAIM_WAITING"));
        } else rows.pollClaim(id, workerId);
        if (!room(poolId, pool, callClass)) return new ClaimReservation(false, null);
        UUID next = rows.next(poolId, false).orElse(null);
        if (!id.equals(next))
            return new ClaimReservation(
                    false, next == null ? null : rows.claimJobId(next).orElse(null));
        if (rows.grant(id, "RESERVED", tokenBound(pool), workerId) != 1)
            throw new IllegalStateException("Claim reservation was lost");
        Permit permit = new Permit(id, poolId, pool.getSlug(), callClass);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                register(permit);
            }
        });
        return new ClaimReservation(true, null);
    }

    // A proxy request already resolved its credential. Changed membership aborts that request; a claim can retry.
    private @Nullable Permit activateReservation(long poolId, LlmCallClass callClass, Caller caller) {
        var pool = pools.lockAdmission(poolId).orElseThrow();
        rows.prune(poolId, properties.permitLease().toSeconds());
        if (!samePool(poolId, caller))
            throw new IllegalStateException("The configured model call pool changed before admission");
        UUID id = rows.reservation(poolId, caller.sourceId(), caller.attempt(), workerId, caller.workspaceId())
                .orElse(null);
        if (id == null) return null;
        if (rows.activate(id, workerId) != 1) return null;
        Permit permit = held.get(id);
        if (permit == null) {
            permit = new Permit(id, poolId, pool.getSlug(), callClass);
        }
        return permit;
    }

    private @Nullable Permit tryGrant(UUID id, long poolId, LlmCallClass callClass, String state) {
        ReentrantLock local = localAdmissions.computeIfAbsent(poolId, key -> new ReentrantLock());
        if (!local.tryLock()) return null;
        try {
            Permit granted = transactions.execute(status -> {
                var pool = pools.lockAdmission(poolId).orElseThrow();
                if (rows.renew(workerId, Set.of(id), properties.permitLease().toSeconds()) != 1)
                    throw new IllegalStateException("Queued model request expired before its grant");
                rows.prune(poolId, properties.permitLease().toSeconds());
                if (!room(poolId, pool, callClass)
                        || !rows.next(poolId, callClass.interactive())
                                .filter(id::equals)
                                .isPresent()) return null;
                if (rows.grant(id, state, tokenBound(pool), workerId) != 1) return null;
                Permit permit = new Permit(id, poolId, pool.getSlug(), callClass);
                return permit;
            });
            return granted;
        } finally {
            local.unlock();
        }
    }

    private boolean room(long poolId, LlmCallPoolRepository.Admission pool, LlmCallClass callClass) {
        if (pool.getCooldownMillis() > 0) return false;
        if (callClass == LlmCallClass.PRODUCT_FEEDBACK && rows.countActiveProductFeedback(poolId) >= 1) return false;
        Integer maximum = pool.getMaxConcurrentCalls();
        if (maximum != null
                && (rows.countActive(poolId) >= maximum
                        || (!callClass.interactive()
                                && rows.countBackground(poolId) >= maximum - pool.getInteractiveReserve())))
            return false;
        return rateRoom(poolId, pool, callClass);
    }

    private boolean rateRoom(long poolId, LlmCallPoolRepository.Admission pool, LlmCallClass callClass) {
        var used = rows.rates(poolId);
        Integer requests = pool.getRequestsPerMinute();
        Long tokens = pool.getTokensPerMinute();
        long bound = tokenBound(pool);
        if (requests != null && used.getRequests() >= requests) return false;
        if (tokens != null && used.getTokens() > tokens - bound) return false;
        if (callClass.interactive()) return true;
        if (requests != null && used.getBackgroundRequests() >= backgroundShare(requests, pool, 1)) return false;
        return tokens == null || used.getBackgroundTokens() <= backgroundShare(tokens, pool, bound) - bound;
    }

    private static long backgroundShare(long budget, LlmCallPoolRepository.Admission pool, long perCall) {
        Integer maximum = pool.getMaxConcurrentCalls();
        if (maximum == null || pool.getInteractiveReserve() == 0) return budget;
        long background = (long) maximum - pool.getInteractiveReserve();
        long proportional = (budget / maximum) * background + (budget % maximum) * background / maximum;
        int interactive = pool.getInteractiveReserve();
        long reserved = perCall <= budget / interactive ? perCall * interactive : budget;
        return Math.max(0, Math.min(proportional, budget - reserved));
    }

    private static long tokenBound(LlmCallPoolRepository.Admission pool) {
        Long bound = pool.getTokensPerCall();
        return bound == null ? 0 : bound;
    }

    /** The usage-negotiation retry is another physical request and must debit the same rate budget. */
    public boolean beforeRetry(Permit permit, BooleanSupplier ended, @Nullable Instant deadline) {
        while (!ended.getAsBoolean() && !expired(deadline) && !permit.isRevoked()) {
            Boolean allowed = transactions.execute(status -> {
                var pool = pools.lockAdmission(permit.poolId).orElseThrow();
                if (pool.getCooldownMillis() > 0 || !rateRoom(permit.poolId, pool, permit.callClass)) return false;
                return rows.debitRetry(
                                permit.id,
                                workerId,
                                tokenBound(pool),
                                properties.permitLease().toSeconds())
                        == 1;
            });
            if (Boolean.TRUE.equals(allowed)) return true;
            if (!pause()) return false;
        }
        return false;
    }

    private static boolean expired(@Nullable Instant deadline) {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    private static boolean pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(POLL.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void register(Permit permit) {
        if (held.putIfAbsent(permit.id, permit) == null)
            meters.pool(permit.pool).active(permit.callClass).incrementAndGet();
    }

    /** Retries only releases whose request thread already confirmed transport closure. */
    void renew() {
        try {
            Set<UUID> queued = Set.copyOf(waiting);
            if (!queued.isEmpty())
                transactions.executeWithoutResult(status ->
                        rows.renew(workerId, queued, properties.permitLease().toSeconds()));
            var abandoned = transactions.execute(status -> rows.abandonedReservations(workerId));
            if (abandoned != null) {
                for (UUID id : abandoned) {
                    Integer released = transactions.execute(status -> rows.releaseReservation(id, workerId));
                    Permit permit = held.get(id);
                    if (permit == null) continue;
                    if (released != null && released == 1) permit.forgetClosed();
                    else permit.revoke("attempt_ended");
                }
            }
            for (Permit permit : held.values()) {
                if (permit.ended.getAsBoolean()) permit.revoke("attempt_ended");
            }
            for (UUID id : Set.copyOf(pendingRelease)) {
                transactions.executeWithoutResult(status -> rows.release(id, workerId));
                pendingRelease.remove(id);
            }
            Set<UUID> ids = new HashSet<>(held.keySet());
            if (ids.isEmpty()) return;
            long attempted = System.nanoTime();
            Set<UUID> kept = transactions.execute(status -> {
                rows.renew(workerId, ids, properties.permitLease().toSeconds());
                return new HashSet<>(
                        rows.findHeld(workerId, ids, properties.permitLease().toSeconds()));
            });
            for (UUID id : ids) {
                Permit permit = held.get(id);
                if (permit == null) continue;
                if (kept != null && kept.contains(id)) permit.confirmedAt = attempted;
                else if (rows.findById(id)
                        .map(row -> row.getState().equals("CLOSED"))
                        .orElse(false)) {
                    permit.revoke("lease_lost");
                    permit.forgetClosed();
                } else permit.revoke("lease_lost");
            }
        } catch (RuntimeException failed) {
            meters.renewalFailed();
            log.warn(
                    "Model permit renewal failed: workerId={}, error={}",
                    workerId,
                    failed.getClass().getSimpleName());
        }
    }

    void fence() {
        fence(System.nanoTime());
    }

    void fence(long now) {
        held.values().stream()
                .filter(permit ->
                        now - permit.confirmedAt > properties.selfFenceAfter().toNanos())
                .forEach(permit -> permit.revoke("no_renewal"));
    }

    /** Only an instance-controlled credential can impose a shared provider cooldown on other tenants. */
    public void coolDown(Permit permit, HttpHeaders headers) {
        Duration asked = RetryAfter.parse(headers, Instant.now());
        Duration pause = asked == null ? DEFAULT_COOLDOWN : asked;
        if (pause.compareTo(properties.maxCooldown()) > 0) pause = properties.maxCooldown();
        long millis = Math.max(1, pause.toMillis());
        transactions.executeWithoutResult(status -> pools.coolDown(permit.poolId, millis));
        meters.pool(permit.pool).throttled();
        meters.pool(permit.pool).coolingFor(millis);
    }

    /** Close only after the upstream operation or its cancelled transport has terminated. */
    public final class Permit implements AutoCloseable {
        private final UUID id;
        private final long poolId;
        private final String pool;
        private final LlmCallClass callClass;
        private final Sinks.Empty<Void> revoked = Sinks.empty();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile long confirmedAt = System.nanoTime();
        private volatile BooleanSupplier ended = () -> false;

        private Permit(UUID id, long poolId, String pool, LlmCallClass callClass) {
            this.id = id;
            this.poolId = poolId;
            this.pool = pool;
            this.callClass = callClass;
        }

        public Mono<Void> revoked() {
            return revoked.asMono();
        }

        public boolean isRevoked() {
            return lost.get();
        }

        private void revoke(String reason) {
            if (closed.get() || !lost.compareAndSet(false, true)) return;
            meters.pool(pool).cancelled(reason);
            revoked.tryEmitEmpty();
        }

        private void forgetClosed() {
            if (!closed.compareAndSet(false, true)) return;
            if (held.remove(id, this)) meters.pool(pool).active(callClass).decrementAndGet();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (held.remove(id, this)) meters.pool(pool).active(callClass).decrementAndGet();
            pendingRelease.add(id);
            try {
                transactions.executeWithoutResult(status -> rows.release(id, workerId));
                pendingRelease.remove(id);
            } catch (RuntimeException failed) {
                log.warn(
                        "Closed model call awaits permit release: pool={}, error={}",
                        pool,
                        failed.getClass().getSimpleName());
            }
        }
    }
}
