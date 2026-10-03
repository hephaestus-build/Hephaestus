package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

class JsonlStdinWriterTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record TestCounters(
            Counter queueFull, Counter writeTimeout, Counter brokenPipe, Counter closed, Counter sendBytes) {
        static TestCounters of(SimpleMeterRegistry reg) {
            return new TestCounters(
                    reg.counter("test.queue.full"),
                    reg.counter("test.write.timeout"),
                    reg.counter("test.broken.pipe"),
                    reg.counter("test.closed"),
                    reg.counter("test.send.bytes"));
        }
    }

    @Test
    @DisplayName("send() writes JSON + newline and resolves quickly under normal conditions")
    void sendsAndAcks() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TestCounters c = TestCounters.of(reg);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicInteger terminalFires = new AtomicInteger();

        JsonlStdinWriter writer = new JsonlStdinWriter(
                UUID.randomUUID(),
                sink,
                MAPPER,
                16,
                2_000L,
                c.queueFull(),
                c.writeTimeout(),
                c.brokenPipe(),
                c.closed(),
                c.sendBytes(),
                terminalFires::incrementAndGet,
                Map.of());
        writer.start();

        writer.send(StringNode.valueOf("hello"));
        writer.send(IntNode.valueOf(42));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(sink.toString(StandardCharsets.UTF_8))
                        .contains("\"hello\"\n")
                        .contains("42\n"));
        assertThat(c.sendBytes().count()).isGreaterThan(0);
        assertThat(terminalFires).hasValue(0);

        writer.close();
    }

    @Test
    void queueFullRejects() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TestCounters c = TestCounters.of(reg);
        // Latch signals the writer has popped envelope #1; we can then test queue-full deterministically.
        CountDownLatch writerEnteredWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutputStream blocking = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                writerEnteredWrite.countDown();
                try {
                    release.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                write(0);
            }
        };

        AtomicInteger terminalFires = new AtomicInteger();
        JsonlStdinWriter writer = new JsonlStdinWriter(
                UUID.randomUUID(),
                blocking,
                MAPPER,
                2,
                500L,
                c.queueFull(),
                c.writeTimeout(),
                c.brokenPipe(),
                c.closed(),
                c.sendBytes(),
                terminalFires::incrementAndGet,
                Map.of());
        writer.start();

        Thread firstSender = new Thread(() -> {
            try {
                writer.send(StringNode.valueOf("first"));
            } catch (InteractiveSandboxException ignored) {
                // The writer is terminated under this blocked sender; how its send ends is not under test.
            }
        });
        firstSender.start();
        assertThat(writerEnteredWrite.await(2, TimeUnit.SECONDS))
                .as("writer thread must enter the blocking write before we test queue-full")
                .isTrue();

        // Queue capacity = 2. Two more queued sends; the fourth must reject — queue_full or timeout.
        Thread secondSender = new Thread(() -> {
            try {
                writer.send(StringNode.valueOf("second"));
            } catch (InteractiveSandboxException ignored) {
                // The writer is terminated under this blocked sender; how its send ends is not under test.
            }
        });
        Thread thirdSender = new Thread(() -> {
            try {
                writer.send(StringNode.valueOf("third"));
            } catch (InteractiveSandboxException ignored) {
                // The writer is terminated under this blocked sender; how its send ends is not under test.
            }
        });
        secondSender.start();
        thirdSender.start();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try {
                writer.send(StringNode.valueOf("fourth"));
            } catch (InteractiveSandboxException expected) {
                assertThat(c.queueFull().count() + c.writeTimeout().count()).isGreaterThan(0.0);
                return;
            }
            throw new AssertionError("Expected InteractiveSandboxException on overflow / timeout");
        });

        release.countDown();
        writer.close();
        firstSender.interrupt();
        secondSender.interrupt();
        thirdSender.interrupt();
    }

    @Test
    @DisplayName("send() after close throws CLOSED")
    void closedRejects() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TestCounters c = TestCounters.of(reg);
        AtomicInteger terminalFires = new AtomicInteger();
        JsonlStdinWriter writer = new JsonlStdinWriter(
                UUID.randomUUID(),
                new ByteArrayOutputStream(),
                MAPPER,
                8,
                500L,
                c.queueFull(),
                c.writeTimeout(),
                c.brokenPipe(),
                c.closed(),
                c.sendBytes(),
                terminalFires::incrementAndGet,
                Map.of());
        writer.start();
        writer.close();
        assertThatThrownBy(() -> writer.send(StringNode.valueOf("x")))
                .isInstanceOf(InteractiveSandboxException.class)
                .hasMessageContaining("Session is closed");
        assertThat(c.closed().count()).isEqualTo(1.0);
    }

    @Test
    void writeStalledDetection() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TestCounters c = TestCounters.of(reg);
        CountDownLatch release = new CountDownLatch(1);
        OutputStream blocking = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                try {
                    release.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                write(0);
            }
        };
        AtomicInteger terminalFires = new AtomicInteger();
        JsonlStdinWriter writer = new JsonlStdinWriter(
                UUID.randomUUID(),
                blocking,
                MAPPER,
                8,
                50L,
                c.queueFull(),
                c.writeTimeout(),
                c.brokenPipe(),
                c.closed(),
                c.sendBytes(),
                terminalFires::incrementAndGet,
                Map.of());
        writer.start();
        Thread t = new Thread(() -> {
            try {
                writer.send(StringNode.valueOf("stalled"));
            } catch (InteractiveSandboxException ignored) {
                // The writer is terminated under this blocked sender; how its send ends is not under test.
            }
        });
        t.start();
        TimeUnit.MILLISECONDS.sleep(150);
        long now = System.nanoTime();
        assertThat(writer.writeStalled(now)).isTrue();
        writer.onWriteTimeout();
        assertThat(writer.isTerminated()).isTrue();
        assertThat(terminalFires).hasValueGreaterThanOrEqualTo(1);
        release.countDown();
        t.interrupt();
    }

    @Test
    void encodesAsSingleLine() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TestCounters c = TestCounters.of(reg);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        JsonlStdinWriter writer = new JsonlStdinWriter(
                UUID.randomUUID(),
                sink,
                MAPPER,
                8,
                2_000L,
                c.queueFull(),
                c.writeTimeout(),
                c.brokenPipe(),
                c.closed(),
                c.sendBytes(),
                () -> {},
                Map.of());
        writer.start();
        // Real LF in the value must be escaped, not emitted raw; only the trailing \n is real.
        JsonNode node = MAPPER.createObjectNode().put("payload", "a\nb");
        writer.send(node);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            String actual = sink.toString(StandardCharsets.UTF_8);
            assertThat(actual).contains("\"payload\":\"a\\nb\"}");
            assertThat(actual).endsWith("\n");
        });
        String result = sink.toString(StandardCharsets.UTF_8);
        long newlineCount = result.chars().filter(ch -> ch == '\n').count();
        assertThat(newlineCount).isEqualTo(1L);
        writer.close();
    }
}
