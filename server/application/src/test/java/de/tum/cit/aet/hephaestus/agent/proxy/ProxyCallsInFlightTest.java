package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProxyCallsInFlightTest extends BaseUnitTest {

    /** 2 × (3 + 1) + 4 at practice concurrency 3. */
    private static final int REVIEW_CALLS = 12;

    private final ProxyCallsInFlight calls = new ProxyCallsInFlight(3);

    @Test
    @DisplayName("a review attempt keeps two calls for each session and its script calls, and no more")
    void shouldRefuseTheCallPastTheCapWhenAReviewAttemptIsFull() {
        ProxyRouting.BilledAttempt review = attempt(UsageSourceType.REVIEW_JOB);

        List<ProxyCallsInFlight.Call> open = begin(review, REVIEW_CALLS);
        ProxyCallsInFlight.Call past = calls.begin(review);

        assertThat(open).noneMatch(ProxyCallsInFlight.Call::isOverCap);
        assertThat(past.isOverCap()).isTrue();
        assertThat(past.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a review's practice script calls count against the same cap as its chat calls")
    void shouldCountScriptCallsWhenTheyShareTheReviewAttempt() {
        ProxyRouting.BilledAttempt chat = attempt(UsageSourceType.REVIEW_JOB);
        begin(chat, REVIEW_CALLS - 1);
        ProxyRouting.BilledAttempt script = new ProxyRouting.BilledAttempt(
                UsageSourceType.PRACTICE_SCRIPT_DECISION, chat.sourceId(), chat.number(), BigDecimal.ZERO, "worker-1");

        assertThat(calls.begin(script).isOverCap()).isFalse();
        assertThat(calls.begin(script).isOverCap()).isTrue();
    }

    @Test
    @DisplayName("a Heph turn attempt keeps one call and one that a stop left closing")
    void shouldRefuseTheThirdCallWhenATurnAttemptHasTwo() {
        ProxyRouting.BilledAttempt turn = attempt(UsageSourceType.MENTOR_TURN);

        List<ProxyCallsInFlight.Call> open = begin(turn, ProxyCallsInFlight.TURN_CALLS);

        assertThat(open).noneMatch(ProxyCallsInFlight.Call::isOverCap);
        assertThat(calls.begin(turn).isOverCap()).isTrue();
    }

    @Test
    @DisplayName("a call that ends frees its place for the next call of its attempt")
    void shouldAdmitTheNextCallWhenACallEnds() {
        ProxyRouting.BilledAttempt turn = attempt(UsageSourceType.MENTOR_TURN);
        List<ProxyCallsInFlight.Call> open = begin(turn, ProxyCallsInFlight.TURN_CALLS);

        open.getFirst().close();

        assertThat(calls.begin(turn).isOverCap()).isFalse();
    }

    @Test
    @DisplayName("a refused call takes no place, and its end frees none")
    void shouldNotCountARefusedCallWhenItEnds() {
        ProxyRouting.BilledAttempt turn = attempt(UsageSourceType.MENTOR_TURN);
        begin(turn, ProxyCallsInFlight.TURN_CALLS);

        calls.begin(turn).close();

        assertThat(calls.begin(turn).isOverCap()).isTrue();
    }

    @Test
    @DisplayName("each attempt has its own cap, also for the next attempt of the same turn")
    void shouldCountEachAttemptApartWhenAnotherAttemptIsFull() {
        ProxyRouting.BilledAttempt first = attempt(UsageSourceType.MENTOR_TURN);
        ProxyRouting.BilledAttempt next = new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, first.sourceId(), first.number() + 1, BigDecimal.ZERO, "worker-1");
        begin(first, ProxyCallsInFlight.TURN_CALLS);

        assertThat(calls.begin(attempt(UsageSourceType.MENTOR_TURN)).isOverCap())
                .isFalse();
        assertThat(calls.begin(next).isOverCap()).isFalse();
    }

    @Test
    @DisplayName("the cap follows the configured practice concurrency")
    void shouldGrowTheReviewCapWhenPracticeConcurrencyGrows() {
        ProxyCallsInFlight wider = new ProxyCallsInFlight(6);
        ProxyRouting.BilledAttempt review = attempt(UsageSourceType.REVIEW_JOB);

        IntStream.range(0, 18)
                .forEach(index -> assertThat(wider.begin(review).isOverCap()).isFalse());

        assertThat(wider.begin(review).isOverCap()).isTrue();
    }

    @Test
    @DisplayName("a call of a sealed attempt is cancelled, not refused for the cap")
    void shouldCancelNotRefuseWhenTheAttemptIsSealed() {
        ProxyRouting.BilledAttempt turn = attempt(UsageSourceType.MENTOR_TURN);
        begin(turn, ProxyCallsInFlight.TURN_CALLS);
        calls.seal(turn.sourceId(), turn.number(), false);

        ProxyCallsInFlight.Call late = calls.begin(turn);

        assertThat(late.isCancelled()).isTrue();
        assertThat(late.isOverCap()).isFalse();
    }

    @Test
    @DisplayName("calls from many threads never pass the cap, and every place is free again once they end")
    void shouldNeverAdmitPastTheCapWhenManyThreadsBeginAndEndCalls() throws Exception {
        ProxyRouting.BilledAttempt review = attempt(UsageSourceType.REVIEW_JOB);
        AtomicInteger open = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(32)) {
            List<Future<?>> done = new ArrayList<>();
            for (int caller = 0; caller < 32; caller++) {
                done.add(threads.submit(() -> {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        try (ProxyCallsInFlight.Call call = calls.begin(review)) {
                            if (call.isOverCap()) continue;
                            admitted.incrementAndGet();
                            most.accumulateAndGet(open.incrementAndGet(), Math::max);
                            Thread.onSpinWait();
                            open.decrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> each : done) each.get(30, TimeUnit.SECONDS);
        }

        assertThat(most.get()).isLessThanOrEqualTo(REVIEW_CALLS);
        assertThat(admitted.get()).isPositive();
        assertThat(calls.awaitNone(review.sourceId(), review.number(), Duration.ZERO))
                .as("no place is left taken")
                .isTrue();
        assertThat(begin(review, REVIEW_CALLS)).noneMatch(ProxyCallsInFlight.Call::isOverCap);
    }

    private List<ProxyCallsInFlight.Call> begin(ProxyRouting.BilledAttempt attempt, int count) {
        List<ProxyCallsInFlight.Call> open = new ArrayList<>();
        for (int index = 0; index < count; index++) open.add(calls.begin(attempt));
        return open;
    }

    private static ProxyRouting.BilledAttempt attempt(UsageSourceType sourceType) {
        return new ProxyRouting.BilledAttempt(sourceType, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker-1");
    }
}
