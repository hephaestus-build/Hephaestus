package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a provider's limit does to all callers, on the rows two workers share: the limit falls, callers slow, nothing
 * fails, the limit recovers, conversations keep their reserve, and grants stay fair.
 */
class LlmCallPoolLearningIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LlmCallPoolRepository pools;

    @Autowired
    private LlmCallPermitRepository rows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmModelResolver models;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private LlmCallPermits workerA;
    private LlmCallPermits workerB;

    @BeforeEach
    void setUp() {
        workerA = worker("a-" + UUID.randomUUID(), meters);
        workerB = worker("b-" + UUID.randomUUID(), meters);
    }

    @AfterEach
    void stopWorkers() {
        workerA.stop();
        workerB.stop();
    }

    /** A provider that serves at most {@code capacity} calls in flight and refuses the rest with a short pause. */
    private static final class FakeProvider {
        final AtomicInteger capacity;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger served = new AtomicInteger();
        final AtomicInteger refused = new AtomicInteger();
        /** For each served call in order: how many calls were in flight at the provider when it started. */
        final List<Integer> concurrencyServed = Collections.synchronizedList(new ArrayList<>());

        final Duration callTakes;

        FakeProvider(int capacity, Duration callTakes) {
            this.capacity = new AtomicInteger(capacity);
            this.callTakes = callTakes;
        }

        boolean call() throws InterruptedException {
            try {
                int concurrent = inFlight.incrementAndGet();
                if (concurrent > capacity.get()) {
                    refused.incrementAndGet();
                    return false;
                }
                Thread.sleep(callTakes.toMillis()); // NOPMD - simulated call duration
                served.incrementAndGet();
                concurrencyServed.add(concurrent);
                return true;
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    /** What the proxy does with one call: it waits for a grant, and after a refusal it takes its place in line again. */
    private void callUntilServed(FakeProvider provider, LlmCallPermits worker, long pool, UUID source, long workspace)
            throws InterruptedException {
        HttpHeaders pause = new HttpHeaders();
        pause.set(HttpHeaders.RETRY_AFTER, "1");
        while (true) {
            try (var permit = Objects.requireNonNull(worker.acquire(
                    pool,
                    AgentRunBand.BATCH,
                    new LlmCallPermits.Caller(workspace, source, 0, null),
                    () -> false,
                    Instant.now().plusSeconds(120)))) {
                if (provider.call()) {
                    permit.served(true);
                    return;
                }
                worker.throttled(permit, 429, pause, true);
                permit.absorbed();
            }
        }
    }

    @Test
    void shouldSlowEveryCallerAndFailNoneWhenTheProviderRefusesThenRecover(TestReporter reporter) throws Exception {
        long pool = ownPool();
        var provider = new FakeProvider(4, Duration.ofMillis(200));

        long began = System.nanoTime();
        runCallers(provider, pool, 32);
        Duration slowed = Duration.ofNanos(System.nanoTime() - began);

        assertThat(provider.served).hasValue(32);
        assertThat(provider.refused.get()).isPositive();
        var afterRefusals = pools.findById(pool).orElseThrow();
        assertThat(afterRefusals.getThrottledAt()).isNotNull();
        assertThat(rows.countActive(pool)).isZero();
        String slug = afterRefusals.getSlug();
        assertThat(meters.get(AgentMetrics.LLM_POOL_UPSTREAM_THROTTLED)
                        .tag("pool", slug)
                        .tag("status", "429")
                        .counter()
                        .count())
                .isPositive();
        assertThat(meters.get(AgentMetrics.LLM_POOL_UPSTREAM_ABSORBED)
                        .tag("pool", slug)
                        .counter()
                        .count())
                .isPositive();
        assertThat(meters.get(AgentMetrics.LLM_POOL_PERMITS_GRANTED)
                        .tag("pool", slug)
                        .tag("band", "batch")
                        .counter()
                        .count())
                .isPositive();
        int fell = afterRefusals.getLearnedLimit();

        // The provider has room again, and the mark of its last refusal grew old: the limit rises quickly.
        provider.capacity.set(64);
        jdbc.update("UPDATE llm_call_pool SET throttled_at=clock_timestamp()-interval '11 minutes' WHERE id=?", pool);
        runCallers(provider, pool, 64);

        int recovered = pools.findById(pool).orElseThrow().getLearnedLimit();
        assertThat(recovered).isGreaterThan(fell);
        reporter.publishEntry(Map.of(
                "callers32Seconds", Double.toString(slowed.toMillis() / 1000.0),
                "refusedAtCapacity4", provider.refused.toString(),
                "limitAfterRefusals", Integer.toString(fell),
                "limitAfterRecovery", Integer.toString(recovered)));
    }

    @Test
    void shouldServeEveryCallWithFewRefusalsWhileTheSharedRowsLearnTheProvidersCapacity(TestReporter reporter)
            throws Exception {
        long pool = ownPool();
        var provider = new FakeProvider(12, Duration.ofMillis(200));
        // A fixed amount of work, not a time window: the pool's behaviour, not the host's speed, decides the counts.
        int callers = 60;
        int callsEach = 6;
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                UUID source = UUID.randomUUID();
                long workspace = 1L + i % 3;
                running.add(calls.submit(() -> {
                    for (int call = 0; call < callsEach; call++)
                        callUntilServed(provider, worker, pool, source, workspace);
                    return null;
                }));
            }
            for (var call : running) call.get(300, TimeUnit.SECONDS);
        }
        int limit = limit(pool);
        List<Integer> served = List.copyOf(provider.concurrencyServed);
        // Reported, not asserted: grants wait for admission rounds and pauses, so the concurrency here measures the
        // host's
        // timing as much as the limit. LlmCallLimitTest derives where the limit itself settles.
        double settled = served.subList(served.size() / 2, served.size()).stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0);
        reporter.publishEntry(Map.of(
                "settleCapacity", "12",
                "settleLimit", Integer.toString(limit),
                "settleServed", Integer.toString(served.size()),
                "settleConcurrency", Double.toString(settled),
                "settleRefused", provider.refused.toString()));
        assertThat(served).hasSize(callers * callsEach);
        assertThat(provider.refused.get()).isLessThan(served.size() / 4);
        assertThat(limit).isBetween(6, 24);
    }

    /** A GPU server: it never refuses, runs what its slots hold, and queues the rest, so that first tokens come later. */
    private static final class FakeGpu {
        final Semaphore slots;
        final AtomicLong tokens = new AtomicLong();
        final AtomicInteger waiting = new AtomicInteger();
        final AtomicInteger mostWaiting = new AtomicInteger();

        FakeGpu(int slots) {
            this.slots = new Semaphore(slots, true);
        }

        void call(Runnable firstToken) throws InterruptedException {
            mostWaiting.accumulateAndGet(waiting.incrementAndGet(), Math::max);
            slots.acquire();
            waiting.decrementAndGet();
            try {
                Thread.sleep(50); // NOPMD - the simulated provider answers its first token after this delay
                firstToken.run();
                Thread.sleep(1_000); // NOPMD - the simulated provider streams for this long, which the pool measures
                tokens.addAndGet(100);
            } finally {
                slots.release();
            }
        }
    }

    @Test
    void shouldLowerTheLimitFromFirstTokensAloneWhenAServerQueuesAndNeverRefuses(TestReporter reporter)
            throws Exception {
        long pool = ownPool();
        String slug = slug(pool);
        var gpu = new FakeGpu(8);
        long ends = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        List<Integer> limits = new ArrayList<>();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                UUID source = UUID.randomUUID();
                running.add(calls.submit(() -> {
                    while (System.nanoTime() < ends) {
                        try (var permit = Objects.requireNonNull(worker.acquire(
                                pool,
                                AgentRunBand.BATCH,
                                new LlmCallPermits.Caller(1L, source, 0, null),
                                () -> false,
                                Instant.now().plusSeconds(120)))) {
                            long sent = System.nanoTime();
                            gpu.call(() -> worker.firstToken(permit, System.nanoTime() - sent, true));
                            permit.served(true);
                        }
                    }
                    return null;
                }));
            }
            while (System.nanoTime() < ends) {
                TimeUnit.SECONDS.sleep(1); // NOPMD - samples the pool each second
                limits.add(limit(pool));
            }
            for (var call : running) call.get(120, TimeUnit.SECONDS);
        }

        var row = pools.findById(pool).orElseThrow();
        double delayDecreases = meters.get(AgentMetrics.LLM_POOL_LIMIT_DECREASES)
                .tag("pool", slug)
                .tag("cause", "delay")
                .counter()
                .count();
        reporter.publishEntry(Map.of(
                "gpuSlots", "8",
                "gpuLimits", limits.toString(),
                "gpuTokensPerSecond", Double.toString(gpu.tokens.get() / 45.0),
                "gpuPeakTokensPerSecond", Double.toString(8 * 100.0 / 1.05),
                "gpuMostWaiting", gpu.mostWaiting.toString(),
                "gpuBaselineMs", String.valueOf(row.getBaselineFirstTokenMs())));
        assertThat(delayDecreases).isPositive();
        assertThat(meters.get(AgentMetrics.GEN_AI_CLIENT_TIME_TO_FIRST_CHUNK)
                        .tag("pool", slug)
                        .tag("band", "batch")
                        .timer()
                        .count())
                .isPositive();
        assertThat(meters.get(AgentMetrics.GEN_AI_CLIENT_TIME_TO_FIRST_CHUNK)
                        .tag("pool", slug)
                        .tag("band", "interactive")
                        .timer()
                        .count())
                .isZero();
        assertThat(row.getThrottledAt()).isNotNull();
        assertThat(row.getNoCallBefore()).isNull();
        assertThat(row.getBaselineFirstTokenMs()).isNotNull().isGreaterThanOrEqualTo(50);
        assertThat(row.getLearnedLimit()).isBetween(LlmCallLimit.FLOOR, 30);
        assertThat(gpu.tokens.get() / 45.0).isGreaterThan(0.7 * 8 * 100.0 / 1.05);
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldNotRaiseALimitThatFirstTokensWatchForACallThatDidNotStream() {
        long pool = ownPool();
        setLimit(pool, 4);
        jdbc.update("UPDATE llm_call_pool SET baseline_first_token_ms=900 WHERE id=?", pool);
        var composition = admit(workerA, pool, AgentRunBand.BATCH, 1);
        var held = List.of(admit(workerA, pool, AgentRunBand.BATCH, 1), admit(workerA, pool, AgentRunBand.BATCH, 1));

        composition.served(true);
        composition.close();
        held.forEach(LlmCallPermits.Permit::close);

        assertThat(limit(pool)).isEqualTo(4);
    }

    @Test
    void shouldLeaveASharedPoolAloneWhenAWorkspaceCredentialsCallIsSlow() {
        long pool = sharedPool();
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);

        workerA.firstToken(call, Duration.ofMinutes(5).toNanos(), false);
        call.close();

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> assertThat(pools.findById(pool).orElseThrow().getWindowSamples())
                                .isZero());
    }

    private void runCallers(FakeProvider provider, long pool, int callers) throws Exception {
        int before = provider.served.get();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                UUID source = UUID.randomUUID();
                long workspace = 1L + i % 3;
                running.add(calls.submit(() -> {
                    callUntilServed(provider, worker, pool, source, workspace);
                    return null;
                }));
            }
            for (var call : running) call.get(120, TimeUnit.SECONDS);
        }
        assertThat(provider.served.get() - before).isEqualTo(callers);
    }

    @Test
    void shouldLowerTheLimitOnceForTheCallsThatStartedUnderIt() {
        long pool = ownPool();
        var first = admit(workerA, pool, AgentRunBand.BATCH, 1);
        var second = admit(workerB, pool, AgentRunBand.BATCH, 1);

        workerA.throttled(first, 429, pause(1), true);
        workerB.throttled(second, 429, pause(1), true);

        assertThat(limit(pool)).isEqualTo(5);
        first.close();
        second.close();

        clearCooldown(pool);
        var later = admit(workerA, pool, AgentRunBand.BATCH, 1);
        workerA.throttled(later, 503, pause(1), true);
        later.close();
        assertThat(limit(pool)).isEqualTo(3);
        assertThat(meters.get(AgentMetrics.LLM_POOL_LIMIT_DECREASES)
                        .tag("pool", slug(pool))
                        .tag("cause", "refusal")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void shouldWaitForTheProvidersPauseAcrossWorkersEvenForAConversation() {
        long pool = ownPool();
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);
        workerA.throttled(call, 429, pause(2), true);
        call.close();

        assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2, Duration.ofMillis(50)))
                .isNull();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2, Duration.ofMillis(300)))
                                .isNotNull());
    }

    @Test
    void shouldHoldAGrantThatAnotherWorkerMadeUntilARefusalsPauseEnds() throws Exception {
        long pool = ownPool();
        setMaximum(pool, 1);
        var held = admit(workerB, pool, AgentRunBand.BATCH, 2);
        AtomicBoolean ended = new AtomicBoolean();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiter = calls.submit(() -> acquire(workerA, pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), ended));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(1));
            UUID queued = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT id FROM llm_call_permit WHERE pool_id=? AND state='WAITING'", UUID.class, pool));
            // Another worker granted it, and then a refusal paused the pool before its own worker found the grant.
            held.close();
            transactions.executeWithoutResult(status -> {
                jdbc.update(
                        "UPDATE llm_call_permit SET state='ACTIVE',granted_at=clock_timestamp(),requests=1 WHERE id=? AND state='WAITING'",
                        queued);
                jdbc.update(
                        "UPDATE llm_call_pool SET no_call_before=clock_timestamp()+interval '2 seconds' WHERE id=?",
                        pool);
            });

            TimeUnit.MILLISECONDS.sleep(1_000); // NOPMD - the pool measures this window
            assertThat(waiter.isDone()).isFalse();
            var permit = waiter.get(10, TimeUnit.SECONDS);
            assertThat(permit).isNotNull();
            assertThat(Objects.requireNonNull(pools.findById(pool).orElseThrow().getNoCallBefore()))
                    .isBefore(Instant.now());
            Objects.requireNonNull(permit).close();
        }
    }

    @Test
    void shouldLowerTheLimitThatAppliesWhenAnAdminsMaximumIsBelowTheLearnedOne() {
        long pool = ownPool();
        setLimit(pool, 64);
        setMaximum(pool, 16);
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);

        workerA.throttled(call, 429, pause(1), true);
        call.close();

        assertThat(limit(pool)).isEqualTo(11);
    }

    @Test
    void shouldNotRaiseTheLimitForAServedCallThatStartedBeforeTheLastRefusal() {
        long pool = ownPool();
        var old = admit(workerA, pool, AgentRunBand.BATCH, 1);
        var refused = admit(workerA, pool, AgentRunBand.BATCH, 1);
        workerA.throttled(refused, 429, pause(1), true);
        refused.close();
        int lowered = limit(pool);

        old.served(true);
        old.close();

        assertThat(limit(pool)).isEqualTo(lowered);
    }

    @Test
    void shouldNotRaiseTheLimitWhenItDoesNotBind() {
        long pool = ownPool();
        var alone = admit(workerA, pool, AgentRunBand.BATCH, 1);
        alone.served(true);
        alone.close();

        assertThat(limit(pool)).isEqualTo(8);
    }

    @Test
    void shouldRaiseTheLimitByOneForAWholeLimitOfServedCallsAfterARefusal() {
        long pool = ownPool();
        setLimit(pool, 4);
        jdbc.update("UPDATE llm_call_pool SET throttled_at=clock_timestamp() WHERE id=?", pool);

        for (int served = 0; served < 4; served++) {
            assertThat(limit(pool)).isEqualTo(4);
            // Three background calls fill the limit of four, so the limit binds when the first ends.
            var held = List.of(
                    admit(workerA, pool, AgentRunBand.BATCH, 1),
                    admit(workerA, pool, AgentRunBand.BATCH, 1),
                    admit(workerB, pool, AgentRunBand.BATCH, 2));
            held.getFirst().served(true);
            held.forEach(LlmCallPermits.Permit::close);
        }

        assertThat(limit(pool)).isEqualTo(5);
    }

    @Test
    void shouldNotLearnPastAnAdminsMaximum() {
        long pool = ownPool();
        setLimit(pool, 4);
        setMaximum(pool, 4);
        var held = List.of(
                admit(workerA, pool, AgentRunBand.BATCH, 1),
                admit(workerA, pool, AgentRunBand.BATCH, 1),
                admit(workerA, pool, AgentRunBand.BATCH, 1));
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 1, Duration.ofMillis(50)))
                .isNull();
        held.getFirst().served(true);
        held.forEach(LlmCallPermits.Permit::close);

        assertThat(limit(pool)).isEqualTo(4);
    }

    @Test
    void shouldKeepTheReserveForConversationsAsTheLimitFalls() {
        long pool = ownPool();
        List<LlmCallPermits.Permit> reviews = new ArrayList<>();
        // A limit of eight leaves six background calls and two for conversations.
        for (int i = 0; i < 6; i++) reviews.add(admit(workerA, pool, AgentRunBand.BATCH, 1));
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 1, Duration.ofMillis(50)))
                .isNull();

        // The limit falls to five, while the six reviews stay in flight. Its reserve of two still holds.
        workerA.throttled(reviews.getFirst(), 429, pause(1), true);
        clearCooldown(pool);
        assertThat(limit(pool)).isEqualTo(5);
        var firstTurn = admit(workerB, pool, AgentRunBand.INTERACTIVE, 2);
        var secondTurn = admit(workerB, pool, AgentRunBand.INTERACTIVE, 2);
        assertThat(probe(workerB, pool, AgentRunBand.INTERACTIVE, 2, Duration.ofMillis(50)))
                .isNull();
        assertThat(probe(workerB, pool, AgentRunBand.BATCH, 1, Duration.ofMillis(50)))
                .isNull();

        firstTurn.close();
        secondTurn.close();
        reviews.forEach(LlmCallPermits.Permit::close);
    }

    @Test
    void shouldGrantInTurnBetweenWideAndNarrowReviewsAndWorkspaces() throws Exception {
        long pool = ownPool();
        setLimit(pool, 8);
        UUID wide = UUID.randomUUID();
        UUID narrow = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        // The wide review already holds its fill.
        List<LlmCallPermits.Permit> held = new ArrayList<>();
        for (int i = 0; i < 6; i++) held.add(admit(workerA, pool, AgentRunBand.BATCH, 1, wide));
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicBoolean ended = new AtomicBoolean();
            List<Future<LlmCallPermits.@Nullable Permit>> queued = new ArrayList<>();
            for (int i = 0; i < 20; i++)
                queued.add(calls.submit(() -> acquire(workerA, pool, AgentRunBand.BATCH, 1, wide, ended)));
            for (int i = 0; i < 4; i++)
                queued.add(calls.submit(() -> acquire(workerB, pool, AgentRunBand.BATCH, 1, narrow, ended)));
            for (int i = 0; i < 4; i++)
                queued.add(calls.submit(() -> acquire(workerB, pool, AgentRunBand.BATCH, 2, other, ended)));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(28));

            // The wide review ends three of its calls. They go to the other workspace, which holds none.
            for (int i = 0; i < 3; i++) held.remove(0).close();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(rows.countActive(pool)).isEqualTo(6));
            assertThat(activeOf(pool, other)).isEqualTo(3);
            assertThat(activeOf(pool, narrow)).isZero();
            assertThat(activeOf(pool, wide)).isEqualTo(3);

            // Now the first workspace holds less, so it gets the next call. Within it, the narrow review holds none, so
            // it goes first, although the wide review has five times its calls waiting.
            held.remove(0).close();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(rows.countActive(pool)).isEqualTo(6));
            assertThat(activeOf(pool, other)).isEqualTo(3);
            assertThat(activeOf(pool, narrow)).isEqualTo(1);
            assertThat(activeOf(pool, wide)).isEqualTo(2);

            assertThat(waiting(pool)).isEqualTo(24);

            ended.set(true);
            for (var call : queued) {
                var permit = call.get(10, TimeUnit.SECONDS);
                if (permit != null) permit.close();
            }
            held.forEach(LlmCallPermits.Permit::close);
        }
    }

    @Test
    void shouldServeAConversationAtOnceBesideHundredsOfWaitingReviewCalls(TestReporter reporter) throws Exception {
        long pool = ownPool();
        setLimit(pool, 8);
        List<LlmCallPermits.Permit> held = new ArrayList<>();
        for (int i = 0; i < 6; i++) held.add(admit(workerA, pool, AgentRunBand.BATCH, 1));
        AtomicBoolean ended = new AtomicBoolean();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<LlmCallPermits.@Nullable Permit>> queued = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                LlmCallPermits worker = i % 2 == 0 ? workerA : workerB;
                queued.add(calls.submit(() -> acquire(worker, pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), ended)));
            }
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(400));
            List<Long> latencies = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                long start = System.nanoTime();
                var turn = admit(i % 2 == 0 ? workerA : workerB, pool, AgentRunBand.INTERACTIVE, 2);
                latencies.add(System.nanoTime() - start);
                turn.close();
            }
            latencies.sort(Long::compare);
            long p50 = latencies.get(19);
            long p95 = latencies.get(37);
            reporter.publishEntry(Map.of(
                    "reviewWaiters", "400",
                    "turnP50Ms", Double.toString(p50 / 1_000_000.0),
                    "turnP95Ms", Double.toString(p95 / 1_000_000.0)));
            assertThat(p95).isLessThan(Duration.ofSeconds(1).toNanos());

            ended.set(true);
            for (var call : queued) {
                var permit = call.get(30, TimeUnit.SECONDS);
                if (permit != null) permit.close();
            }
        } finally {
            held.forEach(LlmCallPermits.Permit::close);
        }
    }

    @Test
    void shouldGrantWithoutLosingTimeToThePollWhenHundredsWait(TestReporter reporter) throws Exception {
        long pool = ownPool();
        setLimit(pool, 1000);
        // A grant waits for its round, and one round serves all the room there is.
        var provider = new FakeProvider(1000, Duration.ofMillis(1));
        long began = System.nanoTime();
        runCallers(provider, pool, 500);
        Duration took = Duration.ofNanos(System.nanoTime() - began);
        reporter.publishEntry(Map.of("calls", "500", "seconds", Double.toString(took.toMillis() / 1000.0)));
        assertThat(took).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void shouldLeaveASharedPoolAloneWhenAWorkspaceCredentialIsRefused() {
        long pool = sharedPool();
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);

        assertThat(workerA.throttled(call, 429, pause(30), false)).isNull();
        call.close();

        var row = pools.findById(pool).orElseThrow();
        assertThat(row.getNoCallBefore()).isNull();
        assertThat(row.getLearnedLimit()).isEqualTo(8);
        assertThat(row.getThrottledAt()).isNull();
    }

    @Test
    void shouldNotRaiseASharedPoolsLimitForAWorkspaceCredentialsServedCall() {
        long pool = sharedPool();
        var held = new ArrayList<LlmCallPermits.Permit>();
        for (int i = 0; i < 6; i++) held.add(admit(workerA, pool, AgentRunBand.BATCH, 1));

        held.getFirst().served(false);
        held.forEach(LlmCallPermits.Permit::close);

        assertThat(limit(pool)).isEqualTo(8);
    }

    @Test
    void shouldCloseAGrantThatAnotherWorkerMadeWhileTheCallEnded() throws Exception {
        long pool = ownPool();
        setLimit(pool, 2);
        setMaximum(pool, 2);
        var held = admit(workerA, pool, AgentRunBand.BATCH, 1);
        AtomicBoolean ended = new AtomicBoolean();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiter = calls.submit(() -> acquire(workerB, pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), ended));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(1));
            // This worker's round grants the other worker's call, which ends before its worker finds the grant.
            ended.set(true);
            held.close();
            admit(workerA, pool, AgentRunBand.BATCH, 2).close();
            var late = waiter.get(10, TimeUnit.SECONDS);
            if (late != null) late.close();
        }
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(rows.countActive(pool)).isZero());
    }

    @Test
    void shouldLetAWorkspaceCredentialSlowItsOwnModelsPool() {
        long pool = ownPool();
        var call = admit(workerA, pool, AgentRunBand.BATCH, 1);

        assertThat(workerA.throttled(call, 429, pause(30), false)).isEqualTo(Duration.ofSeconds(30));
        call.close();

        var row = pools.findById(pool).orElseThrow();
        assertThat(row.getNoCallBefore()).isAfter(Instant.now());
        assertThat(row.getLearnedLimit()).isEqualTo(5);
    }

    @Test
    void shouldExportTheLimitAndTheWaitersFromTheDatabase() throws Exception {
        long pool = ownPool();
        String slug = slug(pool);
        setLimit(pool, 2);
        var held = admit(workerA, pool, AgentRunBand.BATCH, 1);
        AtomicBoolean ended = new AtomicBoolean();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiter = calls.submit(() -> acquire(workerB, pool, AgentRunBand.BATCH, 1, UUID.randomUUID(), ended));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(waiting(pool)).isEqualTo(1));

            workerA.observeBacklog();

            assertThat(gauge(meters, AgentMetrics.LLM_POOL_LIMIT, slug)).isEqualTo(2);
            assertThat(meters.get(AgentMetrics.LLM_POOL_WAITERS)
                            .tag("pool", slug)
                            .tag("band", "batch")
                            .gauge()
                            .value())
                    .isEqualTo(1);
            ended.set(true);
            var permit = waiter.get(5, TimeUnit.SECONDS);
            if (permit != null) permit.close();
        }
        held.close();
    }

    @Test
    void shouldLabelAWorkspaceModelsOwnPoolWithoutNamingIt() {
        var pool = new LlmCallPool();
        pool.setSlug("own-workspace-model-" + UUID.randomUUID());
        pool.setDisplayName("Own");
        long id = pools.save(pool).getId();

        admit(workerA, id, AgentRunBand.BATCH, 1).close();

        assertThat(meters.get(AgentMetrics.LLM_POOL_PERMITS_GRANTED)
                        .tag("pool", LlmPoolMeters.WORKSPACE_MODELS_LABEL)
                        .tag("band", "batch")
                        .counter()
                        .count())
                .isPositive();
    }

    private static double gauge(MeterRegistry registry, String name, String slug) {
        return registry.get(name).tag("pool", slug).gauge().value();
    }

    private LlmCallPermits worker(String id, MeterRegistry registry) {
        return new LlmCallPermits(
                pools,
                rows,
                transactions,
                LlmCallPermitsTestFixtures.PROPERTIES,
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                id,
                registry,
                models);
    }

    /** A pool as a model gets it from the database: dedicated, with no maximum. */
    private long ownPool() {
        var pool = new LlmCallPool();
        pool.setSlug("own-model-" + UUID.randomUUID());
        pool.setDisplayName("Own capacity");
        long id = pools.save(pool).getId();
        // These cases count from eight, which leaves room to fall and a reserve of two.
        jdbc.update("UPDATE llm_call_pool SET dedicated=TRUE,learned_limit=8 WHERE id=?", id);
        return id;
    }

    private long sharedPool() {
        var pool = new LlmCallPool();
        pool.setSlug("shared-" + UUID.randomUUID());
        pool.setDisplayName("Shared capacity");
        long id = pools.save(pool).getId();
        setLimit(id, 8);
        return id;
    }

    private void setLimit(long pool, int limit) {
        jdbc.update("UPDATE llm_call_pool SET learned_limit=? WHERE id=?", limit, pool);
    }

    private void setMaximum(long pool, int maximum) {
        jdbc.update("UPDATE llm_call_pool SET max_concurrent_calls=? WHERE id=?", maximum, pool);
    }

    private void clearCooldown(long pool) {
        jdbc.update("UPDATE llm_call_pool SET no_call_before=NULL WHERE id=?", pool);
    }

    private int limit(long pool) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT learned_limit FROM llm_call_pool WHERE id=?", Integer.class, pool));
    }

    private String slug(long pool) {
        return pools.findById(pool).orElseThrow().getSlug();
    }

    private long waiting(long pool) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING'", Long.class, pool));
    }

    private long activeOf(long pool, UUID source) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND source_id=? AND state='ACTIVE'",
                Long.class,
                pool,
                source));
    }

    private static HttpHeaders pause(int seconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(seconds));
        return headers;
    }

    private static LlmCallPermits.Permit admit(LlmCallPermits worker, long pool, AgentRunBand kind, long workspace) {
        return admit(worker, pool, kind, workspace, UUID.randomUUID());
    }

    private static LlmCallPermits.Permit admit(
            LlmCallPermits worker, long pool, AgentRunBand kind, long workspace, UUID source) {
        return Objects.requireNonNull(worker.acquire(
                pool,
                kind,
                new LlmCallPermits.Caller(workspace, source, 0, null),
                () -> false,
                Instant.now().plusSeconds(10)));
    }

    private static LlmCallPermits.@Nullable Permit acquire(
            LlmCallPermits worker, long pool, AgentRunBand kind, long workspace, UUID source, AtomicBoolean ended) {
        return worker.acquire(
                pool,
                kind,
                new LlmCallPermits.Caller(workspace, source, 0, null),
                ended::get,
                Instant.now().plusSeconds(60));
    }

    private static LlmCallPermits.@Nullable Permit probe(
            LlmCallPermits worker, long pool, AgentRunBand kind, long workspace, Duration wait) {
        var permit = worker.acquire(
                pool,
                kind,
                new LlmCallPermits.Caller(workspace, UUID.randomUUID(), 0, null),
                () -> false,
                Instant.now().plus(wait));
        if (permit != null) permit.close();
        return permit;
    }
}
