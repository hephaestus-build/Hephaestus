package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitState;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Shared admission, fair grants and rate accounting, with the limit that {@link LlmPoolLearning} learns from the
 * provider. The row of a pool is the one thing all servers share: each decision on it holds its row lock, and no one
 * holds a database connection while it waits for a grant or a model. Revocation never releases an active call.
 */
@Component
@ConditionalOnAgentRuntime
public class LlmCallPermits {
    static final int MAINTENANCE_THREADS = 2;
    private static final Logger log = LoggerFactory.getLogger(LlmCallPermits.class);
    static final Duration POLL = Duration.ofMillis(250);
    private static final Duration RECOVERY_POLL = Duration.ofSeconds(2);
    /** A call without a work deadline: its exchange and the one retry of it each get their own ceiling. */
    private static final Duration UNDATED_CALL = LlmProxyService.CONVERSATION_CALL_CEILING.multipliedBy(2);

    private final LlmCallPoolRepository pools;
    private final LlmCallPermitRepository rows;
    private final TransactionTemplate transactions;
    private final LlmCallPoolProperties properties;
    private final Duration permitAging;
    private final String ownerId;
    private final LlmPoolMeters meters;
    private final LlmPoolLearning learning;
    private final Map<UUID, Permit> held = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingRelease = new ConcurrentHashMap<>();
    private final Map<UUID, Waiter> waiting = new ConcurrentHashMap<>();
    private final Map<Long, Admissions> admissions = new ConcurrentHashMap<>();
    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable ScheduledExecutorService fencing;
    private final LlmCallPools callPools;
    private final @Nullable LlmPoolNotifications notifications;

