package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
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
import java.util.stream.Stream;
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
    @EnumSource(
            value = AgentRunBand.class,
            names = {"INTERACTIVE", "BATCH"})
    void shouldGrantAReviewOrTurnOfAHigherBandWhenProductFeedbackWasQueuedFirst(AgentRunBand band) throws Exception {
        long pool = pool(1);
        var occupied = admit(first, pool, AgentRunBand.BATCH, 1L);
        var feedback = submit(first, pool, AgentRunBand.BACKGROUND, null);
        awaitWaiting(pool, null);
        var priority = submit(second, pool, band, 2L);
        awaitWaiting(pool, 2L);

        occupied.close();

        var priorityGrant = priority.get(10, TimeUnit.SECONDS);
        assertThat(priorityGrant.isRevoked()).isFalse();
        assertThat(feedback.isDone()).isFalse();
        priorityGrant.close();
        assertThat(feedback.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldGrantProductFeedbackBeforeALaterBackgroundReviewCall() throws Exception {
        long pool = pool(1);
        var occupied = admit(first, pool, AgentRunBand.BATCH, 1L);
        var feedback = submit(first, pool, AgentRunBand.BACKGROUND, null);
        awaitWaiting(pool, null);
        var review = submit(second, pool, AgentRunBand.BACKGROUND, 2L);
        awaitWaiting(pool, 2L);

        occupied.close();

        var feedbackGrant = feedback.get(10, TimeUnit.SECONDS);
        assertThat(feedbackGrant.isRevoked()).isFalse();
        assertThat(review.isDone()).isFalse();
        feedbackGrant.close();
        assertThat(review.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldAdmitFeedbackWhenHigherDemandTargetsAnotherEndpoint() throws Exception {
        long busyPool = pool(1);
        long idlePool = pool(1);
        admit(first, busyPool, AgentRunBand.BATCH, 1L);
        var review = submit(second, busyPool, AgentRunBand.BATCH, 2L);
        awaitWaiting(busyPool, 2L);

        assertThat(admit(first, idlePool, AgentRunBand.BACKGROUND, null).isRevoked())
                .isFalse();
        assertThat(review.isDone()).isFalse();
    }

    @Test
    void shouldLeaveInteractiveCapacityWhenFeedbackWaits() throws Exception {
        long pool = pool(2);
        var occupied = admit(first, pool, AgentRunBand.BATCH, 1L);
        var feedback = submit(first, pool, AgentRunBand.BACKGROUND, null);
        awaitWaiting(pool, null);

        var turn = admit(second, pool, AgentRunBand.INTERACTIVE, 2L);
        assertThat(feedback.isDone()).isFalse();
        occupied.close();
        assertThat(feedback.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
        assertThat(turn.isRevoked()).isFalse();
    }

    @Test
    void shouldRefuseASecondDraftAtOnceWhileOneIsInFlight() {
        long pool = pool(3);
        var feedback = admit(first, pool, AgentRunBand.BACKGROUND, null);

        assertThat(tryAdmit(second, pool, AgentRunBand.BACKGROUND, null)).isNull();
        assertThat(waitingDrafts(pool)).isZero();
        var review = admit(second, pool, AgentRunBand.BATCH, 2L);
        assertThat(review.isRevoked()).isFalse();
        feedback.close();
        assertThat(tryAdmit(second, pool, AgentRunBand.BACKGROUND, null)).isNotNull();
    }

    @Test
    void shouldRetainFeedbackUntilClosureWhenAReviewCallArrives() throws Exception {
        long pool = pool(1);
        var feedback = admit(first, pool, AgentRunBand.BACKGROUND, null);
        var review = submit(second, pool, AgentRunBand.BATCH, 2L);
        awaitWaiting(pool, 2L);

        assertThat(feedback.isRevoked()).isFalse();
        assertThat(review.isDone()).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='ACTIVE' AND workspace_id IS NULL",
                        Long.class,
                        pool))
                .isEqualTo(1L);
        feedback.close();
        assertThat(review.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldGrantOnlyOneFeedbackCallWhenWorkersAcquireTogether() throws Exception {
        long pool = pool(4);
        var start = new CyclicBarrier(2);
        var a = calls.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return tryAdmit(first, pool, AgentRunBand.BACKGROUND, null);
        });
        var b = calls.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return tryAdmit(second, pool, AgentRunBand.BACKGROUND, null);
        });

        var granted = Stream.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                .filter(Objects::nonNull)
                .toList();

        assertThat(granted).hasSize(1);
        assertThat(waitingDrafts(pool)).isZero();
    }

    /**
     * Twenty drafts that arrive at once on a busy pool all wait until one gets the grant; the others then give up
     * within a poll, so their callers, outbox slots in production, are free again for the other kinds of action.
     */
    @Test
    void shouldReleaseTheOtherDraftsWhenOneOfTwentyIsGranted() throws Exception {
        long pool = pool(1);
        var occupied = admit(first, pool, AgentRunBand.BATCH, 1L);
        var drafts = new ArrayList<Future<LlmCallPermits.@Nullable Permit>>();
        for (int n = 0; n < 20; n++) {
            LlmCallPermits worker = n % 2 == 0 ? first : second;
            drafts.add(calls.submit(() -> tryAdmit(worker, pool, AgentRunBand.BACKGROUND, null)));
        }
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> waitingDrafts(pool) == 20);

        occupied.close();

        var ended = new ArrayList<LlmCallPermits.@Nullable Permit>();
        for (var draft : drafts) ended.add(draft.get(10, TimeUnit.SECONDS));
        assertThat(ended).filteredOn(Objects::nonNull).hasSize(1);
        assertThat(waitingDrafts(pool)).isZero();
    }

    @Test
    void shouldWaitForSharedCooldownWhenFeedbackRequestsAGrant() throws Exception {
        long pool = pool(2);
        var cooling = pools.findById(pool).orElseThrow();
        cooling.setNoCallBefore(Instant.now().plusSeconds(30));
        pools.saveAndFlush(cooling);
        var feedback = submit(first, pool, AgentRunBand.BACKGROUND, null);
        awaitWaiting(pool, null);
        assertThat(context.getBean(LlmCallPermitRepository.class).countActive(pool))
                .isZero();
        cooling.setNoCallBefore(null);
        pools.saveAndFlush(cooling);
        assertThat(feedback.get(10, TimeUnit.SECONDS).isRevoked()).isFalse();
    }

    @Test
    void shouldDebitSharedRateBudgetWhenFeedbackRetries() {
        long pool = pool(3);
        var budget = pools.findById(pool).orElseThrow();
        // Calls that are not interactive get two thirds of these rates.
        budget.setRequestsPerMinute(5);
        budget.setTokensPerMinute(500L);
        budget.setTokensPerCall(100L);
        pools.saveAndFlush(budget);
        admit(first, pool, AgentRunBand.BATCH, 2L).close();
        var feedback = admit(first, pool, AgentRunBand.BACKGROUND, null);
        assertThat(first.beforeRetry(feedback, ended::get, Instant.now().plusSeconds(1)))
                .isTrue();
        feedback.close();

        assertThat(first.acquire(
                        pool,
                        AgentRunBand.BACKGROUND,
                        new LlmCallPermits.Caller(null, UUID.randomUUID(), 0, null, null),
                        ended::get,
                        Instant.now().plusMillis(25)))
                .isNull();
        var rates = context.getBean(LlmCallPermitRepository.class).rates(pool);
        assertThat(rates.getRequests()).isEqualTo(3);
        assertThat(rates.getTokens()).isEqualTo(300);
    }

    /** A pool that has already learned its maximum, so that the maximum decides in these tests. */
    private long pool(int maximum) {
        var pool = new LlmCallPool();
        pool.setSlug("pf-" + UUID.randomUUID());
        pool.setDisplayName("Product feedback contention");
        pool.setMaxConcurrentCalls(maximum);
        long id = pools.saveAndFlush(pool).getId();
        jdbc.update("UPDATE llm_call_pool SET learned_limit=? WHERE id=?", maximum, id);
        return id;
    }

    private Future<LlmCallPermits.Permit> submit(
            LlmCallPermits worker, long pool, AgentRunBand kind, @Nullable Long workspace) {
        var request = calls.submit(() -> admit(worker, pool, kind, workspace));
        requests.add(request);
        return request;
    }

    private LlmCallPermits.Permit admit(LlmCallPermits worker, long pool, AgentRunBand kind, @Nullable Long workspace) {
        var permit = Objects.requireNonNull(worker.acquire(
                pool,
                kind,
                new LlmCallPermits.Caller(workspace, UUID.randomUUID(), 0, null, null),
                ended::get,
                Instant.now().plusSeconds(30)));
        granted.add(permit);
        return permit;
    }

    /** Like {@link #admit}, but a draft may be refused: empty when no grant came. */
    private LlmCallPermits.@Nullable Permit tryAdmit(
            LlmCallPermits worker, long pool, AgentRunBand kind, @Nullable Long workspace) {
        var permit = worker.acquire(
                pool,
                kind,
                new LlmCallPermits.Caller(workspace, UUID.randomUUID(), 0, null),
                ended::get,
                Instant.now().plusSeconds(30));
        if (permit != null) granted.add(permit);
        return permit;
    }

    private long waitingDrafts(long pool) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING' AND workspace_id IS NULL",
                Long.class,
                pool));
    }

    /** Product feedback is the only call without a workspace. */
    private void awaitWaiting(long pool, @Nullable Long workspace) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING'"
                                        + " AND workspace_id IS NOT DISTINCT FROM ?",
                                Long.class,
                                pool,
                                workspace))
                        .isEqualTo(1L));
    }
}
