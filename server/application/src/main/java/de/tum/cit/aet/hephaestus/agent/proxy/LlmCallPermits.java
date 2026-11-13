package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallDelay;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitState;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Shared admission, fair grants, rate accounting and the limit learned from the provider. The row of a pool is the one
 * thing all servers share: each decision on it holds its row lock, and no one holds a database connection while it waits
 * for a grant or a model. Revocation never releases an active call.
 */
@Component
@ConditionalOnAgentRuntime
@WorkspaceAgnostic("Instance-owned endpoint capacity, shared across all servers and workspaces.")
public class LlmCallPermits {
    static final int MAINTENANCE_THREADS = 2;
    private static final Logger log = LoggerFactory.getLogger(LlmCallPermits.class);
    static final Duration POLL = Duration.ofMillis(250);
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(5);
    private static final Duration RECOVERY_POLL = Duration.ofSeconds(2);
    /** A call without a work deadline: its exchange and the one retry of it each get their own ceiling. */
    private static final Duration UNDATED_CALL = LlmProxyService.HEPH_CALL_CEILING.multipliedBy(2);

    private final LlmCallPoolRepository pools;
    private final LlmCallPermitRepository rows;
    private final TransactionTemplate transactions;
    private final LlmCallPoolProperties properties;
    private final Duration permitAging;
    private final String ownerId;
    private final LlmPoolMeters meters;
    private final Map<UUID, Permit> held = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingRelease = new ConcurrentHashMap<>();
    private final Map<UUID, Waiter> waiting = new ConcurrentHashMap<>();
    private final Map<Long, Admissions> admissions = new ConcurrentHashMap<>();
    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable ScheduledExecutorService fencing;
    private final LlmModelResolver models;
    private final @Nullable LlmPoolNotifications notifications;

