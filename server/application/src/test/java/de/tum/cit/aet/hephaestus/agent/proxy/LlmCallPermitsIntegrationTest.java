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
    void shouldRetainRateDebitsAndFenceTheExpiredOwnerAfterReclaimingCapacity() {
        long pool = pool(1, 0);
        var expired = admit(workerA, pool, LlmCallClass.EVENT, 1);
        jdbc.update(
                "UPDATE llm_call_permit SET tokens=17,renewed_at=clock_timestamp()-interval '21 seconds' WHERE pool_id=?",
                pool);
        workerA.renew();
        assertThat(expired.isRevoked()).isTrue();
        try (var replacement = admit(workerB, pool, LlmCallClass.EVENT, 2)) {
            var rates = rows.rates(pool);
            assertThat(rates.getRequests()).isEqualTo(2);
            assertThat(rates.getTokens()).isEqualTo(17);
            assertThat(workerA.beforeRetry(expired, () -> false, Instant.now().plusSeconds(1)))
                    .isFalse();
            expired.close();
            assertThat(rows.countActive(pool)).isEqualTo(1);
            assertThat(replacement.isRevoked()).isFalse();
        }
    }

    @Test
    void shouldRejectExpiredRenewalAndRetryBeforeAnotherWorkerPrunes() {
        long pool = pool(1, 0);
        var call = admit(workerA, pool, LlmCallClass.EVENT, 1);
        var row = jdbc.queryForMap("SELECT id,worker_id FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE'", pool);
        jdbc.update(
                "UPDATE llm_call_permit SET renewed_at=clock_timestamp()-interval '21 seconds' WHERE pool_id=?", pool);
        transactions.executeWithoutResult(status -> {
            String owner = Objects.requireNonNull((String) row.get("worker_id"));
            UUID id = Objects.requireNonNull((UUID) row.get("id"));
            assertThat(rows.renew(owner, List.of(id), PROPERTIES.permitLease().toSeconds()))
                    .isZero();
            assertThat(rows.findHeld(
                            owner, List.of(id), PROPERTIES.permitLease().toSeconds()))
                    .isEmpty();
            assertThat(rows.debitRetry(id, owner, 0, PROPERTIES.permitLease().toSeconds()))
                    .isZero();
        });
        call.close();
    }

    @Test
    void shouldRenewQueuedCallsWhileAnotherWaiterHoldsLocalAdmission() throws Exception {
        long pool = pool(1, 0);
        var gatedPools = mock(LlmCallPoolRepository.class);
        var blockNext = new AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var resume = new CompletableFuture<Void>();
        when(gatedPools.lockAdmission(pool)).thenAnswer(invocation -> {
            if (blockNext.compareAndSet(true, false)) {
                entered.complete(null);
                resume.get(10, TimeUnit.SECONDS);
            }
            return pools.lockAdmission(pool);
        });
        var worker = new LlmCallPermits(
                gatedPools,
                rows,
                transactions,
                PROPERTIES,
                "waiting-" + UUID.randomUUID(),
                new SimpleMeterRegistry(),
                mock(LlmModelResolver.class));
        var ended = new AtomicBoolean();
        try (var occupied = admit(workerB, pool, LlmCallClass.EVENT, 1);
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(occupied.isRevoked()).isFalse();
            var first = calls.submit(() -> worker.acquire(
                    pool,
                    LlmCallClass.COMPOSITION,
                    caller(2),
                    ended::get,
                    Instant.now().plusSeconds(15)));
            var second = calls.submit(() -> worker.acquire(
                    pool,
                    LlmCallClass.EVENT,
                    caller(3),
                    ended::get,
                    Instant.now().plusSeconds(15)));
            try {
                await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(2));
                blockNext.set(true);
                entered.get(5, TimeUnit.SECONDS);
                var ids = jdbc.queryForList(
                        "SELECT id FROM llm_call_permit WHERE pool_id=? AND state='WAITING'", UUID.class, pool);
                jdbc.update(
                        "UPDATE llm_call_permit SET renewed_at=clock_timestamp()-interval '1 hour' "
                                + "WHERE pool_id=? AND state='WAITING'",
                        pool);
                worker.renew();
                transactions.executeWithoutResult(status -> {
                    assertThat(pools.lockAdmission(pool)).isPresent();
                    rows.prune(pool, PROPERTIES.permitLease().toSeconds());
                });
                assertThat(jdbc.queryForList(
                                "SELECT id FROM llm_call_permit WHERE pool_id=? AND state='WAITING'", UUID.class, pool))
                        .containsExactlyInAnyOrderElementsOf(ids);
            } finally {
                ended.set(true);
                resume.complete(null);
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
        }
        assertThat(waiting(pool)).isZero();
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
            var first = calls.submit(() -> transactions.execute(status ->
                    workerA.reserve(pool, LlmCallClass.EVENT, caller(1)).granted()));
            var second = calls.submit(() -> transactions.execute(status ->
                    workerB.reserve(pool, LlmCallClass.EVENT, caller(2)).granted()));
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
        Boolean reserved = transactions.execute(
                status -> workerA.reserve(pool, LlmCallClass.EVENT, caller).granted());
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
        Boolean reserved = transactions.execute(
                status -> workerA.reserve(pool, LlmCallClass.REQUEST, caller(1)).granted());
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
        Boolean reserved = transactions.execute(
                status -> workerA.reserve(pool, LlmCallClass.REQUEST, caller).granted());
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

    @Test
    void shouldMakeProgressWhenReviewsCallAnotherPoolBeforeTheirReservedModel() throws Exception {
        long firstPool = pool(1, 0);
        long secondPool = pool(1, 0);
        var firstCaller = caller(1);
        var secondCaller = caller(2);
        Boolean firstReserved = transactions.execute(status ->
                workerA.reserve(firstPool, LlmCallClass.REQUEST, firstCaller).granted());
        Boolean secondReserved = transactions.execute(status ->
                workerB.reserve(secondPool, LlmCallClass.REQUEST, secondCaller).granted());
        assertThat(firstReserved).isTrue();
        assertThat(secondReserved).isTrue();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = calls.submit(() -> {
                try (var permit = workerA.acquire(
                        secondPool,
                        LlmCallClass.REQUEST,
                        firstCaller,
                        () -> false,
                        Instant.now().plusSeconds(2))) {
                    return permit != null;
                }
            });
            var second = calls.submit(() -> {
                try (var permit = workerB.acquire(
                        firstPool,
                        LlmCallClass.REQUEST,
                        secondCaller,
                        () -> false,
                        Instant.now().plusSeconds(2))) {
                    return permit != null;
                }
            });
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .as("both calls must progress without retaining unused reservations in the other endpoint")
                    .containsOnly(true);
        } finally {
            Objects.requireNonNull(workerA.acquire(
                            firstPool,
                            LlmCallClass.REQUEST,
                            firstCaller,
                            () -> false,
                            Instant.now().plusSeconds(2)))
                    .close();
            Objects.requireNonNull(workerB.acquire(
                            secondPool,
                            LlmCallClass.REQUEST,
                            secondCaller,
                            () -> false,
                            Instant.now().plusSeconds(2)))
                    .close();
        }
    }

    @Test
    void shouldGiveAnOlderCompositionATurnAfterAnUnusedReservationIsReleased() {
        long primary = pool(2, 0);
        var shared = pools.findById(primary).orElseThrow();
        shared.setRequestsPerMinute(2);
        shared.setTokensPerMinute(14L);
        shared.setTokensPerCall(7L);
        pools.saveAndFlush(shared);
        long auxiliary = pool(1, 0);
        UUID pending = UUID.randomUUID();
        String queuedWorker = "composition-" + UUID.randomUUID();
        try (var ongoing = admit(workerB, primary, LlmCallClass.EVENT, 2)) {
            transactions.executeWithoutResult(status -> rows.enqueue(new LlmCallPermitRepository.Request(
                    pending, primary, queuedWorker, "COMPOSITION", 2, UUID.randomUUID(), 0, "WAITING")));
            int granted = 0;
            for (int round = 0; round < 6; round++) {
                var caller = caller(1);
                var reservation =
                        transactions.execute(status -> workerA.reserve(primary, LlmCallClass.REQUEST, caller));
                if (!Objects.requireNonNull(reservation).granted()) break;
                granted++;
                assertThat(rows.countActive(primary)).isEqualTo(2);
                Objects.requireNonNull(workerA.acquire(
                                auxiliary,
                                LlmCallClass.REQUEST,
                                caller,
                                () -> false,
                                Instant.now().plusSeconds(2)))
                        .close();
                assertThat(rows.countActive(primary)).isEqualTo(1);
                var rates = rows.rates(primary);
                assertThat(rates.getRequests()).isEqualTo(1);
                assertThat(rates.getTokens()).isEqualTo(7);
                assertThat(rows.next(primary, false)).contains(pending);
            }
            assertThat(ongoing.isRevoked()).isFalse();
            assertThat(granted)
                    .as("an older composition must win before another unused reservation repeats")
                    .isEqualTo(1);
            assertThat(rows.next(primary, false)).contains(pending);
        } finally {
            transactions.executeWithoutResult(status -> rows.abandon(pending, queuedWorker));
        }
    }

    private LlmCallPermits worker(String id) {
        return new LlmCallPermits(
                pools, rows, transactions, PROPERTIES, id, new SimpleMeterRegistry(), mock(LlmModelResolver.class));
    }

    @Test
    void shouldKeepAnActiveCallWhileItsAttemptAcquiresAnotherPool() {
        long primary = pool(1, 0);
        long auxiliary = pool(1, 0);
        var caller = caller(1);
        transactions.executeWithoutResult(status -> workerA.reserve(primary, LlmCallClass.REQUEST, caller));
        try (var active = Objects.requireNonNull(workerA.acquire(
                primary,
                LlmCallClass.REQUEST,
                caller,
                () -> false,
                Instant.now().plusSeconds(2)))) {
            try (var other = Objects.requireNonNull(workerA.acquire(
                    auxiliary,
                    LlmCallClass.REQUEST,
                    caller,
                    () -> false,
                    Instant.now().plusSeconds(2)))) {
                assertThat(rows.countActive(primary)).isEqualTo(1);
                assertThat(rows.countActive(auxiliary)).isEqualTo(1);
                assertThat(active.isRevoked()).isFalse();
                assertThat(other.isRevoked()).isFalse();
            }
            assertThat(rows.countActive(primary)).isEqualTo(1);
        }
    }

    @Test
    void shouldKeepOtherOwnersAndAttemptsUnusedReservationsWhenChangingPools() {
        long primary = pool(1, 0);
        long auxiliary = pool(1, 0);
        var caller = caller(1);
        transactions.executeWithoutResult(status -> workerA.reserve(primary, LlmCallClass.REQUEST, caller));
        var otherAttempt =
                new LlmCallPermits.Caller(caller.workspaceId(), caller.sourceId(), caller.attempt() + 1, null);
        var otherWorkspace =
                new LlmCallPermits.Caller(caller.workspaceId() + 1, caller.sourceId(), caller.attempt(), null);
        var otherSource = new LlmCallPermits.Caller(caller.workspaceId(), UUID.randomUUID(), caller.attempt(), null);
        try {
            for (var fenced : List.of(otherAttempt, otherWorkspace, otherSource)) {
                try (var call = Objects.requireNonNull(workerA.acquire(
                        auxiliary,
                        LlmCallClass.REQUEST,
                        fenced,
                        () -> false,
                        Instant.now().plusSeconds(2)))) {
                    assertThat(call.isRevoked()).isFalse();
                    assertThat(rows.countActive(primary)).isEqualTo(1);
                }
            }
            try (var otherWorker = Objects.requireNonNull(workerB.acquire(
                    auxiliary,
                    LlmCallClass.REQUEST,
                    caller,
                    () -> false,
                    Instant.now().plusSeconds(2)))) {
                assertThat(otherWorker.isRevoked()).isFalse();
                assertThat(rows.countActive(primary)).isEqualTo(1);
            }
        } finally {
            Objects.requireNonNull(workerA.acquire(
                            primary,
                            LlmCallClass.REQUEST,
                            caller,
                            () -> false,
                            Instant.now().plusSeconds(2)))
                    .close();
        }
    }

    @Test
    void shouldRefuseToWaitForCapacityInsideAnOpenTransaction() {
        long pool = pool(1, 0);
        transactions.executeWithoutResult(status -> assertThatThrownBy(() -> workerA.acquire(
                        pool,
                        LlmCallClass.REQUEST,
                        caller(1),
                        () -> false,
                        Instant.now().plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model calls must acquire capacity outside a database transaction"));
        assertThat(rows.countActive(pool)).isZero();
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
