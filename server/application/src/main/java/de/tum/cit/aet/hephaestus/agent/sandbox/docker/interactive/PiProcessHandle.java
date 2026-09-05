package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a {@code docker exec -i} subprocess. Exposes the runner's stdout as a UTF-8 {@link Reader}
 * (raw — framing is the caller's job) and stdin as an {@link OutputStream}. Stderr is drained on
 * a separate virtual thread so its pipe never fills and back-pressures the runner.
 */
final class PiProcessHandle {

    private static final Logger log = LoggerFactory.getLogger(PiProcessHandle.class);

    private static final int STDERR_LINE_CAP = 4096;

    private final Process process;
    private final Reader stdout;
    private final OutputStream stdin;
    private final Thread stderrDrainer;
    private final String containerId;

    private PiProcessHandle(Process process, String containerId) {
        this.process = process;
        this.containerId = containerId;
        this.stdout = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        this.stdin = process.getOutputStream();
        this.stderrDrainer = Thread.ofVirtual()
                .name("mentor-stderr-" + containerId)
                .uncaughtExceptionHandler((t, ex) -> log.debug("Stderr drainer died", ex))
                .start(this::drainStderr);
    }

    /**
     * Spawns {@code docker exec -i -u <containerUser>}. Explicit {@code -u} guards against USER
     * drift in a future refactor; env keys are pre-validated by
     * {@link de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec}.
     */
    static PiProcessHandle spawn(
            DockerCli dockerCli,
            String containerId,
            String containerUser,
            List<String> command,
            Map<String, String> environment) {
        List<String> argv = new ArrayList<>();
        argv.add("exec");
        argv.add("-i"); // never -t: no TTY → no TIOCSTI injection vector
        argv.add("-u");
        argv.add(containerUser);
        for (Map.Entry<String, String> e : environment.entrySet()) {
            argv.add("-e");
            argv.add(e.getKey() + "=" + e.getValue());
        }
        argv.add(containerId);
        argv.addAll(command);

        ProcessBuilder pb = dockerCli.configure(new ProcessBuilder(argv));
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            log.debug("Spawned docker exec subprocess: containerId={}, pid={}", containerId, p.pid());
            return new PiProcessHandle(p, containerId);
        } catch (IOException e) {
            throw new InteractiveSandboxException(
                    "Failed to spawn docker exec for container " + containerId + ": " + e.getMessage(), e);
        }
    }

    Reader stdout() {
        return stdout;
    }

    OutputStream stdin() {
        return stdin;
    }

    long pid() {
        return process.pid();
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** Closes the underlying FDs — the only way to unblock a thread parked in write() on Linux. */
    void destroyForcibly() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    boolean waitFor(Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** @return exit value, or {@code -1} if still alive (no overlap with real exit codes 0-255). */
    int exitValueOrAlive() {
        if (process.isAlive()) {
            return -1;
        }
        return process.exitValue();
    }

    /** Bounded wait then close FDs — unbounded wait risks blocking against a hung docker exec. */
    void awaitExitAndClose(Duration timeout) {
        if (!waitFor(timeout)) {
            log.warn("Exec subprocess did not exit within {}ms; closing FDs anyway", timeout.toMillis());
        }
        stderrDrainer.interrupt();
        try {
            stdout.close();
        } catch (IOException ignored) {
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        // Closes the stderr pipe FD on the kernel side — only way to unblock the drainer VT if the
        // process did not exit and destroyForcibly was a no-op (zombie docker-cli relay).
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    private void drainStderr() {
        try (BufferedReader err =
                new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                if (line.isEmpty()) continue;
                // Sanitise: a hostile runner can emit CR/LF/ANSI/BiDi to forge log records.
                String safe = LogSafe.sanitise(line, STDERR_LINE_CAP);
                log.debug("[runner-stderr containerId={}] {}", LogSafe.sanitise(containerId), safe);
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                log.debug("Stderr drain ended with IOException while process still alive: {}", e.getMessage());
            }
        }
    }
}
