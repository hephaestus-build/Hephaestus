package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.sql.Timestamp;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.netty.Connection;
import reactor.test.StepVerifier;

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

    @Autowired
    private DataSourceProperties dataSource;

    private LlmCallPermits workerA;
    private LlmCallPermits workerB;
    private final SimpleMeterRegistry workerAMeters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        workerA = worker("a-" + UUID.randomUUID(), workerAMeters);
        workerB = worker("b-" + UUID.randomUUID(), new SimpleMeterRegistry());
    }

    @AfterEach
    void stopWorkers() {
        workerA.stop();
        workerB.stop();
    }

    @Test
    void shouldHoldTheReferenceBudgetAndEightTurnReserveAcrossTwoWorkers(TestReporter reporter) throws Exception {
        workerA.start();
        workerB.start();
        long pool = pool(32);
        List<LlmCallPermits.Permit> reviews = new ArrayList<>();
        for (int i = 0; i < 24; i++) reviews.add(admit(i % 2 == 0 ? workerA : workerB, pool, AgentRunBand.BATCH, 1));
        assertThat(probe(workerA, pool, AgentRunBand.BATCH, 1)).isNull();
        List<Long> latencies = new ArrayList<>();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 25; round++) {
                List<Future<LlmCallPermits.Permit>> turns = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                    turns.add(calls.submit(() -> {
                        long start = System.nanoTime();
                        var permit = admit(worker, pool, AgentRunBand.INTERACTIVE, 2);
                        synchronized (latencies) {
                            latencies.add(System.nanoTime() - start);
                        }
                        return permit;
                    }));
                }
                List<LlmCallPermits.Permit> held = new ArrayList<>();
                for (var turn : turns) held.add(turn.get(10, TimeUnit.SECONDS));
                assertThat(rows.countActive(pool)).isEqualTo(32);
                assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2)).isNull();
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
        long pool = pool(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                done.add(calls.submit(() -> {
                    try (var permit = admit(worker, pool, AgentRunBand.BATCH, 1)) {
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
    void shouldServeAnotherWorkspaceBeforeABusyWorkspaceRepeatsWhenNeitherHoldsAGrant() throws Exception {
        long pool = pool(1);
        var busy = admit(workerA, pool, AgentRunBand.BATCH, 1);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var repeat = calls.submit(() -> admit(workerA, pool, AgentRunBand.BATCH, 1));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(1));
            var other = calls.submit(() -> admit(workerB, pool, AgentRunBand.BATCH, 2));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(2));
            busy.close();
            var second = other.get(5, TimeUnit.SECONDS);
            assertThat(repeat.isDone()).isFalse();
            second.close();
            repeat.get(5, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void shouldGrantAnInteractiveCallBeforeAnAgedBackgroundCallWhenBothWait() {
        long pool = pool(1);
        var active = admit(workerA, pool, AgentRunBand.BATCH, 1);
        UUID background = waitingCall(pool, AgentRunBand.BACKGROUND, 2, UUID.randomUUID(), Duration.ofHours(1));
        UUID interactive = waitingCall(pool, AgentRunBand.INTERACTIVE, 3, UUID.randomUUID(), Duration.ZERO);

        active.close();

        assertThat(state(interactive)).isEqualTo("ACTIVE");
        assertThat(state(background)).isEqualTo("WAITING");
    }

    @Test
    void shouldGrantABackgroundCallOnceItHasWaitedThePermitAgingWhenBatchCallsKeepArriving() {
        long pool = pool(1);
        var first = admit(workerA, pool, AgentRunBand.BATCH, 1);
        UUID background = waitingCall(pool, AgentRunBand.BACKGROUND, 1, UUID.randomUUID(), Duration.ofSeconds(1));
        UUID batch = waitingCall(pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), Duration.ZERO);

        first.close();

        assertThat(state(batch)).isEqualTo("ACTIVE");
        assertThat(state(background)).isEqualTo("WAITING");
        transactions.executeWithoutResult(status -> rows.release(batch, "remote-" + batch));
        var second = admit(workerA, pool, AgentRunBand.BATCH, 1);
        assertThat(state(background)).isEqualTo("WAITING");
        jdbc.update(
                "UPDATE llm_call_permit SET acquired_at=clock_timestamp()-make_interval(secs => ?) WHERE id=?",
                LlmCallPermitsTestFixtures.PERMIT_AGING.toSeconds() + 1,
                background);
        UUID later = waitingCall(pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), Duration.ZERO);

        second.close();

        assertThat(state(background)).isEqualTo("ACTIVE");
        assertThat(state(later)).isEqualTo("WAITING");
    }

    @Test
    void shouldGrantACompositionBeforeOlderCallsOfAWideReviewWhenBothWaitInOneWorkspace() {
        long pool = pool(3);
        UUID wide = UUID.randomUUID();
        var measuring = admit(workerA, pool, AgentRunBand.BATCH, new LlmCallPermits.Caller(1L, wide, 0, null));
        var alsoMeasuring = admit(workerA, pool, AgentRunBand.BATCH, new LlmCallPermits.Caller(1L, wide, 0, null));
        UUID measurement = waitingCall(pool, AgentRunBand.BATCH, 1, wide, Duration.ofMinutes(1));
        UUID composition = waitingCall(pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), Duration.ZERO);

        measuring.close();

        assertThat(state(composition)).isEqualTo("ACTIVE");
        assertThat(state(measurement)).isEqualTo("WAITING");
        alsoMeasuring.close();
    }

    @Test
    void shouldGrantTheWorkspaceWithFewerGrantsWhenTwoWorkspacesWaitInOneBand() {
        long pool = pool(3);
        var busy = admit(workerA, pool, AgentRunBand.BATCH, 1);
        var alsoBusy = admit(workerA, pool, AgentRunBand.BATCH, 1);
        UUID repeat = waitingCall(pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), Duration.ofMinutes(1));
        UUID other = waitingCall(pool, AgentRunBand.BATCH, 2, UUID.randomUUID(), Duration.ZERO);

        busy.close();

        assertThat(state(other)).isEqualTo("ACTIVE");
        assertThat(state(repeat)).isEqualTo("WAITING");
        alsoBusy.close();
    }

    @Test
    void shouldShareOneRoundsGrantsBetweenWorkspacesWhenSeveralCallsFitAtOnce() {
        long pool = pool(4);
        var held = admit(workerA, pool, AgentRunBand.BATCH, 3);
        List<UUID> first = new ArrayList<>();
        List<UUID> second = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            first.add(waitingCall(pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), Duration.ofMinutes(2)));
        for (int i = 0; i < 3; i++)
            second.add(waitingCall(pool, AgentRunBand.BATCH, 2, UUID.randomUUID(), Duration.ofMinutes(1)));

        held.close();

        assertThat(first.stream().map(this::state)).containsExactly("ACTIVE", "ACTIVE", "WAITING");
        assertThat(second.stream().map(this::state)).containsExactly("ACTIVE", "WAITING", "WAITING");
    }

    @Test
    void shouldCancelAnExpiredLocalLeaseBeforeTheNextFenceTick() {
        long pool = pool(1);
        try (var call = admit(workerA, pool, AgentRunBand.BATCH, 1)) {
            ReflectionTestUtils.setField(
                    call,
                    "confirmedAt",
                    System.nanoTime() - PROPERTIES.selfFenceAfter().toNanos() - 1);
            assertThat(call.isRevoked()).isTrue();
            StepVerifier.create(call.revoked()).expectComplete().verify(Duration.ofSeconds(1));
            assertThat(rows.countActive(pool)).isEqualTo(1);
            assertThat(probe(workerB, pool, AgentRunBand.BATCH, 2)).isNull();
        }
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldCancelAReclaimedGrantOnRenewalWithoutReleasingItsReplacement() {
        long pool = pool(1);
        try (var expired = admit(workerA, pool, AgentRunBand.BATCH, 1)) {
            var active = workerAMeters
                    .get(AgentMetrics.LLM_POOL_PERMITS_ACTIVE)
                    .tags(
                            "pool",
                            pools.findById(pool).orElseThrow().getSlug(),
                            "band",
                            LlmPoolMeters.tag(AgentRunBand.BATCH))
                    .gauge();
            assertThat(active.value()).isEqualTo(1);
            jdbc.update(
                    "UPDATE llm_call_permit SET renewed_at=clock_timestamp()-interval '21 seconds' WHERE pool_id=?",
                    pool);
            try (var replacement = admit(workerB, pool, AgentRunBand.BATCH, 2)) {
                assertThat(expired.isRevoked()).isFalse();
                workerA.renew();
                assertThat(expired.isRevoked()).isTrue();
                StepVerifier.create(expired.revoked()).expectComplete().verify(Duration.ofSeconds(1));
                assertThat(active.value()).isZero();
                assertThat(rows.countActive(pool)).isEqualTo(1);
                assertThat(replacement.isRevoked()).isFalse();
            }
        }
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldStopRenewingACallPastItsHardEndAndReclaimItsCapacity() {
        long pool = pool(1);
        var meters = new SimpleMeterRegistry();
        var shortLease = new LlmCallPermits(
                pools,
                rows,
                transactions,
                new LlmCallPoolProperties(Duration.ofSeconds(2), Duration.ofSeconds(30)),
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                "hard-end-" + UUID.randomUUID(),
                meters,
                mock(LlmModelResolver.class));
        var overdue = Objects.requireNonNull(shortLease.acquire(
                pool, AgentRunBand.BATCH, caller(1), () -> false, Instant.now().plusMillis(200)));
        String slug = pools.findById(pool).orElseThrow().getSlug();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            shortLease.renew();
            assertThat(meters.get(AgentMetrics.LLM_POOL_SELF_FENCE_CANCELLED)
                            .tags("pool", slug, "reason", "past_deadline")
                            .counter()
                            .count())
                    .isEqualTo(1);
        });
        assertThat(overdue.isRevoked()).isTrue();
        Timestamp lastRenewal = renewedAt(pool);
        shortLease.renew();
        assertThat(renewedAt(pool)).isEqualTo(lastRenewal);
        jdbc.update(
                "UPDATE llm_call_permit SET renewed_at=clock_timestamp()-interval '21 seconds' WHERE pool_id=?", pool);
        try (var replacement = admit(workerB, pool, AgentRunBand.BATCH, 2)) {
            assertThat(replacement.isRevoked()).isFalse();
            assertThat(rows.countActive(pool)).isEqualTo(1);
        }
        overdue.close();
    }

    @Test
    void shouldRetainARevokedPermitUntilItsCallCloses() {
        long pool = pool(1);
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);
        workerA.fence(System.nanoTime() + PROPERTIES.selfFenceAfter().toNanos() + 1);
        assertThat(call.isRevoked()).isTrue();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 2)).isNull();
        var transport = mock(Connection.class);
        var channel = mock(Channel.class);
        var closed = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        when(transport.channel()).thenReturn(channel);
        when(channel.closeFuture()).thenReturn(closed);
        var transports = new ProxyTransportClosure();
        transports.closing(transport);
        transports.releaseWhenClosed(call::close);
        assertThat(rows.countActive(pool)).isEqualTo(1);
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 2)).isNull();
        closed.setSuccess();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(rows.countActive(pool)).isZero());
        var replacement = admit(workerB, pool, AgentRunBand.BATCH, 2);
        call.close();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        replacement.close();
    }

    @Test
    void shouldRetainRateDebitsAndFenceTheExpiredOwnerAfterReclaimingCapacity() {
        long pool = pool(1);
        var expired = admit(workerA, pool, AgentRunBand.BATCH, 1);
        jdbc.update(
                "UPDATE llm_call_permit SET tokens=17,renewed_at=clock_timestamp()-interval '21 seconds' WHERE pool_id=?",
                pool);
        workerA.renew();
        assertThat(expired.isRevoked()).isTrue();
        try (var replacement = admit(workerB, pool, AgentRunBand.BATCH, 2)) {
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
        long pool = pool(1);
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);
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
        long pool = pool(1);
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
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                "waiting-" + UUID.randomUUID(),
                new SimpleMeterRegistry(),
                mock(LlmModelResolver.class));
        var ended = new AtomicBoolean();
        try (var occupied = admit(workerB, pool, AgentRunBand.BATCH, 1);
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(occupied.isRevoked()).isFalse();
            var first = calls.submit(() -> worker.acquire(
                    pool,
                    AgentRunBand.BATCH,
                    caller(2),
                    ended::get,
                    Instant.now().plusSeconds(15)));
            var second = calls.submit(() -> worker.acquire(
                    pool,
                    AgentRunBand.BATCH,
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
        long pool = pool(1);
        var held = admit(workerA, pool, AgentRunBand.BATCH, 1);
        AtomicBoolean ended = new AtomicBoolean();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = calls.submit(() -> workerB.acquire(pool, AgentRunBand.BATCH, caller(2), ended::get, null));
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
        long pool = pool(8);
        transactions.executeWithoutResult(status -> {
            var settings = pools.findById(pool).orElseThrow();
            settings.setRequestsPerMinute(3);
            settings.setTokensPerMinute(300L);
            settings.setTokensPerCall(100L);
            pools.save(settings);
        });
        admit(workerA, pool, AgentRunBand.BATCH, 1).close();
        admit(workerB, pool, AgentRunBand.BATCH, 2).close();
        // Background calls get three quarters of the limit, and so of the rates. The rest stays for a conversation.
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 2)).isNull();
        admit(workerB, pool, AgentRunBand.INTERACTIVE, 2).close();
        assertThat(probe(workerA, pool, AgentRunBand.INTERACTIVE, 1)).isNull();
        assertThat(rows.rates(pool).getRequests()).isEqualTo(3);
        assertThat(rows.rates(pool).getTokens()).isEqualTo(300);
        jdbc.update(
                "UPDATE llm_call_permit SET closed_at=clock_timestamp()-interval '61 seconds' WHERE pool_id=?", pool);
        admit(workerA, pool, AgentRunBand.INTERACTIVE, 1).close();
    }

    @Test
    void shouldDebitPhysicalRetriesInTheSameRateAccount() {
        long pool = pool(4);
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);
        assertThat(workerA.beforeRetry(call, () -> false, Instant.now().plusSeconds(2)))
                .isTrue();
        assertThat(rows.rates(pool).getRequests()).isEqualTo(2);
        call.close();
    }

    @Test
    void shouldKeepAnInteractiveRateShareWhenBackgroundWorkIsBusy() {
        long pool = pool(4);
        transactions.executeWithoutResult(status -> {
            var settings = pools.findById(pool).orElseThrow();
            settings.setRequestsPerMinute(4);
            pools.save(settings);
        });
        for (int i = 0; i < 3; i++) admit(workerA, pool, AgentRunBand.BATCH, 1).close();
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 2)).isNull();
        admit(workerB, pool, AgentRunBand.INTERACTIVE, 2).close();
    }

    @Test
    void shouldKeepTheLongerProviderCooldownAcrossWorkers() {
        long pool = pool(4);
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);
        HttpHeaders longPause = new HttpHeaders();
        longPause.set(HttpHeaders.RETRY_AFTER, "30");
        workerA.throttled(call, 429, longPause, true);
        HttpHeaders shortPause = new HttpHeaders();
        shortPause.set(HttpHeaders.RETRY_AFTER, "1");
        workerA.throttled(call, 429, shortPause, true);
        call.close();
        assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2)).isNull();
        var deadline = Objects.requireNonNull(pools.findById(pool).orElseThrow().getNoCallBefore());
        assertThat(deadline).isAfter(Instant.now().plusSeconds(25));
    }

    @Test
    void shouldCloseAFreshGrantWhenTheFinalOwnershipReadThrows() {
        long pool = pool(1);
        AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(() -> workerA.acquire(
                        pool,
                        AgentRunBand.BATCH,
                        caller(1),
                        () -> {
                            if (checks.incrementAndGet() == 2) throw new IllegalStateException("ownership read failed");
                            return false;
                        },
                        Instant.now().plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ownership read failed");
        assertThat(rows.countActive(pool)).isZero();
        workerA.renew();
        admit(workerB, pool, AgentRunBand.BATCH, 2).close();
    }

    @Test
    void shouldKeepTurnHistoryOutOfBackgroundWorkspaceFairness() throws Exception {
        long pool = pool(3);
        var busy = admit(workerB, pool, AgentRunBand.BATCH, 2);
        var otherBusy = admit(workerB, pool, AgentRunBand.BATCH, 2);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new CompletableFuture<String>();
            var composition = calls.submit(() -> {
                var permit = admit(workerA, pool, AgentRunBand.BATCH, 1);
                first.complete("composition");
                return permit;
            });
            var repeat = calls.submit(() -> {
                var permit = admit(workerB, pool, AgentRunBand.BATCH, 2);
                first.complete("repeat");
                return permit;
            });
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(2));
            for (int i = 0; i < 3; i++)
                admit(workerA, pool, AgentRunBand.INTERACTIVE, 1).close();
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

    private LlmCallPermits worker(String id, SimpleMeterRegistry registry) {
        return new LlmCallPermits(
                pools,
                rows,
                transactions,
                PROPERTIES,
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                id,
                registry,
                mock(LlmModelResolver.class));
    }

    @Test
    void shouldKeepAnActiveCallWhileItsAttemptAcquiresAnotherPool() {
        long primary = pool(1);
        long auxiliary = pool(1);
        var caller = caller(1);
        try (var active = Objects.requireNonNull(workerA.acquire(
                primary, AgentRunBand.BATCH, caller, () -> false, Instant.now().plusSeconds(2)))) {
            try (var other = Objects.requireNonNull(workerA.acquire(
                    auxiliary,
                    AgentRunBand.BATCH,
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
    void shouldRefuseToWaitForCapacityInsideAnOpenTransaction() {
        long pool = pool(1);
        transactions.executeWithoutResult(status -> assertThatThrownBy(() -> workerA.acquire(
                        pool,
                        AgentRunBand.BATCH,
                        caller(1),
                        () -> false,
                        Instant.now().plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model calls must acquire capacity outside a database transaction"));
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldGrantARemoteRequestOnReleaseWithoutAWaitingLocalThread() {
        long pool = pool(1);
        var active = admit(workerA, pool, AgentRunBand.BATCH, 1);
        UUID remote = UUID.randomUUID();
        transactions.executeWithoutResult(status -> rows.enqueue(new LlmCallPermitRepository.Request(
                remote, pool, "remote-" + UUID.randomUUID(), AgentRunBand.BATCH, 2L, UUID.randomUUID(), 0)));

        active.close();

        assertThat(rows.findById(remote).orElseThrow().getState()).isEqualTo("ACTIVE");
        assertThat(rows.countActive(pool)).isEqualTo(1);
    }

    @Test
    void shouldGrantBackfillBeforeProductFeedbackOnRelease() {
        long pool = pool(1);
        var active = admit(workerA, pool, AgentRunBand.INTERACTIVE, 1);
        UUID feedback = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            rows.enqueue(new LlmCallPermitRepository.Request(
                    feedback, pool, "feedback", AgentRunBand.BACKGROUND, null, UUID.randomUUID(), 0));
            rows.enqueue(new LlmCallPermitRepository.Request(
                    review, pool, "review", AgentRunBand.BACKGROUND, 2L, UUID.randomUUID(), 0));
        });

        active.close();

        assertThat(rows.findById(review).orElseThrow().getState()).isEqualTo("ACTIVE");
        assertThat(rows.findById(feedback).orElseThrow().getState()).isEqualTo("WAITING");
    }

    @Test
    void shouldDeliverPoolHintsOnlyAfterCommitAndOmitRolledBackHints() throws Exception {
        var notifications = new LlmPoolNotifications(dataSource);
        var received = new LinkedBlockingQueue<Long>();
        var listening = new CompletableFuture<Void>();
        notifications.subscribe(received::add, () -> listening.complete(null));
        try {
            listening.get(5, TimeUnit.SECONDS);
            transactions.executeWithoutResult(status -> {
                rows.notifyPool(LlmPoolNotifications.CHANNEL, "11");
                status.setRollbackOnly();
            });
            transactions.executeWithoutResult(status -> {
                rows.notifyPool(LlmPoolNotifications.CHANNEL, "invalid");
                rows.notifyPool(LlmPoolNotifications.CHANNEL, "12");
                assertThat(received).isEmpty();
            });

            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(12L);
            assertThat(received).isEmpty();
        } finally {
            notifications.destroy();
        }
    }

    @Test
    void shouldHoldAnInputBucketApartFromTheOutputBucket() {
        long pool = pool(4);
        jdbc.update(
                "UPDATE llm_call_pool SET input_tokens_per_minute=150,output_tokens_per_minute=50,tokens_per_call=100 WHERE id=?",
                pool);
        var first = admit(workerA, pool, AgentRunBand.INTERACTIVE, 1);
        assertThat(rows.rates(pool).getInputTokens()).isEqualTo(100);
        // A running call has no debit on the output bucket, so a bound above its rate does not stop calls.
        assertThat(rows.rates(pool).getOutputTokens()).isZero();

        assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2)).isNull();
        first.close();
    }

    @Test
    void shouldStopCallsWhenReportedOutputFillsTheOutputBucket() {
        long pool = pool(4);
        jdbc.update("UPDATE llm_call_pool SET output_tokens_per_minute=50 WHERE id=?", pool);
        var call = admit(workerA, pool, AgentRunBand.INTERACTIVE, 1);
        call.settled(new ProxyTokenUsage(1, 51, 0, 0, 0), LlmApiProtocol.ANTHROPIC_MESSAGES);
        call.close();

        assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2)).isNull();
    }

    @Test
    void shouldReplaceTheBoundByTheProvidersUsageWhenACallCloses() {
        long pool = pool(4);
        jdbc.update("UPDATE llm_call_pool SET tokens_per_minute=150,tokens_per_call=100 WHERE id=?", pool);
        var reported = admit(workerA, pool, AgentRunBand.BATCH, 1);
        reported.settled(new ProxyTokenUsage(7, 20, 0, 1_000, 3), LlmApiProtocol.ANTHROPIC_MESSAGES);
        reported.close();

        // Anthropic counts no cache reads in its input bucket.
        assertThat(rows.rates(pool).getInputTokens()).isEqualTo(10);
        assertThat(rows.rates(pool).getOutputTokens()).isEqualTo(20);
        assertThat(rows.rates(pool).getTokens()).isEqualTo(30);

        var unreported = admit(workerB, pool, AgentRunBand.INTERACTIVE, 2);
        assertThat(rows.rates(pool).getTokens()).isEqualTo(130);
        unreported.close();

        // A call without a report keeps its bound, so the bucket stays full for a third call.
        assertThat(rows.rates(pool).getTokens()).isEqualTo(130);
        assertThat(probe(workerA, pool, AgentRunBand.INTERACTIVE, 3)).isNull();
    }

    @Test
    void shouldCountAWholePromptForAProviderThatCountsCacheReads() {
        long pool = pool(4);
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);
        call.settled(new ProxyTokenUsage(7, 20, 0, 1_000, 3), LlmApiProtocol.OPENAI_RESPONSES);
        call.close();

        assertThat(rows.rates(pool).getInputTokens()).isEqualTo(1_010);
    }

    @Test
    void shouldPauseEveryCallerWithoutLoweringTheLimitWhenHeadersLeaveNoRoom() {
        long pool = pool(4);
        int limit = learnedLimit(pool);
        try (var call = admit(workerA, pool, AgentRunBand.BATCH, 1)) {
            workerA.served(call, noRequestsLeftForAnHour(), true);

            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(pausedMillis(pool)).isPositive());
            assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2)).isNull();
            assertThat(learnedLimit(pool)).isEqualTo(limit);
            assertThat(workerAMeters
                            .get(AgentMetrics.LLM_POOL_UPSTREAM_BUCKET_REACHED)
                            .tags("pool", pools.findById(pool).orElseThrow().getSlug(), "bucket", "requests")
                            .counter()
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldNotSendAReleaseHintWhenAWaitingRequestAbandons() throws Exception {
        long pool = pool(1);
        var notifications = new LlmPoolNotifications(dataSource);
        var received = new LinkedBlockingQueue<Long>();
        var listening = new CompletableFuture<Void>();
        try (var active = admit(workerA, pool, AgentRunBand.BATCH, 1)) {
            assertThat(active.isRevoked()).isFalse();
            notifications.subscribe(received::add, () -> listening.complete(null));
            listening.get(5, TimeUnit.SECONDS);

            assertThat(probe(workerB, pool, AgentRunBand.BATCH, 2)).isNull();
            // Commit order makes this a barrier: an abandon hint would be delivered before it.
            transactions.executeWithoutResult(
                    status -> rows.notifyPool(LlmPoolNotifications.CHANNEL, Long.toString(Long.MAX_VALUE)));

            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(Long.MAX_VALUE);
            assertThat(received).isEmpty();
            assertThat(rows.countActive(pool)).isEqualTo(1);
        } finally {
            notifications.destroy();
        }
    }

    @Test
    void shouldHandOffARemoteGrantAfterReleaseWithDatabaseNotifications(TestReporter reporter) throws Exception {
        var first = new LlmPoolNotifications(dataSource);
        var second = new LlmPoolNotifications(dataSource);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            workerA = new LlmCallPermits(
                    pools,
                    rows,
                    transactions,
                    PROPERTIES,
                    LlmCallPermitsTestFixtures.PERMIT_AGING,
                    "wake-a-" + UUID.randomUUID(),
                    workerAMeters,
                    mock(LlmModelResolver.class),
                    first);
            workerB = new LlmCallPermits(
                    pools,
                    rows,
                    transactions,
                    PROPERTIES,
                    LlmCallPermitsTestFixtures.PERMIT_AGING,
                    "wake-b-" + UUID.randomUUID(),
                    new SimpleMeterRegistry(),
                    mock(LlmModelResolver.class),
                    second);
            workerA.start();
            workerB.start();
            long pool = pool(1);
            var active = admit(workerA, pool, AgentRunBand.BATCH, 1);
            try {
                var queued = calls.submit(() -> admit(workerB, pool, AgentRunBand.BATCH, 2));
                await().atMost(Duration.ofSeconds(5)).until(() -> waiting(pool) == 1);
                long releasedAt = System.nanoTime();
                active.close();
                try (var granted = queued.get(5, TimeUnit.SECONDS)) {
                    assertThat(granted.isRevoked()).isFalse();
                    assertThat(rows.countActive(pool)).isEqualTo(1);
                    reporter.publishEntry(
                            "cross-worker release to handoff ms",
                            Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releasedAt)));
                }
            } finally {
                active.close();
            }
        } finally {
            workerA.stop();
            workerB.stop();
            first.destroy();
            second.destroy();
        }
    }

    @Test
    void shouldNotPauseASharedPoolForTheHeadersOfAWorkspacesOwnCredential() {
        long pool = pool(4);
        try (var call = admit(workerA, pool, AgentRunBand.BATCH, 1)) {
            workerA.served(call, noRequestsLeftForAnHour(), false);

            await().during(Duration.ofMillis(500))
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(pausedMillis(pool)).isZero());
        }
    }

    private static HttpHeaders noRequestsLeftForAnHour() {
        var headers = new HttpHeaders();
        headers.add("x-ratelimit-remaining-requests", "0");
        headers.add("x-ratelimit-reset-requests", "1h");
        return headers;
    }

    private int learnedLimit(long pool) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT learned_limit FROM llm_call_pool WHERE id=?", Integer.class, pool));
    }

    private long pausedMillis(long pool) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COALESCE(GREATEST(0,EXTRACT(EPOCH FROM no_call_before-clock_timestamp())*1000),0)::bigint"
                        + " FROM llm_call_pool WHERE id=?",
                Long.class,
                pool));
    }

    /** A pool that has already learned its maximum, so that the maximum decides in these tests. */
    private long pool(int maximum) {
        var pool = new LlmCallPool();
        pool.setSlug("pool-" + UUID.randomUUID());
        pool.setDisplayName("Endpoint");
        pool.setMaxConcurrentCalls(maximum);
        long id = pools.save(pool).getId();
        jdbc.update("UPDATE llm_call_pool SET learned_limit=? WHERE id=?", maximum, id);
        return id;
    }

    private static LlmCallPermits.Caller caller(long workspace) {
        return new LlmCallPermits.Caller(workspace, UUID.randomUUID(), 0, null);
    }

    private static LlmCallPermits.Permit admit(LlmCallPermits worker, long pool, AgentRunBand kind, long workspace) {
        return admit(worker, pool, kind, caller(workspace));
    }

    private static LlmCallPermits.Permit admit(
            LlmCallPermits worker, long pool, AgentRunBand band, LlmCallPermits.Caller caller) {
        return Objects.requireNonNull(
                worker.acquire(pool, band, caller, () -> false, Instant.now().plusSeconds(10)));
    }

    /** Another server's call that has waited this long; its own server is not in the test. */
    private UUID waitingCall(long pool, AgentRunBand band, long workspace, UUID source, Duration waited) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> rows.enqueue(
                new LlmCallPermitRepository.Request(id, pool, "remote-" + id, band, workspace, source, 0)));
        jdbc.update(
                "UPDATE llm_call_permit SET acquired_at=clock_timestamp()-make_interval(secs => ?) WHERE id=?",
                waited.toSeconds(),
                id);
        return id;
    }

    private String state(UUID id) {
        return rows.findById(id).orElseThrow().getState();
    }

    private static LlmCallPermits.@Nullable Permit probe(
            LlmCallPermits worker, long pool, AgentRunBand kind, long workspace) {
        return worker.acquire(
                pool, kind, caller(workspace), () -> false, Instant.now().plusMillis(25));
    }

    private Timestamp renewedAt(long pool) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT renewed_at FROM llm_call_permit WHERE pool_id=?", Timestamp.class, pool));
    }

    private long waiting(long pool) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING'", Long.class, pool));
    }
}
