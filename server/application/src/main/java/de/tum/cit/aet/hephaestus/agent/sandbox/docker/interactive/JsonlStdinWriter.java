package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import io.micrometer.core.instrument.Counter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serialised JSONL writer with bounded queueing and watchdog-driven recovery. {@link #send}
 * rejects immediately when the queue is full — the only honest backpressure signal (a timeout
 * alone allows unbounded growth). A thread parked in {@code OutputStream.write()} on a kernel
 * pipe cannot be interrupted on Linux (see <a href="https://bugs.openjdk.org/browse/JDK-4514257">
 * JDK-4514257</a>), so the {@link StdinWriteWatchdog} polls {@link #writeStartedNanos} and
 * delegates to the owning session to {@code destroyForcibly} the exec subprocess.
 */
final class JsonlStdinWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonlStdinWriter.class);

    private static final long IDLE = -1L;

    private final UUID sessionId;
    private final OutputStream stdin;
    private final ObjectMapper mapper;
    private final long stdinWriteTimeoutMs;
    private final BlockingQueue<WriteEnvelope> queue;
    private final int queueCapacity;
    private final Map<String, String> mdcSnapshot;

    private final Counter rejectedQueueFull;
    private final Counter rejectedWriteTimeout;
    private final Counter rejectedBrokenPipe;
    private final Counter rejectedClosed;
    private final Counter framesBytesIn;

    /** Wall-clock nanos at which the current in-flight write started, or {@link #IDLE}. */
    private final AtomicLong writeStartedNanos = new AtomicLong(IDLE);

    private volatile boolean terminated = false;
    private volatile @Nullable Thread writerThread;

    private final Runnable onTerminalFailure;

    JsonlStdinWriter(
            UUID sessionId,
            OutputStream stdin,
            ObjectMapper mapper,
            int queueCapacity,
            long stdinWriteTimeoutMs,
            Counter rejectedQueueFull,
            Counter rejectedWriteTimeout,
            Counter rejectedBrokenPipe,
            Counter rejectedClosed,
            Counter framesBytesIn,
            Runnable onTerminalFailure,
            Map<String, String> mdcSnapshot) {
        this.sessionId = sessionId;
        this.stdin = stdin;
        this.mapper = mapper;
        this.queueCapacity = queueCapacity;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.stdinWriteTimeoutMs = stdinWriteTimeoutMs;
        this.rejectedQueueFull = rejectedQueueFull;
        this.rejectedWriteTimeout = rejectedWriteTimeout;
        this.rejectedBrokenPipe = rejectedBrokenPipe;
        this.rejectedClosed = rejectedClosed;
        this.framesBytesIn = framesBytesIn;
        this.onTerminalFailure = onTerminalFailure;
        this.mdcSnapshot = mdcSnapshot;
    }

    void start() {
        this.writerThread = Thread.ofVirtual()
                .name("mentor-writer-" + sessionId)
                .uncaughtExceptionHandler((t, ex) -> log.warn("Writer thread died unexpectedly: {}", sessionId, ex))
                .start(this::writerLoop);
    }

    void send(JsonNode frame) {
        if (terminated) {
            rejectedClosed.increment();
            throw new InteractiveSandboxException("Session is closed");
        }
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(frame);
        } catch (JacksonException e) {
            throw new InteractiveSandboxException("Failed to encode frame as JSON", e);
        }
        CompletableFuture<Void> ack = new CompletableFuture<>();
        WriteEnvelope env = new WriteEnvelope(payload, ack);
        // Atomic enqueue-vs-terminal: without this, send() could observe terminated==false,
        // markTerminal() could drain the queue, then send()'s offer would orphan an envelope
        // whose ack never completes (caller blocks for stdinWriteTimeoutMs).
        synchronized (this) {
            if (terminated) {
                rejectedClosed.increment();
                throw new InteractiveSandboxException("Session is closed");
            }
            if (!queue.offer(env)) {
                rejectedQueueFull.increment();
                throw new InteractiveSandboxException("Writer queue full (capacity=" + queueCapacity + ")");
            }
        }
        try {
            ack.get(stdinWriteTimeoutMs, TimeUnit.MILLISECONDS);
            framesBytesIn.increment((double) (payload.length + 1L));
        } catch (TimeoutException te) {
            // Writer is still blocked inside write(); the watchdog will destroyForcibly.
            rejectedWriteTimeout.increment();
            markTerminal();
            throw new InteractiveSandboxException("Stdin write timed out after " + stdinWriteTimeoutMs + " ms", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof IOException) {
                rejectedBrokenPipe.increment();
                markTerminal();
                throw new InteractiveSandboxException("Stdin pipe broken: " + cause.getMessage(), ee);
            }
            throw new InteractiveSandboxException("Stdin write failed", ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InteractiveSandboxException("Interrupted while waiting for stdin write", ie);
        }
    }

    void onWriteTimeout() {
        markTerminal();
    }

    boolean writeStalled(long nowNanos) {
        long started = writeStartedNanos.get();
        return started != IDLE && nowNanos - started > TimeUnit.MILLISECONDS.toNanos(stdinWriteTimeoutMs);
    }

    void close() {
        markTerminal();
        Thread t = writerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    boolean isTerminated() {
        return terminated;
    }

    private synchronized void markTerminal() {
        if (terminated) return;
        terminated = true;
        WriteEnvelope env;
        while ((env = queue.poll()) != null) {
            env.ack.completeExceptionally(new IOException("Session stopped"));
        }
        try {
            onTerminalFailure.run();
        } catch (Throwable t) {
            log.debug("onTerminalFailure callback threw", t);
        }
    }

    private void writerLoop() {
        applyMdc();
        try {
            while (!terminated) {
                WriteEnvelope env;
                try {
                    env = queue.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                writeStartedNanos.set(System.nanoTime());
                try {
                    stdin.write(env.payload);
                    stdin.write('\n');
                    stdin.flush();
                    env.ack.complete(null);
                } catch (IOException io) {
                    env.ack.completeExceptionally(io);
                    rejectedBrokenPipe.increment();
                    markTerminal();
                    return;
                } finally {
                    writeStartedNanos.set(IDLE);
                }
            }
        } finally {
            MDC.clear();
        }
    }

    private void applyMdc() {
        if (mdcSnapshot != null) {
            for (var e : mdcSnapshot.entrySet()) {
                MDC.put(e.getKey(), e.getValue());
            }
        }
    }

    @SuppressWarnings("ArrayRecordComponent") // payload bytes queued for stdin; never compared, hashed or printed
    private record WriteEnvelope(byte[] payload, CompletableFuture<Void> ack) {}
}
