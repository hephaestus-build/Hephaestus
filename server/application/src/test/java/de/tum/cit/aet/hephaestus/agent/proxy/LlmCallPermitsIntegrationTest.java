package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class LlmCallPermitsIntegrationTest extends BaseIntegrationTest {

    private static final LlmCallPoolProperties PROPERTIES =
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30));

    @Autowired
    private LlmCallPoolRepository pools;

    @Autowired
    private LlmCallPermitRepository permitRows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private LlmCallPermits workerA;
    private LlmCallPermits workerB;

    @BeforeEach
    void setUp() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        workerA = worker("pool-test-a-" + run);
        workerB = worker("pool-test-b-" + run);
    }

    @Test
    void shouldAdmitNoMoreCallsThanThePoolAllowsWhenTwoWorkersShareIt() {
        long pool = pool(2, 0);

        var first = admitted(workerA, pool, LlmCallClass.EVENT);
        var second = admitted(workerB, pool, LlmCallClass.REQUEST);

        assertThat(tryOnce(workerA, pool, LlmCallClass.REQUEST)).isNull();
        assertThat(heldIn(pool)).isEqualTo(2);

        first.close();
        var third = admitted(workerA, pool, LlmCallClass.REQUEST);
        assertThat(heldIn(pool)).isEqualTo(2);
        second.close();
        third.close();
        assertThat(heldIn(pool)).isZero();
    }

    @Test
    void shouldKeepTheReserveForTurnsWhenReviewsFillThePool() {
        long pool = pool(2, 1);

        var review = admitted(workerA, pool, LlmCallClass.REQUEST);
        assertThat(tryOnce(workerB, pool, LlmCallClass.EVENT)).isNull();
        var turn = admitted(workerB, pool, LlmCallClass.TURN);

        assertThat(tryOnce(workerA, pool, LlmCallClass.TURN)).isNull();
        review.close();
        turn.close();
    }

    @Test
    void shouldAdmitNoReviewWhenTheReserveIsTheWholePool() {
        long pool = pool(1, 1);

        assertThat(tryOnce(workerA, pool, LlmCallClass.REQUEST)).isNull();
        admitted(workerA, pool, LlmCallClass.TURN).close();
    }

    @Test
    void shouldHoldEveryCallOfThePoolWhenItsProviderAskedForAPause() {
        long pool = pool(null, 0);
        var throttled = admitted(workerA, pool, LlmCallClass.EVENT);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");

        workerA.coolDown(throttled, headers);
        throttled.close();

        assertThat(tryOnce(workerB, pool, LlmCallClass.TURN)).isNull();
        assertThat(workerB.acquire(
                        pool, LlmCallClass.TURN, () -> false, Instant.now().plusSeconds(10)))
                .isNotNull();
    }

    @Test
    void shouldNeverAdmitMoreCallsThanTheLimitWhenManyCallsContend() throws Exception {
        long pool = pool(3, 0);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int call = 0; call < 16; call++) {
                LlmCallPermits worker = call % 2 == 0 ? workerA : workerB;
                done.add(calls.submit(() -> {
                    for (int round = 0; round < 3; round++) {
                        var permit = admitted(worker, pool, LlmCallClass.EVENT);
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        admitted.incrementAndGet();
                        Thread.sleep(20);
                        inFlight.decrementAndGet();
                        permit.close();
                    }
                    return null;
                }));
            }
            for (Future<?> call : done) call.get(60, TimeUnit.SECONDS);
        }

        assertThat(admitted).hasValue(48);
        assertThat(peak.get()).isBetween(1, 3);
        assertThat(heldIn(pool)).isZero();
    }

    @Test
    void shouldAdmitAWaitingCallWhenACallOfItsWorkerEnds() throws Exception {
        long pool = pool(1, 0);
        var first = admitted(workerA, pool, LlmCallClass.EVENT);
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LlmCallPermits.@Nullable Permit> waiting = calls.submit(() -> workerA.acquire(
                    pool, LlmCallClass.EVENT, () -> false, Instant.now().plusSeconds(30)));
            first.close();

            var second = waiting.get(30, TimeUnit.SECONDS);
            assertThat(second).isNotNull();
            Objects.requireNonNull(second).close();
        }
    }

    @Test
    void shouldStopWaitingWhenTheCallEndsWhileItWaits() throws Exception {
        long pool = pool(1, 0);
        var held = admitted(workerA, pool, LlmCallClass.EVENT);
        AtomicBoolean ended = new AtomicBoolean();
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LlmCallPermits.@Nullable Permit> waiting =
                    calls.submit(() -> workerB.acquire(pool, LlmCallClass.EVENT, ended::get, null));
            ended.set(true);

            assertThat(waiting.get(30, TimeUnit.SECONDS)).isNull();
        }
        held.close();
    }

    @Test
    void shouldLetACallRunOnWhenItsPoolIsDeleted() {
        long pool = pool(1, 0);
        var permit = admitted(workerA, pool, LlmCallClass.EVENT);
        transactions.executeWithoutResult(status -> pools.deleteById(pool));

        workerA.renew();

        assertThat(permit.isRevoked()).isFalse();
        permit.close();
    }

    @Test
    void shouldKeepTheLongerPauseWhenAShorterOneFollows() {
        long pool = pool(null, 0);
        var permit = admitted(workerA, pool, LlmCallClass.EVENT);
        HttpHeaders longPause = new HttpHeaders();
        longPause.set(HttpHeaders.RETRY_AFTER, "20");
        HttpHeaders shortPause = new HttpHeaders();
        shortPause.set(RetryAfter.RETRY_AFTER_MS, "100");

        workerA.coolDown(permit, longPause);
        workerB.coolDown(permit, shortPause);

        assertThat(cooldownSeconds(pool)).isGreaterThan(15);
    }

    @Test
    void shouldCapThePauseWhenTheProviderAsksForMore() {
        long pool = pool(null, 0);
        var permit = admitted(workerA, pool, LlmCallClass.EVENT);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "3600");

        workerA.coolDown(permit, headers);

        assertThat(cooldownSeconds(pool)).isBetween(25.0, 30.0);
    }

    @Test
    void shouldFreeAPermitWhenItsWorkerStoppedRenewingIt() {
        long pool = pool(1, 0);
        admitted(workerB, pool, LlmCallClass.EVENT);
        assertThat(tryOnce(workerA, pool, LlmCallClass.EVENT)).isNull();

        jdbc.update(
                "UPDATE llm_call_permit SET renewed_at = now() - make_interval(secs => ?) WHERE pool_id = ?",
                PROPERTIES.reclaimAfter().toSeconds() + 1,
                pool);

        admitted(workerA, pool, LlmCallClass.EVENT).close();
    }

    @Test
    void shouldKeepAPermitWhenItsWorkerRenewsIt() {
        long pool = pool(1, 0);
        var permit = admitted(workerA, pool, LlmCallClass.EVENT);
        jdbc.update(
                "UPDATE llm_call_permit SET renewed_at = now() - make_interval(secs => ?) WHERE pool_id = ?",
                PROPERTIES.reclaimAfter().toSeconds() + 1,
                pool);

        workerA.renew();

        assertThat(tryOnce(workerB, pool, LlmCallClass.EVENT)).isNull();
        assertThat(permit.isRevoked()).isFalse();
        permit.close();
    }

    @Test
    void shouldCancelTheCallWhenTheDatabaseNoLongerHasItsPermit() {
        long pool = pool(1, 0);
        var permit = admitted(workerA, pool, LlmCallClass.EVENT);
        jdbc.update("DELETE FROM llm_call_permit WHERE pool_id = ?", pool);

        workerA.renew();

        assertThat(permit.isRevoked()).isTrue();
        permit.close();
    }

    @Test
    void shouldCancelItsOwnCallsWhenNoRenewalSucceededInTime() {
        long pool = pool(1, 0);
        var permit = admitted(workerA, pool, LlmCallClass.TURN);

        workerA.fence(System.nanoTime());
        assertThat(permit.isRevoked()).isFalse();

        workerA.fence(System.nanoTime() + PROPERTIES.selfFenceAfter().toNanos() + 1);
        assertThat(permit.isRevoked()).isTrue();
        assertThat(permit.revoked().block(Duration.ofSeconds(1))).isNull();
        permit.close();
        assertThat(heldIn(pool)).isZero();
    }

    @Test
    void shouldStopWaitingWhenTheCallsDeadlinePasses() {
        long pool = pool(1, 0);
        var held = admitted(workerA, pool, LlmCallClass.EVENT);

        assertThat(workerB.acquire(
                        pool, LlmCallClass.EVENT, () -> false, Instant.now().plusMillis(300)))
                .isNull();
        held.close();
    }

    @Test
    void shouldAdmitTheCallWhenThePoolWasDeleted() {
        long pool = pool(1, 0);
        transactions.executeWithoutResult(status -> pools.deleteById(pool));

        admitted(workerA, pool, LlmCallClass.EVENT).close();
    }

    private LlmCallPermits worker(String id) {
        return new LlmCallPermits(pools, permitRows, transactions, PROPERTIES, id, new SimpleMeterRegistry());
    }

    private long pool(@Nullable Integer max, int reserve) {
        LlmCallPool pool = new LlmCallPool();
        pool.setSlug("pool-" + UUID.randomUUID().toString().substring(0, 8));
        pool.setDisplayName("Shared GPU");
        pool.setMaxConcurrentCalls(max);
        pool.setInteractiveReserve(reserve);
        return pools.save(pool).getId();
    }

    private static LlmCallPermits.Permit admitted(LlmCallPermits worker, long pool, LlmCallClass callClass) {
        return Objects.requireNonNull(
                worker.acquire(pool, callClass, () -> false, Instant.now().plusSeconds(5)));
    }

    /** One look at the pool: a call that has ended asks once and does not wait. */
    private static LlmCallPermits.@Nullable Permit tryOnce(LlmCallPermits worker, long pool, LlmCallClass callClass) {
        return worker.acquire(pool, callClass, () -> true, null);
    }

    private long heldIn(long pool) {
        return permitRows.countByPoolId(pool);
    }

    private double cooldownSeconds(long pool) {
        Double seconds = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (no_call_before - now())) FROM llm_call_pool WHERE id = ?",
                Double.class,
                pool);
        return Objects.requireNonNull(seconds);
    }
}
