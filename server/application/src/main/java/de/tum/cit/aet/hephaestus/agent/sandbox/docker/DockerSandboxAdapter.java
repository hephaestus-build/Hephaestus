package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
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
 * Blocking Docker execution with cancellation and best-effort resource cleanup.
 *
 * <p>Each {@link #execute} call is self-contained and blocks its caller's thread, so callers submit it
 * to the bounded {@code sandboxExecutor} pool. Cleanup runs in {@code finally} whatever the outcome,
 * and a step that fails there is logged and left for reconciliation rather than propagated.
 */
public class DockerSandboxAdapter implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxAdapter.class);
    private static final String CONTAINER_USER = "1000:1000";
    private static final String CONTAINER_HOSTNAME = "agent";
    /**
     * Docker's tail is applied by the daemon, before anything here sees a line, so the persisted
     * transcript asks for every line: a tail would drop the beginning — what the run was asked and what
     * it read — and leave nothing downstream able to put it back.
     */
    private static final int WHOLE_TRANSCRIPT = 0;

    /** The error-path echo is cut to {@link #MAX_LOG_EVENT_CHARS} before it is logged, so it reads a tail. */
    private static final int ERROR_ECHO_TAIL_LINES = 500;

    private static final String PROXY_URL_PLACEHOLDER = "{appServerIp}";

    private static final String MDC_JOB_ID = "sandbox.jobId";
    private static final String MDC_CONTAINER_ID = "sandbox.containerId";

    /**
     * Limits each diagnostic event so a large container log cannot overflow the aggregator. This is the
     * <em>emission</em> limit; {@link DockerClientOperations#MAX_LOG_CHARS} is the upstream
     * <em>collection</em> limit.
     */
    private static final int MAX_LOG_EVENT_CHARS = 32 * 1024;

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

    private final SandboxNetworkManager networkManager;
    private final SandboxWorkspaceManager workspaceManager;
    private final SandboxContainerManager containerManager;
    private final ContainerSecurityPolicy securityPolicy;
    private final int gatewayPort;

    private final Counter executionsSuccess;
    private final Counter executionsFailed;
    private final Counter executionsTimedOut;
    private final Counter executionsCancelled;
    private final MeterRegistry meterRegistry;
    private final Timer executionDuration;

    /** Presence of a flag is what marks a job as running. */
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();

    /** Only a registered container can be stopped by {@link #cancel}. */
    private final ConcurrentHashMap<UUID, String> activeContainers = new ConcurrentHashMap<>();

    public DockerSandboxAdapter(
            SandboxNetworkManager networkManager,
            SandboxWorkspaceManager workspaceManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            int gatewayPort,
            MeterRegistry meterRegistry) {
        this.networkManager = networkManager;
        this.workspaceManager = workspaceManager;
        this.containerManager = containerManager;
        this.securityPolicy = securityPolicy;
        this.gatewayPort = gatewayPort;

        this.executionsSuccess = Counter.builder(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", "success")
                .description("Successful sandbox executions")
                .register(meterRegistry);
        this.executionsFailed = Counter.builder(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", "failure")
                .description("Failed sandbox executions")
                .register(meterRegistry);
        this.executionsTimedOut = Counter.builder(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", "timeout")
                .description("Timed-out sandbox executions")
                .register(meterRegistry);
        this.executionsCancelled = Counter.builder(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", "cancelled")
                .description("Cancelled sandbox executions")
                .register(meterRegistry);
        this.meterRegistry = meterRegistry;
        this.executionDuration = Timer.builder(AgentMetrics.SANDBOX_EXECUTION_DURATION)
                .description("Duration of sandbox executions")
                .register(meterRegistry);

        meterRegistry.gaugeMapSize(AgentMetrics.SANDBOX_CONTAINERS_ACTIVE, Tags.empty(), this.activeContainers);
    }

    @Override
    public SandboxResult execute(SandboxSpec spec) throws SandboxException {
        UUID jobId = spec.jobId();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (cancellationFlags.putIfAbsent(jobId, cancelled) != null) {
            throw new SandboxException("Job already executing: " + jobId);
        }

        String networkId = null;
        String containerId = null;
        Instant startTime = Instant.now();

        MDC.put(MDC_JOB_ID, jobId.toString());

        log.info("Starting sandbox execution: image={}", spec.image());

        try {
            checkCancelled(cancelled, jobId);

            boolean allowInternet =
                    spec.networkPolicy() != null && spec.networkPolicy().internetAccess();
            networkId = networkManager.createJobNetwork(jobId, allowInternet);

            // Connect app-server to the job network (multi-homing) and get its IP.
            // Returns null when the app-server runs on the host (not in Docker).
            String appServerIp = networkManager.connectAppServer(networkId);
            List<String> extraHosts = List.of();
            if (appServerIp == null) {
                // App-server is on the host — use host.docker.internal with host-gateway mapping.
                // Requires allowInternet=true (non-internal network) so the container can reach the host.
                if (!allowInternet) {
                    throw new SandboxException(
                            "App-server is not in Docker and network is internal (allowInternet=false). "
                                    + "Set allow_internet=true on the agent config, or run the app-server in Docker.");
                }
                appServerIp = "host.docker.internal";
                extraHosts = List.of("host.docker.internal:host-gateway");
                log.info("Using host gateway for LLM proxy: appServerIp={}", appServerIp);
            }

            checkCancelled(cancelled, jobId);

            Map<String, String> environment = buildEnvironment(spec, appServerIp);

            var secProfile = spec.securityProfile() != null ? spec.securityProfile() : SecurityProfile.DEFAULT;
            DockerOperations.HostConfigSpec hostConfig =
                    securityPolicy.buildHostConfig(secProfile, spec.resourceLimits(), spec.networkPolicy());
            Map<String, String> labels = securityPolicy.buildLabels(jobId);

            DockerOperations.ContainerSpec containerSpec = new DockerOperations.ContainerSpec(
                    spec.image(),
                    spec.command(),
                    environment,
                    networkId,
                    CONTAINER_HOSTNAME,
                    CONTAINER_USER,
                    labels,
                    hostConfig,
                    extraHosts);

            containerId = containerManager.createContainer(containerSpec);
            activeContainers.put(jobId, containerId);
            MDC.put(MDC_CONTAINER_ID, containerId);
            log.info("Container created: containerId={}", containerId);

            // Cancellation during creation cannot stop the container until it is registered.
            checkCancelled(cancelled, jobId);

            if (!spec.inputFiles().isEmpty()) {
                workspaceManager.injectFiles(containerId, spec.inputFiles(), spec.inputFilesOnDisk());
                log.debug("Injected {} input files", spec.inputFiles().size());
            }

            if (spec.volumeMounts() != null && !spec.volumeMounts().isEmpty()) {
                workspaceManager.injectDirectories(containerId, spec.volumeMounts());
                log.debug(
                        "Injected {} directories into container",
                        spec.volumeMounts().size());
            }

            containerManager.startContainer(containerId);
            log.info("Container started");

            Duration timeout = spec.resourceLimits().maxRuntime();
            SandboxContainerManager.WaitOutcome waitOutcome = containerManager.waitForCompletion(containerId, timeout);

            // A cancellation-induced container exit must remain cancellation, not a normal result.
            checkCancelled(cancelled, jobId);

            Map<String, byte[]> outputFiles;
            try {
                outputFiles = workspaceManager.collectOutput(containerId, spec.outputPath());
            } catch (SandboxException e) {
                if (!waitOutcome.timedOut() && waitOutcome.exitCode() != SandboxLayout.EXIT_ENVELOPE_MISMATCH) {
                    throw e;
                }
                // Collection failure must not hide an already-known timeout or contract drift.
                log.warn(
                        "Output unavailable after terminal sandbox failure: jobId={}, exitCode={}",
                        jobId,
                        waitOutcome.exitCode());
                outputFiles = Map.of();
            }

            String logs = containerManager.getLogs(containerId, WHOLE_TRANSCRIPT);

            if (waitOutcome.timedOut()) {
                executionsTimedOut.increment();
            } else {
                executionsSuccess.increment();
            }

            Duration duration = Duration.between(startTime, Instant.now());
            log.info(
                    "Sandbox execution complete: exitCode={}, timedOut={}, outputFiles={}, duration={}",
                    waitOutcome.exitCode(),
                    waitOutcome.timedOut(),
                    outputFiles.size(),
                    duration);

            return new SandboxResult(waitOutcome.exitCode(), outputFiles, logs, waitOutcome.timedOut(), duration);
        } catch (SandboxCancelledException e) {
            executionsCancelled.increment();
            log.info("Sandbox execution cancelled");
            throw e;
        } catch (SandboxException e) {
            executionsFailed.increment();
            captureLogsOnError(containerId);
            log.error("Sandbox execution failed: error={}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            executionsFailed.increment();
            captureLogsOnError(containerId);
            log.error("Unexpected error during sandbox execution", e);
            throw new SandboxException("Sandbox execution failed for job: " + jobId, e);
        } finally {
            // Unregister before removal so cancellation cannot race cleanup with stopContainer().
            activeContainers.remove(jobId);
            executionDuration.record(Duration.between(startTime, Instant.now()));
            cleanup(jobId, containerId, networkId);
            cancellationFlags.remove(jobId);
            MDC.remove(MDC_JOB_ID);
            MDC.remove(MDC_CONTAINER_ID);
        }
    }

    @Override
    public void cancel(UUID jobId) {
        AtomicBoolean flag = cancellationFlags.get(jobId);
        if (flag != null) {
            flag.set(true);
            log.info("Cancellation requested: jobId={}", jobId);

            // Keep stopping atomic with unregistration by cleanup.
            activeContainers.computeIfPresent(jobId, (id, containerId) -> {
                try {
                    containerManager.stopContainer(containerId);
                } catch (Exception e) {
                    log.warn("Failed to stop container for cancelled job: jobId={}, error={}", jobId, e.getMessage());
                }
                return containerId;
            });
        } else {
            log.debug("Cancel called for unknown/completed job: jobId={}", jobId);
        }
    }

    @Override
    public boolean isHealthy() {
        return containerManager.ping();
    }

    private Map<String, String> buildEnvironment(SandboxSpec spec, String appServerIp) {
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

        // Injected whether or not a volume mount is present, because the agent can clone a repository at
        // runtime and carry a hostile .git/config in with it.
        int idx = 0;
        for (String containerPath : spec.volumeMounts().values()) {
            env.put("GIT_CONFIG_KEY_" + idx, "safe.directory");
            env.put("GIT_CONFIG_VALUE_" + idx, containerPath);
            idx++;
        }
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

        if (spec.networkPolicy() != null) {
            String gatewayUrl = appServerIp != null ? "http://" + appServerIp + ":" + gatewayPort : null;
            if (gatewayUrl != null) {
                env.put("GATEWAY_URL", gatewayUrl);
            }
            if (spec.networkPolicy().llmProxyUrl() != null) {
                String proxyUrl = spec.networkPolicy().llmProxyUrl();
                if (proxyUrl.contains(PROXY_URL_PLACEHOLDER) && appServerIp != null) {
                    proxyUrl = proxyUrl.replace(PROXY_URL_PLACEHOLDER, appServerIp);
                }
                env.put("LLM_PROXY_URL", proxyUrl);
            } else if (gatewayUrl != null) {
                // One route for every provider: the proxy identifies the connection from the
                // authenticated job token, not the URL.
                env.put("LLM_PROXY_URL", gatewayUrl + "/internal/llm");
            }

            if (spec.networkPolicy().llmProxyToken() != null) {
                env.put("LLM_PROXY_TOKEN", spec.networkPolicy().llmProxyToken());
            }
        }

        return env;
    }

    private static void addTraceEnvironment(Map<String, String> env) {
        String traceId = MDC.get(StructuredLogKeys.TRACE_ID);
        String spanId = MDC.get(StructuredLogKeys.SPAN_ID);
        if (traceId != null && spanId != null) {
            env.put("TRACE_ID", traceId);
            env.put("TRACEPARENT", "00-" + traceId + "-" + spanId + "-00");
        }
    }

    /** Capture diagnostics before cleanup removes the container. */
    private void captureLogsOnError(@Nullable String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            String logs = containerManager.getLogs(containerId, ERROR_ECHO_TAIL_LINES);
            if (logs != null && !logs.isEmpty()) {
                String truncated = logs.length() > MAX_LOG_EVENT_CHARS
                        ? logs.substring(0, MAX_LOG_EVENT_CHARS) + "\n... [truncated, "
                                + logs.length()
                                + " characters total]"
                        : logs;
                log.warn("Container logs before cleanup:\n{}", truncated);
            }
        } catch (Exception e) {
            log.debug("Could not capture container logs on error path: {}", e.getMessage());
        }
    }

    private void checkCancelled(AtomicBoolean flag, UUID jobId) {
        if (flag.get()) {
            throw new SandboxCancelledException("Job cancelled: " + jobId);
        }
    }

    /** Each step is independent, so one that fails still leaves the others to run. */
    private void cleanup(UUID jobId, @Nullable String containerId, @Nullable String networkId) {
        if (containerId != null) {
            suppressAndLog("remove container", jobId, () -> containerManager.forceRemove(containerId));
        }

        if (networkId != null) {
            suppressAndLog("disconnect app-server", jobId, () -> networkManager.disconnectAppServer(networkId));
        }

        if (networkId != null) {
            suppressAndLog("remove network", jobId, () -> networkManager.removeNetwork(networkId));
        }

        log.debug("Cleanup complete: jobId={}", jobId);
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
            log.warn("Cleanup failed ({}): jobId={}, error={}", operation, jobId, e.getMessage());
        }
    }
}
