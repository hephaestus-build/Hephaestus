package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.netty.Connection;

class LlmCallPermitsIntegrationTest extends BaseIntegrationTest {
    private static final LlmCallPoolProperties PROPERTIES =
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30));

    @Autowired
    private LlmCallPoolRepository pools;

    @Autowired
    private LlmCallPermitRepository rows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private LlmCallPermits workerA;
    private LlmCallPermits workerB;

    @BeforeEach
    void setUp() {
        workerA = worker("a-" + UUID.randomUUID());
        workerB = worker("b-" + UUID.randomUUID());
    }

    @Test
    void shouldHoldTheReferenceBudgetAndEightTurnReserveAcrossTwoWorkers(TestReporter reporter) throws Exception {
        long pool = pool(32, 8);
        List<LlmCallPermits.Permit> reviews = new ArrayList<>();
        for (int i = 0; i < 24; i++) reviews.add(admit(i % 2 == 0 ? workerA : workerB, pool, LlmCallClass.REQUEST, 1));
        assertThat(probe(workerA, pool, LlmCallClass.EVENT, 1)).isNull();
        List<Long> latencies = new ArrayList<>();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 25; round++) {
                List<Future<LlmCallPermits.Permit>> turns = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                    turns.add(calls.submit(() -> {
                        long start = System.nanoTime();
                        var permit = admit(worker, pool, LlmCallClass.TURN, 2);
                        synchronized (latencies) {
                            latencies.add(System.nanoTime() - start);
                        }
                        return permit;
                    }));
                }
                List<LlmCallPermits.Permit> held = new ArrayList<>();
                for (var turn : turns) held.add(turn.get(10, TimeUnit.SECONDS));
                assertThat(rows.countActive(pool)).isEqualTo(32);
                assertThat(probe(workerB, pool, LlmCallClass.TURN, 2)).isNull();
                held.forEach(LlmCallPermits.Permit::close);
            }
        } finally {
            reviews.forEach(LlmCallPermits.Permit::close);
        }
        latencies.sort(Long::compare);
        assertThat(latencies).hasSize(200);
        reporter.publishEntry(Map.of(
                "observations",
                Integer.toString(latencies.size()),
                "p50Ms",
                Double.toString(latencies.get(99) / 1_000_000.0),
                "p95Ms",
                Double.toString(latencies.get(189) / 1_000_000.0)));
        assertThat(latencies.get(99)).isLessThan(Duration.ofSeconds(1).toNanos());
        assertThat(latencies.get(189)).isLessThan(Duration.ofSeconds(5).toNanos());
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldNeverExceedCapacityWhenTwoWorkersGrantConcurrently() throws Exception {
        long pool = pool(3, 0);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                done.add(calls.submit(() -> {
                    try (var permit = admit(worker, pool, LlmCallClass.EVENT, 1)) {
                        assertThat(permit.isRevoked()).isFalse();
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        Thread.sleep(20);
                        active.decrementAndGet();
                    }
                    return null;
                }));
            }
            for (var call : done) call.get(30, TimeUnit.SECONDS);
        }
        assertThat(peak.get()).isBetween(1, 3);
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldServeAnotherWorkspaceAndCompositionBeforeABusyWorkspaceRepeats() throws Exception {
        long pool = pool(1, 0);
        var busy = admit(workerA, pool, LlmCallClass.EVENT, 1);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var repeat = calls.submit(() -> admit(workerA, pool, LlmCallClass.EVENT, 1));
            var other = calls.submit(() -> admit(workerB, pool, LlmCallClass.EVENT, 2));
            var compose = calls.submit(() -> admit(workerB, pool, LlmCallClass.COMPOSITION, 1));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(3));
            busy.close();
            var second = other.get(5, TimeUnit.SECONDS);
            assertThat(repeat.isDone()).isFalse();
            second.close();
            var composition = compose.get(5, TimeUnit.SECONDS);
            assertThat(repeat.isDone()).isFalse();
            composition.close();
            repeat.get(5, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void shouldRetainARevokedPermitUntilItsCallCloses() {
        long pool = pool(1, 0);
        var call = admit(workerA, pool, LlmCallClass.EVENT, 1);
        jdbc.update("UPDATE llm_call_permit SET renewed_at=clock_timestamp()-interval '1 hour' WHERE pool_id=?", pool);
        workerA.fence(System.nanoTime() + PROPERTIES.selfFenceAfter().toNanos() + 1);
        assertThat(call.isRevoked()).isTrue();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        assertThat(probe(workerB, pool, LlmCallClass.EVENT, 2)).isNull();
        var transport = mock(Connection.class);
        var channel = mock(Channel.class);
        var closed = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        when(transport.channel()).thenReturn(channel);
        when(channel.closeFuture()).thenReturn(closed);
        var transports = new ProxyTransportClosure();
        transports.closing(transport);
        transports.releaseWhenClosed(call::close);
        assertThat(rows.countActive(pool)).isEqualTo(1);
        assertThat(probe(workerB, pool, LlmCallClass.EVENT, 2)).isNull();
        closed.setSuccess();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(rows.countActive(pool)).isZero());
        var replacement = admit(workerB, pool, LlmCallClass.EVENT, 2);
        call.close();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        replacement.close();
    }

    @Test
    void shouldStopAnEndedWaiterWithoutGrantingIt() throws Exception {
        long pool = pool(1, 0);
        var held = admit(workerA, pool, LlmCallClass.EVENT, 1);
        AtomicBoolean ended = new AtomicBoolean();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = calls.submit(() -> workerB.acquire(pool, LlmCallClass.EVENT, caller(2), ended::get, null));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(1));
            ended.set(true);
            held.close();
            assertThat(waiting.get(5, TimeUnit.SECONDS)).isNull();
        }
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldShareRequestAndTokenRatesAcrossCompositionScriptsAndWorkers() {
        long pool = pool(8, 0);
        transactions.executeWithoutResult(status -> {
            var settings = pools.findById(pool).orElseThrow();
            settings.setRequestsPerMinute(3);
            settings.setTokensPerMinute(300L);
            settings.setTokensPerCall(100L);
            pools.save(settings);
        });
        admit(workerA, pool, LlmCallClass.EVENT, 1).close();
        admit(workerB, pool, LlmCallClass.COMPOSITION, 2).close();
        var script = admit(workerB, pool, LlmCallClass.REQUEST, 2);
        script.close();
        assertThat(probe(workerA, pool, LlmCallClass.TURN, 1)).isNull();
        assertThat(rows.rates(pool).getRequests()).isEqualTo(3);
        assertThat(rows.rates(pool).getTokens()).isEqualTo(300);
        jdbc.update(
                "UPDATE llm_call_permit SET closed_at=clock_timestamp()-interval '61 seconds' WHERE pool_id=?", pool);
        admit(workerA, pool, LlmCallClass.TURN, 1).close();
    }

    @Test
    void shouldDebitPhysicalRetriesInTheSameRateAccount() {
        long pool = pool(4, 0);
        var call = admit(workerA, pool, LlmCallClass.COMPOSITION, 1);
        assertThat(workerA.beforeRetry(call, () -> false, Instant.now().plusSeconds(2)))
                .isTrue();
        assertThat(rows.rates(pool).getRequests()).isEqualTo(2);
        call.close();
    }

    @Test
    void shouldKeepAnInteractiveRateShareWhenBackgroundWorkIsBusy() {
        long pool = pool(4, 1);
        transactions.executeWithoutResult(status -> {
            var settings = pools.findById(pool).orElseThrow();
            settings.setRequestsPerMinute(4);
            pools.save(settings);
        });
        for (int i = 0; i < 3; i++) admit(workerA, pool, LlmCallClass.EVENT, 1).close();
        assertThat(probe(workerB, pool, LlmCallClass.EVENT, 2)).isNull();
        admit(workerB, pool, LlmCallClass.TURN, 2).close();
    }

    @Test
    void shouldKeepTheLongerProviderCooldownAcrossWorkers() {
        long pool = pool(4, 0);
        var call = admit(workerA, pool, LlmCallClass.EVENT, 1);
        HttpHeaders longPause = new HttpHeaders();
        longPause.set(HttpHeaders.RETRY_AFTER, "30");
        workerA.coolDown(call, longPause);
        HttpHeaders shortPause = new HttpHeaders();
        shortPause.set(HttpHeaders.RETRY_AFTER, "1");
        workerA.coolDown(call, shortPause);
        call.close();
        assertThat(probe(workerB, pool, LlmCallClass.TURN, 2)).isNull();
        var deadline = Objects.requireNonNull(pools.findById(pool).orElseThrow().getNoCallBefore());
        assertThat(deadline).isAfter(Instant.now().plusSeconds(25));
    }

    @Test
    void shouldReserveTheLastBackgroundSlotForOnlyOneClaimAcrossWorkers() throws Exception {
        long pool = pool(2, 1);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = calls.submit(
                    () -> transactions.execute(status -> workerA.reserve(pool, LlmCallClass.EVENT, caller(1))));
            var second = calls.submit(
                    () -> transactions.execute(status -> workerB.reserve(pool, LlmCallClass.EVENT, caller(2))));
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(rows.countActive(pool)).isEqualTo(1);
        assertThat(waiting(pool)).isEqualTo(1);
    }

    @Test
    void shouldUseOnlyTheReservationForTheExactAttemptAndWorker() {
        long pool = pool(1, 0);
        var caller = caller(1);
        Boolean reserved = transactions.execute(status -> workerA.reserve(pool, LlmCallClass.EVENT, caller));
        assertThat(reserved).isTrue();
        assertThat(probe(workerB, pool, LlmCallClass.EVENT, 1)).isNull();
        var call = Objects.requireNonNull(workerA.acquire(
                pool, LlmCallClass.EVENT, caller, () -> false, Instant.now().plusSeconds(2)));
        assertThat(rows.countActive(pool)).isEqualTo(1);
        call.close();
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldRecoverAnUnusedReservationFromADeadWorkerBeforeGrantingItsReplacement() {
        long pool = pool(2, 1);
        Boolean reserved = transactions.execute(status -> workerA.reserve(pool, LlmCallClass.REQUEST, caller(1)));
        assertThat(reserved).isTrue();
        jdbc.update(
                "UPDATE llm_call_permit SET renewed_at=clock_timestamp()-interval '1 minute' WHERE pool_id=?", pool);
        var replacement = workerB.acquire(
                pool,
                LlmCallClass.REQUEST,
                caller(2),
                () -> false,
                Instant.now().plusSeconds(5));
        assertThat(replacement)
                .as("a dead owner's unused reservation must not retire background capacity")
                .isNotNull();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        Objects.requireNonNull(replacement).close();
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldCloseAFreshGrantWhenTheFinalOwnershipReadThrows() {
        long pool = pool(1, 0);
        AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(() -> workerA.acquire(
                        pool,
                        LlmCallClass.REQUEST,
                        caller(1),
                        () -> {
                            if (checks.incrementAndGet() == 3) throw new IllegalStateException("ownership read failed");
                            return false;
                        },
                        Instant.now().plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ownership read failed");
        assertThat(rows.countActive(pool)).isZero();
        workerA.renew();
        admit(workerB, pool, LlmCallClass.REQUEST, 2).close();
    }

    @Test
    void shouldCloseAnActivatedReservationWhenTheFinalOwnershipReadThrows() {
        long pool = pool(1, 0);
        var caller = caller(1);
        Boolean reserved = transactions.execute(status -> workerA.reserve(pool, LlmCallClass.REQUEST, caller));
        assertThat(reserved).isTrue();
        AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(() -> workerA.acquire(
                        pool,
                        LlmCallClass.REQUEST,
                        caller,
                        () -> {
                            if (checks.incrementAndGet() == 2) throw new IllegalStateException("ownership read failed");
                            return false;
                        },
                        Instant.now().plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ownership read failed");
        assertThat(rows.countActive(pool)).isZero();
        workerA.renew();
        admit(workerB, pool, LlmCallClass.REQUEST, 2).close();
    }

    @Test
    void shouldKeepTurnHistoryOutOfBackgroundWorkspaceFairness() throws Exception {
        long pool = pool(3, 1);
        var busy = admit(workerB, pool, LlmCallClass.EVENT, 2);
        var otherBusy = admit(workerB, pool, LlmCallClass.EVENT, 2);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new CompletableFuture<String>();
            var composition = calls.submit(() -> {
                var permit = admit(workerA, pool, LlmCallClass.COMPOSITION, 1);
                first.complete("composition");
                return permit;
            });
            var repeat = calls.submit(() -> {
                var permit = admit(workerB, pool, LlmCallClass.EVENT, 2);
                first.complete("repeat");
                return permit;
            });
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(2));
            for (int i = 0; i < 3; i++)
                admit(workerA, pool, LlmCallClass.TURN, 1).close();
            otherBusy.close();
            try {
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("composition");
                var served = composition.get(5, TimeUnit.SECONDS);
                assertThat(repeat.isDone()).isFalse();
                served.close();
            } finally {
                busy.close();
                otherBusy.close();
                repeat.get(10, TimeUnit.SECONDS).close();
                composition.get(10, TimeUnit.SECONDS).close();
            }
        }
    }

    private LlmCallPermits worker(String id) {
        return new LlmCallPermits(
                pools, rows, transactions, PROPERTIES, id, new SimpleMeterRegistry(), mock(LlmModelResolver.class));
    }

    private long pool(int maximum, int reserve) {
        var pool = new LlmCallPool();
        pool.setSlug("pool-" + UUID.randomUUID());
        pool.setDisplayName("Endpoint");
        pool.setMaxConcurrentCalls(maximum);
        pool.setInteractiveReserve(reserve);
        return pools.save(pool).getId();
    }

    private static LlmCallPermits.Caller caller(long workspace) {
        return new LlmCallPermits.Caller(workspace, UUID.randomUUID(), 0, null);
    }

    private static LlmCallPermits.Permit admit(LlmCallPermits worker, long pool, LlmCallClass kind, long workspace) {
        return Objects.requireNonNull(worker.acquire(
                pool, kind, caller(workspace), () -> false, Instant.now().plusSeconds(10)));
    }

    private static LlmCallPermits.@Nullable Permit probe(
            LlmCallPermits worker, long pool, LlmCallClass kind, long workspace) {
        return worker.acquire(
                pool, kind, caller(workspace), () -> false, Instant.now().plusMillis(25));
    }

    private long waiting(long pool) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state IN ('WAITING','CLAIM_WAITING')",
                Long.class,
                pool));
    }
}
