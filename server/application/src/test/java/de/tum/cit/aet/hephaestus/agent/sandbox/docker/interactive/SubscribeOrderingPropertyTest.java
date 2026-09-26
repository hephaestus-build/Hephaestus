package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.IntNode;

/** Snapshot replay must strictly precede live frames for a subscriber that races the pump. */
class SubscribeOrderingPropertyTest extends BaseUnitTest {

    @Test
    void snapshotThenLive() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        FrameRingBuffer ring = new FrameRingBuffer(1024, reg.counter("test.dropped"));
        Object lock = new Object();
        CopyOnWriteArrayList<FrameSubscription> subs = new CopyOnWriteArrayList<>();

        for (int i = 1; i <= 100; i++) {
            ring.offer(IntNode.valueOf(i));
        }

        // CopyOnWriteArrayList: dispatcher thread writes, main thread reads at end without re-sync.
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch listenerCalledOnce = new CountDownLatch(1);
        FrameSubscription[] holder = new FrameSubscription[1];
        FrameSubscription sub = new FrameSubscription(
                frame -> {
                    received.add(frame.intValue());
                    listenerCalledOnce.countDown();
                },
                4096,
                Duration.ofSeconds(5),
                reg.counter("test.sub.cutoff"),
                reg.counter("test.sub.error"),
                () -> {},
                () -> {});
        holder[0] = sub;

        // Producer mimics the pump's fan-out path under the same lock as subscribe.
        Thread producer = Thread.ofVirtual().start(() -> {
            for (int i = 101; i <= 1100; i++) {
                synchronized (lock) {
                    ring.offer(IntNode.valueOf(i));
                    for (FrameSubscription s : subs) {
                        s.offer(IntNode.valueOf(i));
                    }
                }
            }
        });

        // Subscribe mirrors the production fix: under the same lock, snapshot then add.
        synchronized (lock) {
            List<JsonNode> snapshot = ring.snapshotSince(-1L);
            subs.add(sub);
            sub.start();
            for (JsonNode frame : snapshot) {
                sub.offer(frame);
            }
        }
        producer.join();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received.size()).isGreaterThan(1000));

        // Strict monotonicity: a live frame ahead of any snapshot frame would dip the sequence.
        for (int i = 1; i < received.size(); i++) {
            assertThat(received.get(i)).as("frame %d not monotonic", i).isGreaterThan(received.get(i - 1));
        }
        listenerCalledOnce.await();
        sub.dispose();
    }
}
