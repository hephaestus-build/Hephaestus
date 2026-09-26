package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.gateway.GatewayInteractiveChannel;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.ObjectMapper;

/** Fan-out from the runner's frame stream to subscribers, through the real pump. */
class DockerAttachedSandboxAdapterTest extends BaseUnitTest {

    private static final Duration STALL = Duration.ofMillis(300);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private GatewayInteractiveChannel channel;
    private DockerAttachedSandboxAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        channel = new GatewayInteractiveChannel(1 << 20);
        adapter = new DockerAttachedSandboxAdapter(
                UUID.randomUUID(),
                "1",
                "2",
                "container",
                "network",
                new InteractiveSandboxRuntimeKey(
                        "image",
                        List.of(),
                        Map.of(),
                        false,
                        null,
                        ResourceLimits.DEFAULT,
                        SecurityProfile.DEFAULT,
                        Map.of(),
                        null),
                channel,
                new ObjectMapper(),
                new FrameRingBuffer(64, registry.counter("test.ring")),
                4,
                STALL,
                5_000,
                16,
                1 << 20,
                Duration.ZERO,
                new InteractiveSandboxMetrics(registry),
                new DockerAttachedSandboxAdapter.LifecycleOps() {
                    @Override
                    public void stopContainer(String containerId, int graceSeconds) {}

                    @Override
                    public void removeContainer(String containerId) {}

                    @Override
                    public void disconnectAndRemoveNetwork(String networkId) {}
                },
                Runnable::run,
                closed -> {});
        adapter.start();
    }

    @AfterEach
    void tearDown() {
        channel.finish(0);
        adapter.close(Duration.ZERO);
    }

    @Test
    void shouldDeliverEveryFrameToALiveSubscriberWithinOneStallWhenSeveralPeersStall() throws Exception {
        CountDownLatch stalledGate = new CountDownLatch(1);
        AtomicInteger stalledLost = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            adapter.subscribeFromNow(
                    frame -> {
                        try {
                            stalledGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    stalledLost::incrementAndGet);
        }
        CopyOnWriteArrayList<Integer> live = new CopyOnWriteArrayList<>();
        AtomicInteger liveLost = new AtomicInteger();
        adapter.subscribeFromNow(frame -> live.add(frame.get("n").asInt()), liveLost::incrementAndGet);

        for (int i = 0; i < 20; i++) {
            channel.receive(new TextMessage("{\"n\":" + i + "}"));
        }

        // The stalled peers share one deadline, so together they hold the live subscriber up once.
        await().atMost(STALL.multipliedBy(3))
                .untilAsserted(() -> assertThat(live)
                        .containsExactlyElementsOf(
                                IntStream.range(0, 20).boxed().toList()));
        assertThat(stalledLost).hasValue(3);
        assertThat(liveLost).hasValue(0);
        stalledGate.countDown();
    }

    @Test
    void shouldCutSubscribersOffWhenARunnerLineCannotBeRead() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger lost = new AtomicInteger();
        adapter.subscribeFromNow(frame -> received.add(frame.get("type").asString()), lost::incrementAndGet);

        channel.receive(new TextMessage("{\"type\":\"message_update\"}"));
        channel.receive(new TextMessage("{\"type\":\"message_up"));
        channel.receive(new TextMessage("{\"type\":\"agent_end\"}"));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lost).hasValue(1));
        // A frame still queued ahead of the gap may be discarded with it; nothing after the gap arrives.
        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received).isSubsetOf("message_update"));
    }

    @Test
    void shouldReportLossPromptlyWhenTheRunnerExitsMidTurn() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger lost = new AtomicInteger();
        adapter.subscribeFromNow(frame -> received.add(frame.get("type").asString()), lost::incrementAndGet);

        channel.receive(new TextMessage("{\"type\":\"message_update\"}"));
        channel.finish(1);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lost).hasValue(1));
        assertThat(received).containsExactly("message_update");
    }

    @Test
    void shouldStillDeliverATerminalFrameWrittenJustBeforeTheRunnerExits() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        adapter.subscribeFromNow(frame -> received.add(frame.get("type").asString()), () -> {});

        channel.receive(new TextMessage("{\"type\":\"message_update\"}"));
        channel.receive(new TextMessage("{\"type\":\"agent_end\"}"));
        channel.finish(0);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received).containsExactly("message_update", "agent_end"));
    }

    @Test
    void shouldReportLossToASubscriberThatArrivesAfterTheSandboxClosed() {
        channel.finish(0);
        adapter.close(Duration.ZERO);
        AtomicInteger lost = new AtomicInteger();

        adapter.subscribeFromNow(frame -> {}, lost::incrementAndGet);

        assertThat(lost).hasValue(1);
    }
}