    @Autowired
    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            AgentCapacityProperties capacity,
            MeterRegistry registry,
            LlmCallPools callPools,
            LlmPoolNotifications notifications) {
        this(
                pools,
                rows,
                transactions,
                properties,
                capacity.permitAging(),
                UUID.randomUUID().toString(),
                registry,
                callPools,
                notifications);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            Duration permitAging,
            String ownerId,
            MeterRegistry registry,
            LlmModelResolver models) {
        this(
                pools,
                rows,
                transactions,
                properties,
                permitAging,
                ownerId,
                registry,
                new LlmCallPools(pools, rows, models, properties, permitAging),
                null);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            Duration permitAging,
            String ownerId,
            MeterRegistry registry,
            LlmCallPools callPools,
            @Nullable LlmPoolNotifications notifications) {
        this.notifications = notifications;
        this.pools = pools;
        this.callPools = callPools;
        this.rows = rows;
        this.transactions = transactions;
        this.properties = properties;
        this.permitAging = permitAging;
        this.ownerId = ownerId;
        this.meters = new LlmPoolMeters(registry);
        this.learning = new LlmPoolLearning(pools, rows, transactions, properties, meters);
    }

    /** The limit learned from the provider's answers to this server's calls, on this server's pool meters. */
    LlmPoolLearning learning() {
        return learning;
    }

    /**
     * The exact attempt fence, including its tenant, for a pending call.
     *
     * @param sessionRank where the call's practice session stands in its job's start order; null for a call of no
     *     practice session
     */
    public record Caller(
            @Nullable Long workspaceId,
            UUID sourceId,
            int attempt,
            LlmModelResolver.@Nullable ConnectionRef model,
            @Nullable Integer sessionRank) {}

    // Artifact profiles expose controllers without a migrated database or agent jobs.
    @EventListener(
            value = ApplicationReadyEvent.class,
            condition = "!@environment.matchesProfiles('specs', 'cds-training')")
    public void start() {
        // A pool that models share starts from zero, for its alerts. A model's own pool registers at its first call.
        pools.findAll().stream().filter(pool -> !pool.isDedicated()).forEach(pool -> meters.pool(pool.getSlug()));
        ThreadFactory daemons = task -> {
            Thread thread = new Thread(task, "llm-call-permits");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService started = Executors.newScheduledThreadPool(MAINTENANCE_THREADS, daemons);
        long renewMillis = properties.renewEvery().toMillis();
        var unusedRenewal =
                started.scheduleWithFixedDelay(this::renew, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        // On a thread of its own: a lost database connection can hang the other tasks for minutes, and this one ends
        // the server's calls then. It must never read the database.
        ScheduledExecutorService fence = Executors.newSingleThreadScheduledExecutor(daemons);
        var unusedFence = fence.scheduleWithFixedDelay(this::fence, 250, 250, TimeUnit.MILLISECONDS);
        fencing = fence;
        var unusedGauges =
                started.scheduleWithFixedDelay(this::observeBacklog, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        scheduler = started;
        LlmPoolNotifications changes = notifications;
        if (changes != null) changes.subscribe(this::wake, this::resyncWaiters);
    }

    private void resyncWaiters() {
        waiting.values().stream().map(waiter -> waiter.poolId).distinct().forEach(this::wake);
    }

    private void wake(long poolId) {
        Admissions local = admissions.get(poolId);
        if (local != null) local.changes.incrementAndGet();
        wakeWaiters(poolId);
    }

    private void wakeWaiters(long poolId) {
        waiting.values().stream().filter(waiter -> waiter.poolId == poolId).forEach(Waiter::wake);
    }

    /** Queued by PostgreSQL, so every listener sees the hint only after this transaction commits. */
    private void notifyPool(long poolId) {
        rows.notifyPool(LlmPoolNotifications.CHANNEL, Long.toString(poolId));
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (fencing != null) fencing.shutdownNow();
    }

    /**
     * No provider call starts without a committed grant, or for a caller that has ended.
     *
     * @param band the call's grant priority: its run's band. A call without a workspace (a product feedback draft or
     *     an instance model probe) is {@link AgentRunBand#BACKGROUND}
     * @param deadline when the caller stops waiting, and when the call must have ended; {@code null} for a call that
     *     its own ceiling ends
     */
    public @Nullable Permit acquire(
            long poolId, AgentRunBand band, Caller caller, BooleanSupplier ended, @Nullable Instant deadline) {
        if (caller.workspaceId() == null && band != AgentRunBand.BACKGROUND)
            throw new IllegalArgumentException(
                    "A call without a workspace is product feedback, in the background band");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model calls must acquire capacity outside a database transaction");
        if (ended.getAsBoolean() || expired(deadline)) return null;
        // A pool runs one call without a workspace at a time. Another one does not wait for it: it gets no permit at
        // once, so its caller is free while the first runs.
        boolean instanceCall = caller.workspaceId() == null;
        UUID id = UUID.randomUUID();
        Enqueued enqueued = Objects.requireNonNull(transactions.execute(status -> {
            var admission = pools.lockAdmission(poolId)
                    .orElseThrow(() -> new IllegalStateException("Configured call pool is unavailable"));
            if (!samePool(poolId, caller))
                throw new IllegalStateException("The configured model call pool changed before admission");
            long expired = 0;
            if (instanceCall) {
                // Under the pool lock and after a prune, so a lapsed call cannot hold the pool for good.
                expired = rows.prune(poolId, properties.permitLease().toSeconds());
                if (rows.countActiveProductFeedback(poolId) > 0)
                    return new Enqueued(admission.getSlug(), false, expired);
            }
            rows.enqueue(new LlmCallPermitRepository.Request(
                    id,
                    poolId,
                    ownerId,
                    band,
                    caller.workspaceId(),
                    caller.sourceId(),
                    caller.attempt(),
                    caller.sessionRank()));
            return new Enqueued(admission.getSlug(), true, expired);
        }));
        String poolSlug = enqueued.poolSlug();
        if (enqueued.expired() > 0) meters.pool(poolSlug).expired(enqueued.expired());
        if (!enqueued.queued()) {
            meters.pool(poolSlug).abandoned(band, LlmPoolMeters.INSTANCE_CALL_IN_FLIGHT);
            return null;
        }
        Waiter waiter = new Waiter(poolId, poolSlug, band, deadline);
        waiting.put(id, waiter);
        long began = System.nanoTime();
        boolean handedOff = false;
        Permit admitted = null;
        String stopped = "capacity";
        try {
            // A new request has its round at once, so an idle pool adds no wait to a call.
            boolean arrived = true;
            // Asking whether a call ended can read the database, and hundreds can wait. The handoff asks again.
            long checkedEnded = System.nanoTime();
            while (!expired(deadline)) {
                if (System.nanoTime() - checkedEnded >= RECOVERY_POLL.toNanos()) {
                    if (ended.getAsBoolean()) return null;
                    checkedEnded = System.nanoTime();
                }
                admit(poolId, arrived);
                arrived = false;
                admitted = waiter.awaitGrant();
                if (admitted != null) {
                    meters.pool(admitted.pool).granted(band);
                    meters.pool(admitted.pool).waited(band, "capacity", System.nanoTime() - began);
                    Permit result = handOff(admitted, ended, deadline);
                    handedOff = result != null;
                    return result;
                }
                if (Thread.currentThread().isInterrupted()) return null;
                // Its own grant counts as in flight before this waiter sees it, so only a call that still waits yields.
                if (instanceCall && instanceCallInFlight(poolId) && stillWaiting(id)) {
                    stopped = LlmPoolMeters.INSTANCE_CALL_IN_FLIGHT;
                    return null;
                }
            }
            return null;
        } finally {
            waiting.remove(id);
            // A round may have delivered the grant after the last look.
            if (admitted == null && !waiter.granted.isCompletedExceptionally()) admitted = waiter.granted.getNow(null);
            if (!handedOff && admitted != null) admitted.close();
            if (!handedOff && admitted == null) {
                meters.pool(poolSlug).abandoned(band, stopped);
                meters.pool(poolSlug).waited(band, stopped, System.nanoTime() - began);
                try {
                    // No transport can have opened before handoff, including an uncertain grant commit.
                    pendingRelease.put(id, poolId);
                    // In this order: the delete waits for another server's grant of this request, which the release
                    // then sees and closes. The other way round, the grant would stay open with no one to close it.
                    Boolean freed = transactions.execute(status -> {
                        rows.abandon(id, ownerId);
                        return releaseRow(id, poolId);
                    });
                    pendingRelease.remove(id);
                    if (Boolean.TRUE.equals(freed)) released(poolId);
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

    private boolean samePool(long poolId, Caller caller) {
        var model = caller.model();
        return model == null || callPools.poolFor(model) == poolId;
    }

    private boolean instanceCallInFlight(long poolId) {
        return rows.countActiveProductFeedback(poolId) > 0;
    }

    /** What admission found: the pool's slug, whether the request was queued, and the lapsed permits it pruned. */
    private record Enqueued(String poolSlug, boolean queued, long expired) {}

    private boolean stillWaiting(UUID request) {
        return rows.findById(request)
                .filter(row -> row.getState() == LlmCallPermitState.WAITING)
                .isPresent();
    }

    /**
     * One admission round: grants whatever the pool has room for, in fair order, whichever server's requests they are,
     * and hands this server's grants to its waiters. So one round per server and pool is enough, however many calls
     * wait. The round runs under the pool's row lock, so it writes no waiter's row: {@link #renew()} keeps them alive
     * four times per lease. A new interactive call queues for its round, as it must not lose time to the calls that wait
     * beside it.
     */
    private void admit(long poolId, boolean arrived) {
        Admissions local = admissions.computeIfAbsent(poolId, key -> new Admissions());
        if (!arrived && local.observed == local.changes.get() && System.nanoTime() - local.lastRound < POLL.toNanos())
            return;
        if (arrived) local.lock.lock();
        else if (!local.lock.tryLock()) return;
        long changed = local.changes.get();
        try {
            local.lastRound = System.nanoTime();
            Map<UUID, Waiter> mine = new HashMap<>();
            waiting.forEach((id, waiter) -> {
                if (waiter.poolId == poolId) mine.put(id, waiter);
            });
            Round round = Objects.requireNonNull(transactions.execute(status -> {
                var pool = pools.lockAdmission(poolId)
                        .orElseThrow(() -> new IllegalStateException("Configured call pool is unavailable"));
                long expired = rows.prune(poolId, properties.permitLease().toSeconds());
                boolean granted = pool.getCooldownMillis() <= 0
                        && rows.grantRound(
                                        poolId,
                                        LlmPoolRoom.room(rows, poolId, pool),
                                        LlmPoolRoom.tokenBound(pool),
                                        permitAging.toSeconds())
                                > 0;
                Map<UUID, Boolean> queued = new HashMap<>();
                if (!mine.isEmpty()) {
                    for (var row : rows.findQueued(ownerId, mine.keySet()))
                        queued.put(row.getId(), row.getState() == LlmCallPermitState.ACTIVE);
                }
                if (granted) notifyPool(poolId);
                return new Round(queued, pool.getThrottleMark(), pool.getCooldownMillis() > 0, pool.getSlug(), expired);
            }));
            if (round.expired() > 0) meters.pool(round.slug()).expired(round.expired());
            mine.forEach((id, waiter) -> {
                Boolean active = round.queued().get(id);
                if (active == null) {
                    waiter.granted.completeExceptionally(
                            new IllegalStateException("Queued model request expired before its grant"));
                    waiter.wake();
                }
                // Another server may have granted it before a refusal paused the pool. The grant waits for the pause.
                else if (active && !round.cooling()) {
                    waiter.granted.complete(new Permit(id, waiter, round.throttleMark()));
                    waiter.wake();
                }
            });
        } finally {
            local.observed = changed;
            local.lock.unlock();
            // A hint received during the database read must survive the waiters that already consumed it.
            if (local.changes.get() != changed) wakeWaiters(poolId);
        }
    }

    private boolean releaseRow(UUID id, long poolId) {
        if (rows.release(id, ownerId) != 1) return false;
        notifyPool(poolId);
        return true;
    }

    private void released(long poolId) {
        // The release has committed and its transport is closed. Grant even when only other servers have waiters.
        try {
            admit(poolId, true);
        } catch (RuntimeException failed) {
            log.warn(
                    "Model pool admission after release failed: poolId={}, error={}",
                    poolId,
                    failed.getClass().getSimpleName());
        }
        wake(poolId);
    }

    /** The usage-negotiation retry is another physical request and must debit the same rate budget. */
    public boolean beforeRetry(Permit permit, BooleanSupplier ended, @Nullable Instant deadline) {
        while (!ended.getAsBoolean() && !expired(deadline) && !permit.isRevoked()) {
            Boolean allowed = transactions.execute(status -> {
                var pool = pools.lockAdmission(permit.poolId).orElseThrow();
                if (pool.getCooldownMillis() > 0) return false;
                var room = LlmPoolRoom.room(rows, permit.poolId, pool);
                if (room.rate() < 1 || (permit.band != AgentRunBand.INTERACTIVE && room.notInteractiveRate() < 1))
                    return false;
                return rows.debitRetry(
                                permit.id,
                                ownerId,
                                LlmPoolRoom.tokenBound(pool),
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
            meters.pool(permit.pool).active(permit.band).incrementAndGet();
    }

    /** Retries only releases whose request thread already confirmed transport closure. */
    void renew() {
        try {
            Set<UUID> queued = Set.copyOf(waiting.keySet());
            if (!queued.isEmpty())
                transactions.executeWithoutResult(status ->
                        rows.renew(ownerId, queued, properties.permitLease().toSeconds()));
            Set<UUID> ids = new HashSet<>();
            for (Permit permit : held.values()) {
                if (permit.ended.getAsBoolean()) permit.revoke("attempt_ended");
                // A call past its hard end stops renewing, so its grant expires. One that only has yet to close is no
                // cancelled call.
                if (!permit.overdue()) ids.add(permit.id);
                else if (permit.outcome == null) permit.revoke("past_deadline");
            }
            for (var release : Map.copyOf(pendingRelease).entrySet()) {
                UUID id = release.getKey();
                long poolId = release.getValue();
                Boolean freed = transactions.execute(status -> releaseRow(id, poolId));
                pendingRelease.remove(id);
                if (Boolean.TRUE.equals(freed)) released(poolId);
            }
            if (ids.isEmpty()) return;
            long attempted = System.nanoTime();
            Set<UUID> kept = transactions.execute(status -> {
                rows.renew(ownerId, ids, properties.permitLease().toSeconds());
                return new HashSet<>(
                        rows.findHeld(ownerId, ids, properties.permitLease().toSeconds()));
            });
            for (UUID id : ids) {
                Permit permit = held.get(id);
                if (permit == null) continue;
                if (kept != null && kept.contains(id)) permit.confirmedAt = attempted;
                else if (rows.findById(id)
                        .map(row -> row.getState() == LlmCallPermitState.CLOSED)
                        .orElse(false)) {
                    permit.revoke("lease_lost");
                    permit.forgetClosed();
                } else permit.revoke("lease_lost");
            }
        } catch (RuntimeException failed) {
            meters.renewalFailed();
            log.warn(
                    "Model permit renewal failed: ownerId={}, error={}",
                    ownerId,
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

    /** Publishes what every pool in use stands at, read from the database, so each server reports the same. */
    void observeBacklog() {
        try {
            meters.observe(rows.backlog());
        } catch (RuntimeException failed) {
            log.warn(
                    "Model call pool gauges not read: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    /** What one admission round found: whether each of this server's requests is granted, the limit's mark, and a pause. */
    private record Round(
            Map<UUID, Boolean> queued, @Nullable Long throttleMark, boolean cooling, String slug, long expired) {}

    /** This server's admission rounds of one pool. */
    private static final class Admissions {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile long lastRound = System.nanoTime() - POLL.toNanos();
        private final AtomicLong changes = new AtomicLong();
        private volatile long observed;
    }

    /** One queued call of this server, until a round hands it its grant. */
    private static final class Waiter {
        private final long poolId;
        private final String pool;
        private final AgentRunBand band;
        private final @Nullable Instant deadline;
        private final CompletableFuture<Permit> granted = new CompletableFuture<>();
        private final Semaphore changed = new Semaphore(0);

        private Waiter(long poolId, String pool, AgentRunBand band, @Nullable Instant deadline) {
            this.poolId = poolId;
            this.pool = pool;
            this.band = band;
            this.deadline = deadline;
        }

        private void wake() {
            if (changed.availablePermits() == 0) changed.release();
        }

        /** A hint wakes the waiter at once; polling recovers missed hints, rate refills and expired owners. */
        private @Nullable Permit awaitGrant() {
            try {
                if (!granted.isDone()) {
                    long millis = RECOVERY_POLL.toMillis();
                    if (deadline != null)
                        millis = Math.max(
                                1,
                                Math.min(
                                        millis,
                                        Duration.between(Instant.now(), deadline)
                                                .toMillis()));
                    changed.tryAcquire(millis, TimeUnit.MILLISECONDS);
                }
                return granted.getNow(null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (CompletionException failed) {
                throw new IllegalStateException("Queued model request expired before its grant", failed);
            }
        }
    }

    /** Close only after the upstream operation or its cancelled transport has terminated. */
    public final class Permit implements AutoCloseable {
        private final UUID id;
        final long poolId;
        final String pool;
        final AgentRunBand band;
        private final Sinks.Empty<Void> revoked = Sinks.empty();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile long confirmedAt = System.nanoTime();
        private volatile BooleanSupplier ended = () -> false;
        private volatile boolean served;
        volatile boolean instanceCredential;
        volatile @Nullable Integer firstTokenMillis;
        volatile boolean unanswered;
        private final long grantedAt = System.nanoTime();
        private volatile @Nullable String outcome;
        /** When the provider's part ended, before the proxy settles the call's usage. */
        private volatile long endedAt;
        /** The hard end of the call: every exchange ends by then, so a permit past it has nothing left to protect. */
        private final Instant mustEndBy;

        /** The limit's mark when the call started: an answer about an older limit says nothing about this one. */
        final @Nullable Long throttleMark;

        private volatile long reportedInputTokens = -1;
        private volatile long reportedOutputTokens = -1;

        private Permit(UUID id, Waiter waiter, @Nullable Long throttleMark) {
            this.id = id;
            this.poolId = waiter.poolId;
            this.pool = waiter.pool;
            this.band = waiter.band;
            this.throttleMark = throttleMark;
            this.mustEndBy =
                    waiter.deadline != null ? waiter.deadline : Instant.now().plus(UNDATED_CALL);
        }

        /** A renewal interval past the hard end, so that a call that just ended can still settle and close. */
        private boolean overdue() {
            return Instant.now().isAfter(mustEndBy.plus(properties.renewEvery()));
        }

        /** The provider served this call, which is the evidence that the limit can rise. */
        public void served(boolean instanceCredential) {
            this.instanceCredential = instanceCredential;
            served = true;
        }

        /**
         * How the call to the provider ended: one of {@link LlmPoolMeters#CALL_OUTCOMES}. Set once the provider was
         * asked; a permit closed without one never reached a provider and counts no call.
         */
        public void outcome(String outcome) {
            this.endedAt = System.nanoTime();
            this.outcome = outcome;
        }

        /** The tokens that the provider reported for this call. */
        public void tokens(ProxyTokenUsage usage) {
            meters.pool(pool).tokens(band, usage);
        }

        /** The whole usage of a call that ended on its own, which replaces its debit when the permit closes. */
        public void settled(ProxyTokenUsage usage, LlmApiProtocol protocol) {
            reportedInputTokens = usage.rateInputTokens(protocol);
            reportedOutputTokens = usage.outputTokens();
        }

        /** The provider's answer never reached the caller, because the call asks again instead. */
        public void absorbed() {
            meters.pool(pool).absorbed();
        }

        public Mono<Void> revoked() {
            return revoked.asMono();
        }

        /** Recheck elapsed time so a resumed request does not wait for the periodic fence. */
        public boolean isRevoked() {
            if (System.nanoTime() - confirmedAt > properties.selfFenceAfter().toNanos()) revoke("no_renewal");
            return lost.get();
        }

        private void revoke(String reason) {
            if (closed.get() || !lost.compareAndSet(false, true)) return;
            meters.pool(pool).cancelled(reason);
            revoked.tryEmitEmpty();
        }

        private void forgetClosed() {
            if (!closed.compareAndSet(false, true)) return;
            if (held.remove(id, this)) meters.pool(pool).active(band).decrementAndGet();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            String ended = outcome;
            // The provider's own word outranks an answer without content: a refusal or a failure can come with no text.
            if (ended != null)
                meters.pool(pool)
                        .called(
                                band,
                                unanswered && !LlmPoolMeters.EXPLICIT_OUTCOMES.contains(ended) ? "unanswered" : ended,
                                endedAt - grantedAt);
            if (held.remove(id, this)) meters.pool(pool).active(band).decrementAndGet();
            pendingRelease.put(id, poolId);
            try {
                Boolean freed = transactions.execute(status -> {
                    // First, in the order every grant takes these locks.
                    if (served) learning.learn(this);
                    if (reportedInputTokens >= 0)
                        rows.settleTokens(id, ownerId, reportedInputTokens, reportedOutputTokens);
                    return releaseRow(id, poolId);
                });
                pendingRelease.remove(id);
                if (Boolean.TRUE.equals(freed)) released(poolId);
            } catch (RuntimeException failed) {
                log.warn(
                        "Closed model call awaits permit release: pool={}, error={}",
                        pool,
                        failed.getClass().getSimpleName());
            }
        }
    }
}
