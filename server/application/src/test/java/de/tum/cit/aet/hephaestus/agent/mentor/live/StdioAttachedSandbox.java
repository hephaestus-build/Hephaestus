package de.tum.cit.aet.hephaestus.agent.mentor.live;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import reactor.core.Disposable;
import reactor.core.Disposables;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AttachedSandbox} implementation that wraps a locally-spawned {@link Process} instead
 * of a Docker container. Test-only — the production interactive sandbox lives behind
 * {@code DockerInteractiveSandboxAdapter}.
 *
 * <p>Design constraints kept identical to the Docker adapter so this stand-in catches the same
 * class of bugs:
 * <ul>
 *   <li>Strict {@code \n}-terminated JSON-line framing on stdout (per pi-mentor-runner.ts §86-113).</li>
 *   <li>Fan-out subscribe — each listener runs on a dedicated virtual thread; the pump thread
 *       never blocks on slow consumers.</li>
 *   <li>After {@link #close(Duration)}, {@code send} throws and {@code subscribe} returns a
 *       disposed handle.</li>
 *   <li>{@link #close(Duration)} sends SIGTERM, waits the grace, then SIGKILL — same semantics
 *       as {@code docker stop --time}.</li>
 * </ul>
 *
 * <p>Not thread-safe across {@link #send} calls — JUnit-driven tests issue requests serially.
 */
final class StdioAttachedSandbox implements AttachedSandbox {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Buffer cap mirrors {@code MAX_LINE_BYTES} in pi-mentor-runner.ts. */
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;

    private final UUID sessionId;
    private final SandboxIdentity identity;
    private final Process process;
    private final OutputStream stdin;
    private final CopyOnWriteArrayList<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>(Instant.now());
    private volatile boolean closed = false;

    StdioAttachedSandbox(UUID sessionId, String userId, String workspaceId, Process process) {
        this.sessionId = sessionId;
        this.identity = new SandboxIdentity(sessionId, userId, workspaceId);
        this.process = process;
        this.stdin = process.getOutputStream();
        startStdoutPump(process.getInputStream());
        startStderrLogger(process.getErrorStream());
    }

    /** Test-only accessor for sampling {@code /proc/$pid/*} from stress harnesses. */
    Process process() {
        return process;
    }

    @Override
    public SandboxIdentity identity() {
        return identity;
    }

    @Override
    public synchronized void send(JsonNode frame) {
        if (closed) {
            throw new InteractiveSandboxException("sandbox already closed");
        }
        try {
            byte[] payload = MAPPER.writeValueAsBytes(frame);
            stdin.write(payload);
            stdin.write('\n');
            stdin.flush();
            lastActivity.set(Instant.now());
        } catch (IOException e) {
            throw new InteractiveSandboxException("failed to write frame to runner stdin", e);
        }
    }

    @Override
    public Disposable subscribe(Consumer<JsonNode> listener) {
        if (closed) {
            return Disposables.disposed();
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Fan-out here never cuts a subscriber off, so {@code onLost} never runs. */
    @Override
    public Disposable subscribeFromNow(Consumer<JsonNode> listener, Runnable onLost) {
        return subscribe(listener);
    }

    @Override
    public Instant lastActivityAt() {
        Instant activity = lastActivity.get();
        if (activity == null) {
            throw new IllegalStateException("last activity is unavailable");
        }
        return activity;
    }

    @Override
    public Duration idleFor() {
        return Duration.between(lastActivityAt(), Instant.now());
    }

    @Override
    public void close(Duration graceTimeout) {
        if (closed) return;
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // stdin may already be broken if the runner exited; ignore.
        }
        process.destroy();
        try {
            if (!process.waitFor(graceTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Read \n-framed JSON from stdout on a virtual thread; fan out parsed frames to subscribers. */
    private void startStdoutPump(InputStream stdout) {
        Thread.ofVirtual().name("stdio-sandbox-stdout-" + sessionId).start(() -> {
            // Buffered reader is fine here: pi-mentor-runner.ts already strips trailing \r, and the
            // single-byte \n delimiter has no overlap with multibyte UTF-8 continuation bytes — the
            // U+2028/U+2029 readline hazard called out in the runner doesn't apply to us because we
            // never split on those code points. We do still cap line size to detect a runaway runner.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stdout, StandardCharsets.UTF_8), Math.min(MAX_LINE_BYTES, 64 * 1024))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    lastActivity.set(Instant.now());
                    JsonNode frame;
                    try {
                        frame = MAPPER.readTree(line);
                    } catch (Exception parseError) {
                        // Skip malformed lines — the runner logs to stderr, which we surface verbatim.
                        continue;
                    }
                    for (Consumer<JsonNode> listener : listeners) {
                        try {
                            listener.accept(frame);
                        } catch (Throwable listenerError) {
                            // A poison listener must not kill the pump for the others.
                        }
                    }
                }
            } catch (IOException ignored) {
                // stdout pipe closes on process exit — terminal state, no work left.
            }
        });
    }

    private void startStderrLogger(InputStream stderr) {
        Thread.ofVirtual().name("stdio-sandbox-stderr-" + sessionId).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Direct to stderr so JUnit surface — matches what live tests already do for
                    // every other child-process integration in tree.
                    System.err.println("[mentor-runner] " + line);
                }
            } catch (IOException ignored) {
                // stderr pipe closes on process exit.
            }
        });
    }
}