    @Autowired
    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            AgentCapacityProperties capacity,
            MeterRegistry registry,
            LlmModelResolver models,
            LlmPoolNotifications notifications) {
        this(
                pools,
                rows,
                transactions,
                properties,
                capacity.permitAging(),
                UUID.randomUUID().toString(),
                registry,
                models,
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
        this(pools, rows, transactions, properties, permitAging, ownerId, registry, models, null);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            Duration permitAging,
            String ownerId,
            MeterRegistry registry,
            LlmModelResolver models,
            @Nullable LlmPoolNotifications notifications) {
        this.notifications = notifications;
        this.pools = pools;
        this.models = models;
        this.rows = rows;
        this.transactions = transactions;
        this.properties = properties;
        this.permitAging = permitAging;
        this.ownerId = ownerId;
        this.meters = new LlmPoolMeters(registry);
    }

    /** The exact attempt fence, including its tenant, for a pending call. */
    public record Caller(
            @Nullable Long workspaceId, UUID sourceId, int attempt, LlmModelResolver.@Nullable ConnectionRef model) {}

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
     * @param band the call's grant priority: its run's band. Product feedback, the only call without a workspace, is
     *     {@link AgentRunBand#BACKGROUND}
     * @param deadline when the caller stops waiting, and when the call must have ended; {@code null} for a call that
     *     its own ceiling ends
     */
    public @Nullable Permit acquire(
            long poolId, AgentRunBand band, Caller caller, BooleanSupplier ended, @Nullable Instant deadline) {
        return acquire(poolId, band, caller, ended, deadline, true);
    }

    /** One fair admission round for a draft; a busy pool leaves the outbox free to execute other actions. */
    public @Nullable Permit tryAcquireBackground(long poolId, Caller caller, BooleanSupplier ended, Instant deadline) {
        return acquire(poolId, AgentRunBand.BACKGROUND, caller, ended, deadline, false);
    }

    private @Nullable Permit acquire(
            long poolId,
            AgentRunBand band,
            Caller caller,
            BooleanSupplier ended,
            @Nullable Instant deadline,
            boolean wait) {
        if (caller.workspaceId() == null && band != AgentRunBand.BACKGROUND)
            throw new IllegalArgumentException(
                    "A call without a workspace is product feedback, in the background band");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model calls must acquire capacity outside a database transaction");
        if (ended.getAsBoolean() || expired(deadline)) return null;
        UUID id = UUID.randomUUID();
        String poolSlug = Objects.requireNonNull(transactions.execute(status -> {
            var admission = pools.lockAdmission(poolId)
                    .orElseThrow(() -> new IllegalStateException("Configured call pool is unavailable"));
            if (!samePool(poolId, caller))
                throw new IllegalStateException("The configured model call pool changed before admission");
            rows.enqueue(new LlmCallPermitRepository.Request(
                    id, poolId, ownerId, band, caller.workspaceId(), caller.sourceId(), caller.attempt()));
            return admission.getSlug();
        }));
        Waiter waiter = new Waiter(poolId, poolSlug, band, deadline);
        waiting.put(id, waiter);
        long began = System.nanoTime();
        boolean handedOff = false;
        Permit admitted = null;
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
                admitted = wait ? waiter.awaitGrant() : waiter.granted.getNow(null);
                if (admitted != null) {
                    meters.pool(admitted.pool).granted(band);
                    meters.pool(admitted.pool).waited(band, "capacity", System.nanoTime() - began);
                    Permit result = handOff(admitted, ended, deadline);
                    handedOff = result != null;
                    return result;
                }
                if (!wait || Thread.currentThread().isInterrupted()) return null;
            }
            return null;
        } finally {
            waiting.remove(id);
            // A round may have delivered the grant after the last look.
            if (admitted == null && !waiter.granted.isCompletedExceptionally()) admitted = waiter.granted.getNow(null);
            if (!handedOff && admitted != null) admitted.close();
            if (!handedOff && admitted == null) {
                meters.pool(poolSlug).abandoned(band, "capacity");
                meters.pool(poolSlug).waited(band, "capacity", System.nanoTime() - began);
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

    /** The model's pool. Every model has one, so only a model that is gone has none. */
    public long poolFor(LlmModelResolver.ConnectionRef ref) {
        Long pool = models.resolveCallPool(ref);
        if (pool == null) throw new IllegalStateException("The configured model is no longer available");
        return pool;
    }

    private boolean samePool(long poolId, Caller caller) {
        var model = caller.model();
        return model == null || poolFor(model) == poolId;
    }

    /** Whether a review's call waits for the pool: a point-in-time observation that creates no request or grant. */
    public boolean hasReviewCallsWaiting(long poolId) {
        return rows.hasReviewCallsWaiting(poolId, properties.permitLease().toSeconds())
                .orElseThrow(() -> new IllegalStateException("Configured call pool is unavailable"));
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
                        && rows.grantRound(poolId, room(poolId, pool), tokenBound(pool), permitAging.toSeconds()) > 0;
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

    /**
     * What one round may grant. An interactive call may use the reserve, also when the calls in flight fill the limit. A
     * call that is not interactive may not: calls that a lowered limit left in flight must not cost a person the
     * reserve.
     */
    private LlmCallPermitRepository.Room room(long poolId, LlmCallPoolRepository.Admission pool) {
        int limit = limit(pool);
        int reserve = LlmCallLimit.reserve(limit);
        long active = rows.countActive(poolId);
        long notInteractive = rows.countNotInteractive(poolId);
        var used = rows.rates(poolId);
        long bound = tokenBound(pool);
        Integer perMinute = pool.getRequestsPerMinute();
        Long requests = perMinute == null ? null : perMinute.longValue();
        Long tokens = pool.getTokensPerMinute();
        Long input = pool.getInputTokensPerMinute();
        Long output = pool.getOutputTokensPerMinute();
        // An output bucket counts tokens as a model writes them, so a call needs no room in it before it starts.
        long rate = LongStream.of(
                        fit(requests, used.getRequests(), 1),
                        fit(tokens, used.getTokens(), bound),
                        fit(input, used.getInputTokens(), bound),
                        fit(output, used.getOutputTokens(), 0))
                .min()
                .orElseThrow();
        long notInteractiveRate = LongStream.of(
                        fitShare(requests, used.getNotInteractiveRequests(), pool, 1),
                        fitShare(tokens, used.getNotInteractiveTokens(), pool, bound),
                        fitShare(input, used.getNotInteractiveInputTokens(), pool, bound),
                        fitShare(output, used.getNotInteractiveOutputTokens(), pool, 0))
                .min()
                .orElseThrow();
        return new LlmCallPermitRepository.Room(
                Math.max(0, Math.max(limit - active, reserve - (active - notInteractive))),
                Math.max(0, limit - active),
                Math.max(0, limit - reserve - notInteractive),
                Math.max(0, 1 - rows.countActiveProductFeedback(poolId)),
                rate,
                notInteractiveRate);
    }

    private static int limit(LlmCallPoolRepository.Admission pool) {
        return LlmCallLimit.effective(pool.getLearnedLimit(), pool.getMaxConcurrentCalls());
    }

    /**
     * How many calls fit in one rate budget, each of which debits {@code perCall} at its grant: {@link Long#MAX_VALUE}
     * for no budget. A budget that debits nothing at a grant fits every call while it has room.
     */
    private static long fit(@Nullable Long budget, long used, long perCall) {
        if (budget == null) return Long.MAX_VALUE;
        if (perCall == 0) return used <= budget ? Long.MAX_VALUE : 0;
        long slack = budget - perCall - used;
        return slack < 0 ? 0 : slack / perCall + 1;
    }

    private static long fitShare(@Nullable Long budget, long used, LlmCallPoolRepository.Admission pool, long perCall) {
        return budget == null ? Long.MAX_VALUE : fit(notInteractiveShare(budget, pool, perCall), used, perCall);
    }

    /** Calls that are not interactive get the rate's share that the limit gives them, and always at least one call. */
    private static long notInteractiveShare(long budget, LlmCallPoolRepository.Admission pool, long perCall) {
        int limit = limit(pool);
        long share = (long) limit - LlmCallLimit.reserve(limit);
        long proportional = (budget / limit) * share + (budget % limit) * share / limit;
        return Math.max(perCall, proportional);
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
                if (pool.getCooldownMillis() > 0) return false;
                var room = room(permit.poolId, pool);
                if (room.rate() < 1 || (permit.band != AgentRunBand.INTERACTIVE && room.notInteractiveRate() < 1))
                    return false;
                return rows.debitRetry(
                                permit.id,
                                ownerId,
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

    /**
     * The provider's answer that it serves too many calls: 429, 529, or a 503. Only one of the calls that started under
     * the same limit lowers it, since the others say nothing new, and all extend the pause.
     *
     * <p>Another tenant's calls may not depend on a workspace's own credential, so in a pool that models share, such a
     * credential changes nothing and {@code null} tells the caller that its calls did not slow down.
     *
     * @return how long the provider asked to be left alone, uncapped; {@code null} when the answer changed nothing
     */
    public @Nullable Duration throttled(Permit permit, int status, HttpHeaders headers, boolean instanceCredential) {
        Duration asked = RetryAfter.parse(headers, Instant.now());
        Duration pause = asked == null ? DEFAULT_COOLDOWN : asked;
        Duration capped = pause.compareTo(properties.maxCooldown()) > 0 ? properties.maxCooldown() : pause;
        long millis = Math.max(1, capped.toMillis());
        boolean applied = Boolean.TRUE.equals(transactions.execute(state -> {
            var pool = pools.lockAdmission(permit.poolId).orElse(null);
            if (pool == null || (!instanceCredential && !pool.getDedicated())) return false;
            if (Objects.equals(pool.getThrottleMark(), permit.throttleMark)) {
                pools.throttled(
                        permit.poolId,
                        new LlmCallLimit(limit(pool), pool.getLimitCredit())
                                .afterThrottle()
                                .learned());
                meters.pool(permit.pool).limitDecreased("refusal");
            }
            pools.coolDown(permit.poolId, millis);
            return true;
        }));
        meters.pool(permit.pool).throttled(status);
        if (!applied) return null;
        meters.pool(permit.pool).coolingFor(millis);
        return pause;
    }

    /**
     * The head of a served response. When the provider's own headers say that a bucket is empty, every caller pauses
     * briefly. The limit stays: nothing was refused. This runs on the transport's event loop, so the row is written beside
     * it, and the tenant rule of a refusal applies.
     */
    public void served(Permit permit, HttpHeaders headers, boolean instanceCredential) {
        var reached = RateLimitHeaders.reached(headers, Instant.now());
        if (reached == null) return;
        long millis =
                Math.min(reached.pause().toMillis(), properties.maxCooldown().toMillis());
        var unused = Thread.ofVirtual().name("llm-bucket-reached").start(() -> {
            try {
                boolean applied = Boolean.TRUE.equals(transactions.execute(status -> {
                    var pool = pools.lockAdmission(permit.poolId).orElse(null);
                    if (pool == null || (!instanceCredential && !pool.getDedicated())) return false;
                    pools.coolDown(permit.poolId, millis);
                    return true;
                }));
                if (!applied) return;
                meters.pool(permit.pool).bucketReached(reached.bucket());
                meters.pool(permit.pool).coolingFor(millis);
            } catch (RuntimeException failed) {
                log.warn(
                        "Provider bucket pause not recorded: pool={}, error={}",
                        permit.pool,
                        failed.getClass().getSimpleName());
            }
        });
    }

    /** OpenAI and Anthropic answer 429 with these codes when an account has no quota left, which no wait mends. */
    private static final List<String> SPENT_ACCOUNT = List.of("insufficient_quota", "enforced_spend_limit_reached");

    /** Whether a refusal's body says that the account has no quota left, which is no answer about capacity. */
    public static boolean spentAccount(byte @Nullable [] body) {
        if (body == null) return false;
        String answer = new String(body, StandardCharsets.UTF_8);
        return SPENT_ACCOUNT.stream().anyMatch(answer::contains);
    }

    /** Whether a provider's status says it serves too many calls. */
    public static boolean throttles(int status) {
        return status == 429 || status == 503 || status == 529;
    }

    /**
     * A stream's first content event. The caller is about to relay it to someone who waits for it, so the row is written beside
     * it. The same tenant rule as for a refusal applies. A call under an older limit says nothing about this one.
     */
    public void firstToken(Permit permit, long nanos, boolean instanceCredential) {
        int millis = Math.toIntExact(Math.min(TimeUnit.NANOSECONDS.toMillis(nanos), Integer.MAX_VALUE));
        permit.firstTokenMillis = millis;
        meters.pool(permit.pool).firstToken(permit.band, millis);
        var unused = Thread.ofVirtual().name("llm-first-token").start(() -> {
            try {
                transactions.executeWithoutResult(status -> learnFromFirstToken(permit, millis, instanceCredential));
            } catch (RuntimeException failed) {
                log.warn(
                        "Model call first token not recorded: pool={}, error={}",
                        permit.pool,
                        failed.getClass().getSimpleName());
            }
        });
    }

    private void learnFromFirstToken(Permit permit, int millis, boolean instanceCredential) {
        var pool = pools.lockAdmission(permit.poolId).orElse(null);
        if (pool == null || !Objects.equals(pool.getThrottleMark(), permit.throttleMark)) return;
        if (!instanceCredential && !pool.getDedicated()) return;
        int limit = limit(pool);
        var known =
                new LlmCallDelay(pool.getBaselineFirstTokenMs(), pool.getWindowFirstTokenMs(), pool.getWindowSamples());
        var step = known.afterFirstToken(millis, limit);
        var delay = step.delay();
        pools.firstTokens(permit.poolId, delay.baselineMillis(), delay.windowMillis(), delay.samples());
        if (step.keep() < 1) {
            pools.throttled(
                    permit.poolId,
                    new LlmCallLimit(limit, pool.getLimitCredit())
                            .afterDelay(step.keep())
                            .learned());
            meters.pool(permit.pool).limitDecreased("delay");
        } else if (known.baselineMillis() != null
                && delay.baselineMillis() != null
                && delay.baselineMillis() > known.baselineMillis())
            meters.pool(permit.pool).baselineReset();
    }

    /**
     * A served stream that ended without a content token. A provider can fail this way with no error: it sends its head,
     * a comment or a role, and then nothing, or it ends the stream. Such a call never raises the limit. It lowers the
     * limit like a refusal when the provider stopped answering: it ended the stream, or sent no content for the proxy's
     * silence bound, {@code hephaestus.mentor.turn.model-idle-timeout}. Without that, it lowers the limit when the call
     * waited past what the first-token baseline allows. A caller that ended the call sooner says nothing about the
     * provider.
     */
    public void unanswered(Permit permit, boolean stoppedAnswering, long nanos, boolean instanceCredential) {
        permit.unanswered = true;
        int millis = Math.toIntExact(Math.min(TimeUnit.NANOSECONDS.toMillis(nanos), Integer.MAX_VALUE));
        try {
            transactions.executeWithoutResult(status -> {
                var pool = pools.lockAdmission(permit.poolId).orElse(null);
                if (pool == null || !Objects.equals(pool.getThrottleMark(), permit.throttleMark)) return;
                if (!instanceCredential && !pool.getDedicated()) return;
                if (!stoppedAnswering && !new LlmCallDelay(pool.getBaselineFirstTokenMs(), null, 0).queued(millis))
                    return;
                pools.throttled(
                        permit.poolId,
                        new LlmCallLimit(limit(pool), pool.getLimitCredit())
                                .afterThrottle()
                                .learned());
                meters.pool(permit.pool).limitDecreased("unanswered");
            });
        } catch (RuntimeException failed) {
            log.warn(
                    "Unanswered model call not recorded: pool={}, error={}",
                    permit.pool,
                    failed.getClass().getSimpleName());
        }
    }

    /** Raises the limit for a served call that started under it and found it binding; the caller is in a transaction. */
    private void learn(Permit permit) {
        if (permit.unanswered) return;
        var pool = pools.lockAdmission(permit.poolId).orElse(null);
        if (pool == null || !Objects.equals(pool.getThrottleMark(), permit.throttleMark)) return;
        if (!permit.instanceCredential && !pool.getDedicated()) return;
        Integer maximum = pool.getMaxConcurrentCalls();
        if (maximum != null && pool.getLearnedLimit() >= maximum) return;
        // A call that did not stream shows no queue, so it cannot raise a limit that first tokens watch.
        var delay = new LlmCallDelay(pool.getBaselineFirstTokenMs(), null, 0);
        Integer waited = permit.firstTokenMillis;
        if (waited == null ? delay.baselineMillis() != null : delay.queued(waited)) return;
        int limit = limit(pool);
        if (rows.countActive(permit.poolId) < limit
                && rows.countNotInteractive(permit.poolId) < limit - LlmCallLimit.reserve(limit)) return;
        var next = new LlmCallLimit(pool.getLearnedLimit(), pool.getLimitCredit()).afterServedCall(pool.getProbing());
        pools.learned(permit.poolId, next.learned(), next.credit());
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
        private final long poolId;
        private final String pool;
        private final AgentRunBand band;
        private final Sinks.Empty<Void> revoked = Sinks.empty();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile long confirmedAt = System.nanoTime();
        private volatile BooleanSupplier ended = () -> false;
        private volatile boolean served;
        private volatile boolean instanceCredential;
        private volatile @Nullable Integer firstTokenMillis;
        private volatile boolean unanswered;
        private final long grantedAt = System.nanoTime();
        private volatile @Nullable String outcome;
        /** When the provider's part ended, before the proxy settles the call's usage. */
        private volatile long endedAt;
        /** The hard end of the call: every exchange ends by then, so a permit past it has nothing left to protect. */
        private final Instant mustEndBy;

        /** The limit's mark when the call started: an answer about an older limit says nothing about this one. */
        private final @Nullable Long throttleMark;

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
                    if (served) learn(this);
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
