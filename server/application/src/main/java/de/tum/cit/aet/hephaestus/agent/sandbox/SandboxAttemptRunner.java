package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInitializationException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxRoom;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The practice review attempt protocol, once for every sandbox runtime: the gateway session that binds the attempt to
 * its credential and inputs, the sandbox's environment and deadlines, cancellation, the result and its metrics. The
 * {@link SandboxBackend} only runs the sandbox.
 *
 * <p>Each {@link #execute} call is self-contained and blocks its caller's thread, so callers submit it to the bounded
 * {@code sandboxExecutor} pool. Cleanup runs in {@code finally} whatever the outcome, and a step that fails there is
 * logged and left for reconciliation rather than propagated.
 */
public class SandboxAttemptRunner implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxAttemptRunner.class);

    private static final String MDC_JOB_ID = "sandbox.jobId";
    private static final String MDC_SANDBOX = "sandbox.id";

    /**
     * Overrides repository, global and system Git config; explicit {@code git -c} options take precedence.
     *
     * <p>Each entry pairs a git config key that can execute code with a value that neutralises it: an
     * empty string disables the feature, {@code /nonexistent} points it at a path that cannot exist, and
     * {@code cat} is the canonical no-op pager.
     *
     * @see <a href="https://git-scm.com/docs/git-config#_environment">git-config environment</a>
     */
    static final List<Map.Entry<String, String>> GIT_SECURITY_CONFIGS = List.of(
            Map.entry("core.hooksPath", "/nonexistent"),
            Map.entry("core.fsmonitor", "false"),
            Map.entry("core.sshCommand", ""),
            Map.entry("core.askPass", ""),
            Map.entry("core.editor", ""),
            Map.entry("core.pager", "cat"),
            Map.entry("core.gitProxy", ""),
            Map.entry("sequence.editor", ""),
            Map.entry("credential.helper", ""),
            Map.entry("diff.external", ""),
            Map.entry("protocol.ext.allow", "never"));

    private final SandboxBackend backend;
    private final SandboxWorkspaceManager workspaceManager;
    private final SandboxGatewaySessions gatewaySessions;
    private final MeterRegistry meterRegistry;

    private final Counter executionsSuccess;
    private final Counter executionsFailed;
    private final Counter executionsTimedOut;
    private final Counter executionsCancelled;
    private final Timer executionDuration;

    /** Presence of a flag is what marks a job as running. */
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();

    /** Only a registered sandbox can be killed by {@link #cancel}. */
    private final ConcurrentHashMap<UUID, SandboxBackend.Handle> activeSandboxes = new ConcurrentHashMap<>();

    public SandboxAttemptRunner(
            SandboxBackend backend,
            SandboxWorkspaceManager workspaceManager,
            SandboxGatewaySessions gatewaySessions,
            MeterRegistry meterRegistry) {
        this.backend = backend;
        this.workspaceManager = workspaceManager;
        this.gatewaySessions = gatewaySessions;
        this.meterRegistry = meterRegistry;
        this.executionsSuccess = execution(meterRegistry, "success", "Successful sandbox executions");
        this.executionsFailed = execution(meterRegistry, "failure", "Failed sandbox executions");
        this.executionsTimedOut = execution(meterRegistry, "timeout", "Timed-out sandbox executions");
        this.executionsCancelled = execution(meterRegistry, "cancelled", "Cancelled sandbox executions");
        this.executionDuration = Timer.builder(AgentMetrics.SANDBOX_EXECUTION_DURATION)
                .description("Duration of sandbox executions")
                .register(meterRegistry);
        meterRegistry.gaugeMapSize(AgentMetrics.SANDBOX_CONTAINERS_ACTIVE, Tags.empty(), activeSandboxes);
    }

    private static Counter execution(MeterRegistry registry, String outcome, String description) {
        return Counter.builder(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", outcome)
                .description(description)
                .register(registry);
    }

    @Override
    public boolean ready() {
        return backend.ready();
    }

    @Override
    public int roomFor(ResourceLimits limits) {
        return backend instanceof SandboxRoom room ? room.roomFor(limits) : Integer.MAX_VALUE;
    }

    @Override
    public SandboxResult execute(SandboxSpec spec) throws SandboxException {
        UUID jobId = spec.jobId();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (cancellationFlags.putIfAbsent(jobId, cancelled) != null) {
            throw new SandboxException("Job already executing: " + jobId);
        }

        SandboxGatewaySessions.Session session = null;
        SandboxBackend.Handle handle = null;
        Instant startTime = Instant.now();

        MDC.put(MDC_JOB_ID, jobId.toString());

        log.info("Starting sandbox execution: image={}", spec.image());

        try {
            checkCancelled(cancelled, jobId);

            session = openSession(spec);
            Instant workDeadline = startTime.plus(spec.resourceLimits().maxRuntime());
            if (spec.workDeadline() != null && spec.workDeadline().isBefore(workDeadline)) {
                workDeadline = spec.workDeadline();
            }
            Instant uploadDeadline = workDeadline.plus(SandboxLayout.RESULT_UPLOAD_GRACE);
            if (!Instant.now().isBefore(workDeadline)) {
                throw new SandboxException("Sandbox execution deadline exceeded before initialization");
            }
            List<String> command = new ArrayList<>(SandboxLayout.RUNTIME_COMMAND);
            command.addAll(spec.command());
            UUID sessionId = session.id();
            Instant work = workDeadline;
            handle = backend.launch(new SandboxBackend.Launch(
                    SandboxBackend.Kind.REVIEW,
                    jobId,
                    spec.attempt(),
                    spec.image(),
                    command,
                    spec.resourceLimits(),
                    uploadDeadline,
                    gateway -> environment(spec, gateway, sessionId, work, uploadDeadline)));
            activeSandboxes.put(jobId, handle);
            MDC.put(MDC_SANDBOX, handle.id());
            // Cancellation during the launch could not kill the sandbox until it was registered.
            checkCancelled(cancelled, jobId);

            SandboxBackend.Observation initialized =
                    backend.observe(handle, SandboxBackend.Stage.INITIALIZED, workDeadline);
            checkCancelled(cancelled, jobId);
            // Before any model call, an initializer that fails or hangs is the infrastructure's failure: the job may
            // run again within its retry budget.
            Integer initExit = initialized.exitCode();
            if (initialized.timedOut() || initExit == null || initExit != 0) {
                if (initialized.timedOut()) {
                    executionsTimedOut.increment();
                }
                String transcript = backend.transcript(handle, SandboxBackend.Container.INITIALIZER);
                logTranscriptTail(transcript);
                throw new SandboxInitializationException(initExit, initialized.timedOut(), transcript);
            }
            if (!Instant.now().isBefore(workDeadline)) {
                backend.kill(handle);
                executionsTimedOut.increment();
                return new SandboxResult(
                        SandboxLayout.EXIT_WORK_TIMEOUT,
                        Map.of(),
                        "",
                        true,
                        Duration.between(startTime, Instant.now()));
            }

            SandboxBackend.Observation ended = backend.observe(handle, SandboxBackend.Stage.ENDED, uploadDeadline);
            // A cancellation-induced exit must remain cancellation, not a normal result.
            checkCancelled(cancelled, jobId);
            if (ended.exitCode() == null && !ended.timedOut()) {
                throw new SandboxException("The sandbox ended before its runtime ran");
            }
            int exitCode = exitOrKilled(ended);

            Map<String, byte[]> outputFiles;
            try {
                outputFiles = session.result();
            } catch (RuntimeException exception) {
                if (!ended.timedOut()
                        && exitCode != SandboxLayout.EXIT_WORK_TIMEOUT
                        && exitCode != SandboxLayout.EXIT_UPLOAD_FAILED
                        && exitCode != SandboxLayout.EXIT_ENVELOPE_MISMATCH) {
                    throw exception;
                }
                outputFiles = Map.of();
            }

            String logs = backend.transcript(handle, SandboxBackend.Container.RUNTIME);

            boolean timedOut = ended.timedOut() || exitCode == SandboxLayout.EXIT_WORK_TIMEOUT;
            if (timedOut) {
                executionsTimedOut.increment();
            } else {
                executionsSuccess.increment();
            }

            Duration duration = Duration.between(startTime, Instant.now());
            log.info(
                    "Sandbox execution complete: exitCode={}, timedOut={}, outputFiles={}, duration={}",
                    exitCode,
                    timedOut,
                    outputFiles.size(),
                    duration);

            return new SandboxResult(exitCode, outputFiles, logs, timedOut, duration);
        } catch (SandboxCancelledException e) {
            executionsCancelled.increment();
            log.info("Sandbox execution cancelled");
            throw e;
        } catch (Exception e) {
            if (cancelled.get()) {
                // A failure that a cancellation caused, inside the backend's own steps, remains the cancellation.
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                executionsCancelled.increment();
                log.info("Sandbox execution cancelled");
                throw new SandboxCancelledException("Job cancelled: " + jobId, e);
            }
            throw failed(e, jobId, handle);
        } finally {
            // Unregister before removal so cancellation cannot race cleanup with kill().
            activeSandboxes.remove(jobId);
            executionDuration.record(Duration.between(startTime, Instant.now()));
            if (handle != null) {
                SandboxBackend.Handle launched = handle;
                suppressAndLog("remove sandbox", jobId, () -> backend.remove(launched));
            }
            if (session != null) {
                SandboxGatewaySessions.Session opened = session;
                suppressAndLog("close gateway session", jobId, () -> {
                    try {
                        opened.close();
                    } catch (IOException exception) {
                        throw new SandboxException("Could not close the gateway session", exception);
                    }
                });
            }
            cancellationFlags.remove(jobId);
            MDC.remove(MDC_JOB_ID);
            MDC.remove(MDC_SANDBOX);
        }
    }

    /** Counts and logs a failure, as the caller sees it: a {@link SandboxException}, the runtime's or the attempt's. */
    private SandboxException failed(Exception e, UUID jobId, SandboxBackend.@Nullable Handle handle) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        executionsFailed.increment();
        captureLogsOnError(handle);
        if (e instanceof SandboxException failure) {
            // Bounded facts only, never a message: the initializer's exit names the failure in the line itself.
            if (failure instanceof SandboxInitializationException initialization) {
                log.error(
                        "Sandbox execution failed: errorType={}, initializerExit={}, timedOut={}",
                        failure.getClass().getName(),
                        initialization.exitCode(),
                        initialization.timedOut(),
                        failure);
            } else {
                log.error(
                        "Sandbox execution failed: errorType={}",
                        failure.getClass().getName(),
                        failure);
            }
            return failure;
        }
        log.error("Unexpected error during sandbox execution", e);
        return new SandboxException("Sandbox execution failed for job: " + jobId, e);
    }

    @Override
    public void cancel(UUID jobId) {
        AtomicBoolean flag = cancellationFlags.get(jobId);
        if (flag != null) {
            flag.set(true);
            log.info("Cancellation requested: jobId={}", jobId);

            // Keep killing atomic with unregistration by cleanup.
            activeSandboxes.computeIfPresent(jobId, (id, handle) -> {
                backend.kill(handle);
                return handle;
            });
        } else {
            log.debug("Cancel called for unknown/completed job: jobId={}", jobId);
        }
    }

    /**
     * Registers the attempt's gateway session on its inputs. A review attempt with a declared attempt number is bound
     * to that attempt and image before anything can upload for it. A failed registration removes the input archive.
     */
    private SandboxGatewaySessions.Session openSession(SandboxSpec spec) throws IOException {
        Path inputTar =
                workspaceManager.createInputTar(spec.inputFiles(), spec.inputFilesOnDisk(), spec.inputDirectories());
        try {
            return spec.attempt() == null
                    ? gatewaySessions.register(spec.jobId(), spec.gatewayToken(), inputTar, "out")
                    : gatewaySessions.registerAttempt(
                            spec.jobId(), spec.attempt(), spec.image(), spec.gatewayToken(), inputTar, "out");
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(inputTar);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /** The sandbox's environment for the gateway address its backend gives it. */
    private static Map<String, String> environment(
            SandboxSpec spec, String gateway, UUID sessionId, Instant workDeadline, Instant uploadDeadline) {
        Map<String, String> env = new HashMap<>();

        // Enforced values must overwrite caller-supplied variables: SandboxEnvBlocklist already drops the
        // dangerous ones, and this ordering keeps a value added below winning even if it is not listed there.
        for (var entry : spec.environment().entrySet()) {
            if (SandboxEnvBlocklist.isBlocked(entry.getKey())) {
                log.warn("Blocked dangerous environment variable: {}", entry.getKey());
            } else {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        addTraceEnvironment(env);

        // The agent can clone a repository at runtime and carry a hostile .git/config in with it.
        int idx = 0;
        for (var gitConfig : GIT_SECURITY_CONFIGS) {
            env.put("GIT_CONFIG_KEY_" + idx, gitConfig.getKey());
            env.put("GIT_CONFIG_VALUE_" + idx, gitConfig.getValue());
            idx++;
        }
        env.put("GIT_CONFIG_COUNT", String.valueOf(idx));

        // Prevent git from prompting interactively (would hang the agent)
        env.put("GIT_TERMINAL_PROMPT", "0");
        // Prevent system-wide gitattributes from being loaded
        env.put("GIT_ATTR_NOSYSTEM", "1");

        // One route for every provider: the proxy identifies the connection from the authenticated job token, not the
        // URL.
        env.put("LLM_PROXY_URL", gateway + "/internal/llm");
        env.put("LLM_PROXY_TOKEN", spec.gatewayToken());
        env.put("SANDBOX_RUNTIME_URL", gateway + "/internal/llm/runtime/" + sessionId);
        env.put("SANDBOX_WORK_DEADLINE_MS", Long.toString(workDeadline.toEpochMilli()));
        env.put("SANDBOX_UPLOAD_DEADLINE_MS", Long.toString(uploadDeadline.toEpochMilli()));
        return env;
    }

    private static void addTraceEnvironment(Map<String, String> env) {
        String traceId = MDC.get(StructuredLogKeys.TRACE_ID);
        String spanId = MDC.get(StructuredLogKeys.SPAN_ID);
        if (traceId != null && spanId != null) {
            env.put("TRACE_ID", traceId);
            env.put(
                    "TRACEPARENT",
                    "00-" + traceId + "-" + spanId + "-"
                            + ("01".equals(MDC.get(StructuredLogKeys.TRACE_FLAGS)) ? "01" : "00"));
        }
    }

    /** The exit that a stop left, or SIGKILL's when the backend saw none. */
    private static int exitOrKilled(SandboxBackend.Observation observation) {
        Integer exit = observation.exitCode();
        return exit == null ? SandboxLayout.EXIT_KILLED : exit;
    }

    /** Capture diagnostics before cleanup removes the sandbox. */
    private void captureLogsOnError(SandboxBackend.@Nullable Handle handle) {
        if (handle == null) {
            return;
        }
        try {
            logTranscriptTail(backend.transcript(handle, SandboxBackend.Container.RUNTIME));
        } catch (Exception e) {
            log.debug(
                    "Could not capture sandbox logs on error path: errorType={}",
                    e.getClass().getName());
        }
    }

    /**
     * The transcript holds what the run was asked, what it read and the provider errors it saw, so no log level
     * receives it; the log gets its size only. The worker's bounded debug session transcripts (ADR 0041) are the
     * diagnostic path.
     */
    private static void logTranscriptTail(@Nullable String logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        log.atWarn()
                .addKeyValue("event.name", "sandbox.transcript.withheld")
                .addKeyValue("sandbox.transcript.characters", logs.length())
                .addKeyValue("sandbox.transcript.lines", logs.lines().count())
                .log("Container logs before cleanup are withheld from the shared log");
    }

    private void checkCancelled(AtomicBoolean flag, UUID jobId) {
        if (flag.get()) {
            throw new SandboxCancelledException("Job cancelled: " + jobId);
        }
    }

    private void suppressAndLog(String operation, UUID jobId, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            meterRegistry
                    .counter(AgentMetrics.SANDBOX_CLEANUP_FAILURES, "step", operation)
                    .increment();
            log.warn(
                    "Cleanup failed ({}): jobId={}, errorType={}",
                    operation,
                    jobId,
                    e.getClass().getName());
        }
    }
}
