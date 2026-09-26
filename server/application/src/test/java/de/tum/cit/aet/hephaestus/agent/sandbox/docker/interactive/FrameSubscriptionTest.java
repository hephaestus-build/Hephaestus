package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.IntNode;

class FrameSubscriptionTest extends BaseUnitTest {

    private static final Duration STALL = Duration.ofSeconds(5);

    @Test
    void deliversInOrder() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter dropped = reg.counter("test.drop");
        Counter errors = reg.counter("test.err");
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();

        FrameSubscription sub = new FrameSubscription(
                frame -> received.add(frame.intValue()), 16, STALL, dropped, errors, () -> {}, () -> {});
        sub.start();

        sub.offer(IntNode.valueOf(1));
        sub.offer(IntNode.valueOf(2));
        sub.offer(IntNode.valueOf(3));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received).containsExactly(1, 2, 3));
        assertThat(dropped.count()).isZero();
        sub.dispose();
    }

    @Test
    void shouldDeliverEveryFrameInOrderWhenASlowListenerFallsFarBehindQueueCapacity() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter cutOff = reg.counter("test.cutoff");
        AtomicInteger lost = new AtomicInteger();
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();
        int frames = 200;

        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    received.add(frame.intValue());
                },
                4,
                Duration.ofSeconds(5),
                cutOff,
                reg.counter("test.err"),
                lost::incrementAndGet,
                () -> {});
        sub.start();

        Thread producer = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < frames; i++) {
                sub.offer(IntNode.valueOf(i));
            }
        });
        producer.join(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == frames);
        assertThat(received)
                .containsExactlyElementsOf(IntStream.range(0, frames).boxed().toList());
        assertThat(cutOff.count()).isZero();
        assertThat(lost).hasValue(0);
        assertThat(sub.isDisposed()).isFalse();
        sub.dispose();
    }

    @Test
    void shouldCutOffAndSignalLossWhenListenerStallsPastTheTimeout() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter cutOff = reg.counter("test.cutoff");
        AtomicInteger lost = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();

        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    received.add(frame.intValue());
                },
                4,
                Duration.ofMillis(100),
                cutOff,
                reg.counter("test.err"),
                lost::incrementAndGet,
                () -> {});
        sub.start();

        Thread producer = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 20; i++) {
                sub.offer(IntNode.valueOf(i));
            }
        });
        producer.join(Duration.ofSeconds(5));

        assertThat(producer.isAlive())
                .as("the producer is released once the subscriber is cut off")
                .isFalse();
        assertThat(lost).hasValue(1);
        assertThat(cutOff.count()).isEqualTo(1.0);
        assertThat(sub.isDisposed()).isTrue();

        gate.countDown();
        // Only the frame already handed to the listener lands; nothing after the gap is delivered.
        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received).containsExactly(0));
    }

    @Test
    void shouldReleaseABlockedProducerWhenDisposed() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AtomicInteger lost = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                1,
                Duration.ofMinutes(1),
                reg.counter("test.cutoff"),
                reg.counter("test.err"),
                lost::incrementAndGet,
                () -> {});
        sub.start();
        Thread producer = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 5; i++) {
                sub.offer(IntNode.valueOf(i));
            }
        });
        await().atMost(Duration.ofSeconds(2)).until(() -> producer.getState() == Thread.State.TIMED_WAITING);

        sub.dispose();

        producer.join(Duration.ofSeconds(2));
        assertThat(producer.isAlive()).isFalse();
        assertThat(lost).as("a disposed subscriber was not cut off").hasValue(0);
        gate.countDown();
    }

    @Test
    void shouldCutOffInsteadOfSkippingAFrameWhenTheListenerThrows() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter errors = reg.counter("test.err");
        AtomicInteger lost = new AtomicInteger();
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();

        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    if (frame.intValue() == 2) {
                        throw new RuntimeException("boom");
                    }
                    received.add(frame.intValue());
                },
                8,
                STALL,
                reg.counter("test.cutoff"),
                errors,
                lost::incrementAndGet,
                () -> {});
        sub.start();

        sub.offer(IntNode.valueOf(1));
        sub.offer(IntNode.valueOf(2));
        sub.offer(IntNode.valueOf(3));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lost).hasValue(1));
        assertThat(received).containsExactly(1);
        assertThat(errors.count()).isEqualTo(1.0);
        assertThat(sub.isDisposed()).isTrue();
    }

    @Test
    void shouldReportLossButNoStallWhenABlockedProducerIsInterrupted() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter cutOff = reg.counter("test.cutoff");
        AtomicInteger lost = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                1,
                Duration.ofMinutes(1),
                cutOff,
                reg.counter("test.err"),
                lost::incrementAndGet,
                () -> {});
        sub.start();
        Thread producer = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 5; i++) {
                sub.offer(IntNode.valueOf(i));
            }
        });
        await().atMost(Duration.ofSeconds(2)).until(() -> producer.getState() == Thread.State.TIMED_WAITING);

        producer.interrupt();

        producer.join(Duration.ofSeconds(2));
        assertThat(producer.isAlive()).isFalse();
        assertThat(lost).as("the frame it was holding never arrives").hasValue(1);
        assertThat(cutOff.count()).as("not a stall").isZero();
        gate.countDown();
    }

    @Test
    void shouldDeliverQueuedFramesThenSignalLossWhenTheStreamEnds() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AtomicInteger lost = new AtomicInteger();
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    received.add(frame.intValue());
                },
                8,
                STALL,
                reg.counter("test.cutoff"),
                reg.counter("test.err"),
                lost::incrementAndGet,
                () -> {});
        sub.start();
        sub.offer(IntNode.valueOf(1));
        sub.offer(IntNode.valueOf(2));

        sub.endOfStream();
        sub.offer(IntNode.valueOf(3));
        gate.countDown();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lost).hasValue(1));
        assertThat(received)
                .as("frames queued before the end still arrive; none after it")
                .containsExactly(1, 2);
    }

    @Test
    void shouldNotSignalLossWhenTheOwnerDisposesBeforeTheStreamEnds() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AtomicInteger lost = new AtomicInteger();
        FrameSubscription sub = new FrameSubscription(
                frame -> {},
                8,
                STALL,
                reg.counter("test.cutoff"),
                reg.counter("test.err"),
                lost::incrementAndGet,
                () -> {});
        sub.start();

        sub.dispose();
        sub.endOfStream();

        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(lost).hasValue(0));
    }

    @Test
    void disposeIdempotent() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AtomicInteger onDisposeFires = new AtomicInteger();
        FrameSubscription sub = new FrameSubscription(
                frame -> {},
                4,
                STALL,
                reg.counter("test.drop"),
                reg.counter("test.err"),
                () -> {},
                onDisposeFires::incrementAndGet);
        sub.start();
        assertThat(sub.isDisposed()).isFalse();
        sub.dispose();
        sub.dispose();
        sub.dispose();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(sub.isDisposed()).isTrue();
            assertThat(onDisposeFires).hasValue(1);
        });
    }

    @Test
    void offerAfterDisposeNoOp() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        List<Integer> received = new CopyOnWriteArrayList<>();
        FrameSubscription sub = new FrameSubscription(
                frame -> received.add(frame.intValue()),
                4,
                STALL,
                reg.counter("test.drop"),
                reg.counter("test.err"),
                () -> {},
                () -> {});
        sub.start();
        sub.dispose();
        sub.offer(IntNode.valueOf(99));
        sub.offer(IntNode.valueOf(100));
        assertThat(received).doesNotContain(99, 100);
    }
}
