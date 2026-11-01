package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class LlmProductFeedbackAdmissionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmCallPoolRepository pools;

    @Autowired
    private JdbcTemplate jdbc;

    private LlmCallPermits first;
    private LlmCallPermits second;
    private ExecutorService calls;
    private String secondWorkerId;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final Queue<LlmCallPermits.Permit> granted = new ConcurrentLinkedQueue<>();
    private final List<Future<?>> requests = new ArrayList<>();

    @BeforeEach
    void startWorkers() {
        first = LlmCallPermitsTestFixtures.worker(context, "pf-first-" + UUID.randomUUID());
        secondWorkerId = "pf-second-" + UUID.randomUUID();
        second = LlmCallPermitsTestFixtures.worker(context, secondWorkerId);
        first.start();
        second.start();
        calls = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void stopWorkers() {
        ended.set(true);
        requests.forEach(request -> request.cancel(true));
        calls.close();
        granted.forEach(LlmCallPermits.Permit::close);
        first.stop();
        second.stop();
    }

    @ParameterizedTest
    @EnumSource(value = LlmCallClass.class, names = "PRODUCT_FEEDBACK", mode = EnumSource.Mode.EXCLUDE)
    void shouldGrantHigherClassesWhenProductFeedbackWasQueuedFirst(LlmCallClass higher) throws Exception {
        long pool = pool(1, 0);
        var occupied = admit(first, pool, LlmCallClass.EVENT, 1L);
        var feedback = submit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        awaitWaiting(pool, LlmCallClass.PRODUCT_FEEDBACK);
        var priority = submit(second, pool, higher, 2L);
        awaitWaiting(pool, higher);

        occupied.close();

        var priorityGrant = priority.get(10, TimeUnit.SECONDS);
        assertThat(priorityGrant.isRevoked()).isFalse();
        assertThat(feedback.isDone()).isFalse();
        priorityGrant.close();
        assertThat(feedback.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldAdmitFeedbackWhenHigherDemandTargetsAnotherEndpoint() throws Exception {
        long busyPool = pool(1, 0);
        long idlePool = pool(1, 0);
        admit(first, busyPool, LlmCallClass.EVENT, 1L);
        var review = submit(second, busyPool, LlmCallClass.REQUEST, 2L);
        awaitWaiting(busyPool, LlmCallClass.REQUEST);

        assertThat(admit(first, idlePool, LlmCallClass.PRODUCT_FEEDBACK, null).isRevoked())
                .isFalse();
        assertThat(review.isDone()).isFalse();
    }

    @Test
    void shouldLeaveInteractiveCapacityWhenFeedbackWaits() throws Exception {
        long pool = pool(2, 1);
        var occupied = admit(first, pool, LlmCallClass.EVENT, 1L);
        var feedback = submit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        awaitWaiting(pool, LlmCallClass.PRODUCT_FEEDBACK);

        var turn = admit(second, pool, LlmCallClass.TURN, 2L);
        assertThat(feedback.isDone()).isFalse();
        occupied.close();
        assertThat(feedback.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
        assertThat(turn.isRevoked()).isFalse();
    }

    @Test
    void shouldLimitFeedbackToOneCallWhenWorkersShareAPool() throws Exception {
        long pool = pool(3, 1);
        var feedback = admit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        var another = submit(second, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        awaitWaiting(pool, LlmCallClass.PRODUCT_FEEDBACK);

        var review = admit(second, pool, LlmCallClass.REQUEST, 2L);
        assertThat(another.isDone()).isFalse();
        assertThat(feedback.isRevoked()).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT workspace_id FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE' AND call_class='PRODUCT_FEEDBACK'",
                        Long.class,
                        pool))
                .isNull();
        feedback.close();
        assertThat(another.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
        assertThat(review.isRevoked()).isFalse();
    }

    @Test
    void shouldRetainFeedbackUntilClosureWhenAHigherClassArrives() throws Exception {
        long pool = pool(1, 0);
        var feedback = admit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        var review = submit(second, pool, LlmCallClass.REQUEST, 2L);
        awaitWaiting(pool, LlmCallClass.REQUEST);

        assertThat(feedback.isRevoked()).isFalse();
        assertThat(review.isDone()).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE' AND call_class='PRODUCT_FEEDBACK'",
                        Long.class,
                        pool))
                .isEqualTo(1L);
        feedback.close();
        assertThat(review.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldGrantOnlyOneFeedbackCallWhenWorkersAcquireTogether() throws Exception {
        long pool = pool(4, 1);
        var start = new CyclicBarrier(2);
        var a = calls.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return admit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        });
        requests.add(a);
        var b = calls.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return admit(second, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        });
        requests.add(b);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE' AND call_class='PRODUCT_FEEDBACK'",
                            Long.class,
                            pool))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING' AND call_class='PRODUCT_FEEDBACK'",
                            Long.class,
                            pool))
                    .isEqualTo(1L);
            assertThat(a.isDone() ^ b.isDone()).isTrue();
        });

        var winner = a.isDone() ? a.get(10, TimeUnit.SECONDS) : b.get(10, TimeUnit.SECONDS);
        winner.close();
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE' AND call_class='PRODUCT_FEEDBACK'",
                        Long.class,
                        pool))
                .isEqualTo(1L);
    }

    @Test
    void shouldWaitForSharedCooldownWhenFeedbackRequestsAGrant() throws Exception {
        long pool = pool(2, 0);
        var cooling = pools.findById(pool).orElseThrow();
        cooling.setNoCallBefore(Instant.now().plusSeconds(30));
        pools.saveAndFlush(cooling);
        var feedback = submit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        awaitWaiting(pool, LlmCallClass.PRODUCT_FEEDBACK);
        assertThat(context.getBean(LlmCallPermitRepository.class).countActive(pool))
                .isZero();
        cooling.setNoCallBefore(null);
        pools.saveAndFlush(cooling);
        assertThat(feedback.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldDebitSharedRateBudgetWhenFeedbackRetries() {
        long pool = pool(3, 0);
        var budget = pools.findById(pool).orElseThrow();
        budget.setRequestsPerMinute(3);
        budget.setTokensPerMinute(300L);
        budget.setTokensPerCall(100L);
        pools.saveAndFlush(budget);
        admit(first, pool, LlmCallClass.REQUEST, 2L).close();
        var feedback = admit(first, pool, LlmCallClass.PRODUCT_FEEDBACK, null);
        assertThat(first.beforeRetry(feedback, ended::get, Instant.now().plusSeconds(1)))
                .isTrue();
        feedback.close();

        assertThat(first.acquire(
                        pool,
                        LlmCallClass.PRODUCT_FEEDBACK,
                        new LlmCallPermits.Caller(null, UUID.randomUUID(), 0, null),
                        ended::get,
                        Instant.now().plusMillis(25)))
                .isNull();
        var rates = context.getBean(LlmCallPermitRepository.class).rates(pool);
        assertThat(rates.getRequests()).isEqualTo(3);
        assertThat(rates.getTokens()).isEqualTo(300);
    }

    private long pool(int maximum, int reserve) {
        var pool = new LlmCallPool();
        pool.setSlug("pf-" + UUID.randomUUID());
        pool.setDisplayName("Product feedback contention");
        pool.setMaxConcurrentCalls(maximum);
        pool.setInteractiveReserve(reserve);
        return pools.saveAndFlush(pool).getId();
    }

    private Future<LlmCallPermits.Permit> submit(
            LlmCallPermits worker, long pool, LlmCallClass kind, @Nullable Long workspace) {
        var request = calls.submit(() -> admit(worker, pool, kind, workspace));
        requests.add(request);
        return request;
    }

    private LlmCallPermits.Permit admit(LlmCallPermits worker, long pool, LlmCallClass kind, @Nullable Long workspace) {
        var permit = Objects.requireNonNull(worker.acquire(
                pool,
                kind,
                new LlmCallPermits.Caller(workspace, UUID.randomUUID(), 0, null),
                ended::get,
                Instant.now().plusSeconds(30)));
        granted.add(permit);
        return permit;
    }

    private void awaitWaiting(long pool, LlmCallClass kind) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND call_class=? AND state='WAITING'",
                                Long.class,
                                pool,
                                kind.name()))
                        .isEqualTo(1L));
    }
}
