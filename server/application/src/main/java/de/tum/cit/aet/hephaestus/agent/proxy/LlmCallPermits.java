package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallDelay;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Shared admission, fair grants, rate accounting and the limit learned from the provider. The row of a pool is the one
 * thing all workers share: each decision on it holds its row lock, and no one holds a database connection while it waits
 * for a grant or a model. Revocation never releases an active call.
 */
@Component
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Instance-owned endpoint capacity, shared across all workers and workspaces.")
public class LlmCallPermits {
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
    private final String workerId;
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
            WorkerProperties worker,
            MeterRegistry registry,
            LlmModelResolver models,
            LlmPoolNotifications notifications) {
        this(pools, rows, transactions, properties, worker.resolvedWorkerId(), registry, models, notifications);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            String workerId,
            MeterRegistry registry,
            LlmModelResolver models) {
        this(pools, rows, transactions, properties, workerId, registry, models, null);
    }

    LlmCallPermits(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            String workerId,
            MeterRegistry registry,
            LlmModelResolver models,
            @Nullable LlmPoolNotifications notifications) {
        this.notifications = notifications;
        this.pools = pools;
        this.models = models;
        this.rows = rows;
        this.transactions = transactions;
        this.properties = properties;
        this.workerId = workerId;
        this.meters = new LlmPoolMeters(registry);
    }

    /** The exact attempt fence, including its tenant, for a pending call. */
    public record Caller(
            @Nullable Long workspaceId, UUID sourceId, int attempt, LlmModelResolver.@Nullable ConnectionRef model) {}

    // Artifact profiles expose controllers without a migrated database or worker activity.
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
        ScheduledExecutorService started = Executors.newScheduledThreadPool(2, daemons);
        long renewMillis = properties.renewEvery().toMillis();
        var unusedRenewal =
                started.scheduleWithFixedDelay(this::renew, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        // On a thread of its own: a lost database connection can hang the other tasks for minutes, and this one ends
        // the worker's calls then. It must never read the database.
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
     * @param deadline when the caller stops waiting, and when the call must have ended; {@code null} for a call that
     *     its own ceiling ends
     */
    public @Nullable Permit acquire(
            long poolId, LlmCallClass callClass, Caller caller, BooleanSupplier ended, @Nullable Instant deadline) {
        if ((caller.workspaceId() == null) != (callClass == LlmCallClass.PRODUCT_FEEDBACK))
            throw new IllegalArgumentException("Product feedback calls have no workspace; all other calls require one");
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
                    id, poolId, workerId, callClass.name(), caller.workspaceId(), caller.sourceId(), caller.attempt()));
            return admission.getSlug();
        }));
        Waiter waiter = new Waiter(poolId, poolSlug, callClass, deadline);
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
                admitted = waiter.awaitGrant();
                if (admitted != null) {
                    meters.pool(admitted.pool).granted(callClass);
                    meters.pool(admitted.pool).waited(callClass, "capacity", System.nanoTime() - began);
                    Permit result = handOff(admitted, ended, deadline);
                    handedOff = result != null;
                    return result;
                }
                if (Thread.currentThread().isInterrupted()) return null;
            }
            return null;
        } finally {
            waiting.remove(id);
            // A round may have delivered the grant after the last look.
            if (admitted == null && !waiter.granted.isCompletedExceptionally()) admitted = waiter.granted.getNow(null);
            if (!handedOff && admitted != null) admitted.close();
            if (!handedOff && admitted == null) {
                meters.pool(poolSlug).abandoned(callClass, "capacity");
                meters.pool(poolSlug).waited(callClass, "capacity", System.nanoTime() - began);
                try {
                    // No transport can have opened before handoff, including an uncertain grant commit.
                    pendingRelease.put(id, poolId);
                    // In this order: the delete waits for another worker's grant of this request, which the release
                    // then sees and closes. The other way round, the grant would stay open with no one to close it.
                    Boolean freed = transactions.execute(status -> {
                        rows.abandon(id, workerId);
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

    /** A point-in-time endpoint backlog observation; this creates no request or grant. */
    public boolean hasBackgroundWaiters(long poolId) {
        return rows.hasBackgroundWaiters(poolId, properties.permitLease().toSeconds())
                .orElseThrow(() -> new IllegalStateException("Configured call pool is unavailable"));
    }

    /**
     * One admission round: grants whatever the pool has room for, in fair order, whichever worker's requests they are,
     * and hands this worker's grants to its waiters. So one round per worker and pool is enough, however many calls
     * wait. The round runs under the pool's row lock, so it writes no waiter's row: {@link #renew()} keeps them alive
     * four times per lease. A new conversation call queues for its round, as it must not lose time to the review calls
     * that wait beside it.
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
                boolean granted = grantWhileRoom(poolId, pool, true);
                granted |= grantWhileRoom(poolId, pool, false);
                Map<UUID, Boolean> queued = new HashMap<>();
                if (!mine.isEmpty()) {
                    for (var row : rows.findQueued(workerId, mine.keySet()))
                        queued.put(row.getId(), row.getState().equals("ACTIVE"));
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
                // Another worker may have granted it before a refusal paused the pool. The grant waits for the pause.
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

    private boolean grantWhileRoom(long poolId, LlmCallPoolRepository.Admission pool, boolean interactive) {
        boolean granted = false;
        while (true) {
            var head = rows.next(poolId, interactive).orElse(null);
            if (head == null || !room(poolId, pool, LlmCallClass.valueOf(head.getCallClass()))) return granted;
            if (rows.grant(head.getId(), tokenBound(pool)) != 1) return granted;
            granted = true;
        }
    }

    private boolean releaseRow(UUID id, long poolId) {
        if (rows.release(id, workerId) != 1) return false;
        notifyPool(poolId);
        return true;
    }

    private void released(long poolId) {
        // The release has committed and its transport is closed. Grant even when only other workers have waiters.
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

    private boolean room(long poolId, LlmCallPoolRepository.Admission pool, LlmCallClass callClass) {
        if (pool.getCooldownMillis() > 0) return false;
        if (callClass == LlmCallClass.PRODUCT_FEEDBACK && rows.countActiveProductFeedback(poolId) >= 1) return false;
        int limit = limit(pool);
        int reserve = LlmCallLimit.reserve(limit);
        long active = rows.countActive(poolId);
        long background = rows.countBackground(poolId);
        // Background calls that a lowered limit left in flight must not cost a conversation its reserve.
        boolean within = callClass.interactive()
                ? active < limit || active - background < reserve
                : active < limit && background < limit - reserve;
        return within && rateRoom(poolId, pool, callClass);
    }

    private static int limit(LlmCallPoolRepository.Admission pool) {
        return LlmCallLimit.effective(pool.getLearnedLimit(), pool.getMaxConcurrentCalls());
    }

    private boolean rateRoom(long poolId, LlmCallPoolRepository.Admission pool, LlmCallClass callClass) {
        Integer requests = pool.getRequestsPerMinute();
        Long tokens = pool.getTokensPerMinute();
        if (requests == null && tokens == null) return true;
        var used = rows.rates(poolId);
        long bound = tokenBound(pool);
        if (requests != null && used.getRequests() >= requests) return false;
        if (tokens != null && used.getTokens() > tokens - bound) return false;
        if (callClass.interactive()) return true;
        if (requests != null && used.getBackgroundRequests() >= backgroundShare(requests, pool, 1)) return false;
        return tokens == null || used.getBackgroundTokens() <= backgroundShare(tokens, pool, bound) - bound;
    }

    /** Background calls get the rate's share that the limit gives them, and always at least one call. */
    private static long backgroundShare(long budget, LlmCallPoolRepository.Admission pool, long perCall) {
        int limit = limit(pool);
        long background = (long) limit - LlmCallLimit.reserve(limit);
        long proportional = (budget / limit) * background + (budget % limit) * background / limit;
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
            Set<UUID> queued = Set.copyOf(waiting.keySet());
            if (!queued.isEmpty())
                transactions.executeWithoutResult(status ->
                        rows.renew(workerId, queued, properties.permitLease().toSeconds()));
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
        meters.pool(permit.pool).firstToken(permit.callClass, millis);
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
                && rows.countBackground(permit.poolId) < limit - LlmCallLimit.reserve(limit)) return;
        var next = new LlmCallLimit(pool.getLearnedLimit(), pool.getLimitCredit()).afterServedCall(pool.getProbing());
        pools.learned(permit.poolId, next.learned(), next.credit());
    }

    /** Publishes what every pool in use stands at, read from the database, so each worker reports the same. */
    void observeBacklog() {
        try {
            meters.observe(rows.backlog());
        } catch (RuntimeException failed) {
            log.warn(
                    "Model call pool gauges not read: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    /** What one admission round found: whether each of this worker's requests is granted, the limit's mark, and a pause. */
    private record Round(
            Map<UUID, Boolean> queued, @Nullable Long throttleMark, boolean cooling, String slug, long expired) {}

    /** This worker's admission rounds of one pool. */
    private static final class Admissions {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile long lastRound = System.nanoTime() - POLL.toNanos();
        private final AtomicLong changes = new AtomicLong();
        private volatile long observed;
    }

    /** One queued call of this worker, until a round hands it its grant. */
    private static final class Waiter {
        private final long poolId;
        private final String pool;
        private final LlmCallClass callClass;
        private final @Nullable Instant deadline;
        private final CompletableFuture<Permit> granted = new CompletableFuture<>();
        private final Semaphore changed = new Semaphore(0);

        private Waiter(long poolId, String pool, LlmCallClass callClass, @Nullable Instant deadline) {
            this.poolId = poolId;
            this.pool = pool;
            this.callClass = callClass;
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
        private final LlmCallClass callClass;
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

        private Permit(UUID id, Waiter waiter, @Nullable Long throttleMark) {
            this.id = id;
            this.poolId = waiter.poolId;
            this.pool = waiter.pool;
            this.callClass = waiter.callClass;
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
            meters.pool(pool).tokens(callClass, usage);
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
            if (held.remove(id, this)) meters.pool(pool).active(callClass).decrementAndGet();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            String ended = outcome;
            // The provider's own word outranks an answer without content: a refusal or a failure can come with no text.
            if (ended != null)
                meters.pool(pool)
                        .called(
                                callClass,
                                unanswered && !LlmPoolMeters.EXPLICIT_OUTCOMES.contains(ended) ? "unanswered" : ended,
                                endedAt - grantedAt);
            if (held.remove(id, this)) meters.pool(pool).active(callClass).decrementAndGet();
            pendingRelease.put(id, poolId);
            try {
                Boolean freed = transactions.execute(status -> {
                    // First, in the order every grant takes these locks.
                    if (served) learn(this);
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
