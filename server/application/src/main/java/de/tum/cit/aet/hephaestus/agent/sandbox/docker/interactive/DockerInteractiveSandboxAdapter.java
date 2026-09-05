package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.ContainerSecurityPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerOperations;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxContainerManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxEnvBlocklist;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxLabels;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxNetworkManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.EvictionReason;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

public class DockerInteractiveSandboxAdapter implements InteractiveSandboxService {

    private static final Logger log = LoggerFactory.getLogger(DockerInteractiveSandboxAdapter.class);

    private static final String CONTAINER_USER = "1000:1000";
    private static final String CONTAINER_HOSTNAME = "mentor";
    private static final String PROXY_URL_PLACEHOLDER = "{appServerIp}";
    private static final String MDC_SESSION_ID = "mentor.sessionId";

    private static final List<String> SLEEPER_CMD = List.of("tail", "-f", "/dev/null");

    // --cap-drop=ALL removes CAP_DAC_OVERRIDE, so root cannot bypass file permissions.
    // /workspace is owned by 1000:1000 in the image; run mkdir as the container user.
    private static final String PREP_MKDIR_CMD =
            "mkdir -p /workspace/.runner /workspace/context/target /workspace/context/user /workspace/scratch && "
                    + "chmod 1777 /workspace /workspace/.runner /workspace/context/user /workspace/scratch && "
                    + "chmod 1755 /workspace/context /workspace/context/target";

    // Per-dir, not -R: context/user must stay writable.
    private static final String PREP_CHMOD_CMD = "chmod -R a-w /workspace/context/target 2>/dev/null || true; "
            + "chmod a-w /workspace/context 2>/dev/null || true";

    private static final int PREP_OUTPUT_PREVIEW_CAP = 512;
    private static final Duration PREP_EXEC_TIMEOUT = Duration.ofSeconds(30);

    private final InteractiveSandboxProperties properties;
    private final SandboxNetworkManager networkManager;
    private final SandboxWorkspaceManager workspaceManager;
    private final SandboxContainerManager containerManager;
    private final ContainerSecurityPolicy securityPolicy;
    private final InteractiveSandboxRegistry registry;
    private final InteractiveSandboxMetrics metrics;
    private final ObjectMapper mapper;
    private final DockerCli dockerCli;
    private final int gatewayPort;
    private final Executor closeExecutor;
    private final MentorProxyCredentialRegistry mentorProxyCredentialRegistry;
    private final Object[] attachLocks = new Object[64];

    public DockerInteractiveSandboxAdapter(
            InteractiveSandboxProperties properties,
            SandboxNetworkManager networkManager,
            SandboxWorkspaceManager workspaceManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            InteractiveSandboxRegistry registry,
            InteractiveSandboxMetrics metrics,
            ObjectMapper mapper,
            Executor closeExecutor,
            DockerSandboxProperties dockerProperties,
            int gatewayPort,
            MentorProxyCredentialRegistry mentorProxyCredentialRegistry) {
        this.properties = properties;
        this.networkManager = networkManager;
        this.workspaceManager = workspaceManager;
        this.containerManager = containerManager;
        this.securityPolicy = securityPolicy;
        this.registry = registry;
        this.metrics = metrics;
        this.mapper = mapper;
        this.closeExecutor = closeExecutor;
        this.dockerCli = new DockerCli(dockerProperties);
        this.gatewayPort = gatewayPort;
        this.mentorProxyCredentialRegistry = mentorProxyCredentialRegistry;
        java.util.Arrays.setAll(attachLocks, ignored -> new Object());
    }

    @Override
    public AttachedSandbox attach(InteractiveSandboxSpec spec) {
        int lockIndex = Math.floorMod(Objects.hash(spec.userId(), spec.workspaceId()), attachLocks.length);
        synchronized (attachLocks[lockIndex]) {
            return attachLocked(spec);
        }
    }

    private AttachedSandbox attachLocked(InteractiveSandboxSpec spec) {
        // Per-workspace gating happens upstream in MentorChatController via
        // WorkspaceFeatures.mentorEnabled — there is no deployment-wide mentor enable flag.

        InteractiveSandboxRuntimeKey runtimeKey = runtimeKey(spec);
        DockerAttachedSandboxAdapter existing = registry.findLive(spec.userId(), spec.workspaceId());
        if (existing != null) {
            if (existing.hasRuntimeKey(runtimeKey)) {
                // The caller minted a fresh token before attach. A compatible warm sandbox keeps
                // using its original token, so discard the unused credential immediately.
                mentorProxyCredentialRegistry.revoke(spec.sessionId());
                return existing;
            }
            existing.terminate(EvictionReason.RUNTIME_CHANGED);
            existing.awaitClosed(Duration.ofSeconds(properties.graceTimeoutSeconds() + 5L));
        }

        MDC.put(MDC_SESSION_ID, spec.sessionId().toString());
        Timer.Sample sample = Timer.start();
        String networkId = null;
        String containerId = null;
        PiProcessHandle process = null;
        DockerAttachedSandboxAdapter sandbox = null;
        boolean registered = false;
        try {
            boolean allowInternet =
                    spec.networkPolicy() != null && spec.networkPolicy().internetAccess();
            networkId = networkManager.createJobNetwork(spec.sessionId(), allowInternet);
            String appServerIp = networkManager.connectAppServer(networkId);
            List<String> extraHosts = List.of();
            if (appServerIp == null) {
                if (!allowInternet) {
                    throw new InteractiveSandboxException(
                            "App-server is not in Docker and network is internal. Set allow_internet=true.");
                }
                appServerIp = "host.docker.internal";
                extraHosts = List.of("host.docker.internal:host-gateway");
            }

            SecurityProfile secProfile =
                    spec.securityProfile() != null ? spec.securityProfile() : SecurityProfile.DEFAULT;
            DockerOperations.HostConfigSpec hostConfig =
                    securityPolicy.buildHostConfig(secProfile, spec.resourceLimits(), spec.networkPolicy());
            Map<String, String> labels = Map.of(
                    SandboxLabels.MANAGED,
                    "true",
                    SandboxLabels.KIND,
                    SandboxLabels.KIND_INTERACTIVE,
                    SandboxLabels.SESSION_ID,
                    spec.sessionId().toString());
            Map<String, String> runnerEnv = buildRunnerEnvironment(spec, appServerIp);

            DockerOperations.ContainerSpec containerSpec = new DockerOperations.ContainerSpec(
                    spec.image(),
                    SLEEPER_CMD,
                    Map.of(),
                    networkId,
                    CONTAINER_HOSTNAME,
                    CONTAINER_USER,
                    labels,
                    hostConfig,
                    extraHosts);
            try {
                containerId = containerManager.createContainer(containerSpec);
            } catch (Exception e) {
                metrics.attachFailureImage.increment();
                throw new InteractiveSandboxException("createContainer failed: " + e.getMessage(), e);
            }
            log.info("Mentor container created: containerId={}, image={}", containerId, LogSafe.sanitise(spec.image()));

            try {
                containerManager.startContainer(containerId);
            } catch (Exception e) {
                metrics.attachFailureStart.increment();
                throw new InteractiveSandboxException("startContainer failed: " + e.getMessage(), e);
            }

            runExec(containerId, CONTAINER_USER, PREP_MKDIR_CMD, "workspace mkdir");
            if (!spec.inputFiles().isEmpty()) {
                workspaceManager.injectFiles(containerId, spec.inputFiles());
            }
            if (!spec.volumeMounts().isEmpty()) {
                workspaceManager.injectDirectories(containerId, spec.volumeMounts());
            }
            runExec(containerId, CONTAINER_USER, PREP_CHMOD_CMD, "workspace chmod");

            try {
                process = PiProcessHandle.spawn(dockerCli, containerId, CONTAINER_USER, spec.command(), runnerEnv);
            } catch (InteractiveSandboxException e) {
                metrics.attachFailureStdin.increment();
                throw e;
            }

            // Build + await first frame BEFORE register: a stillborn runner never becomes visible.
            sandbox = buildSandbox(spec, runtimeKey, containerId, networkId, process);
            sandbox.start();

            Duration firstFrameTimeout = Duration.ofSeconds(properties.attachFirstFrameTimeoutSeconds());
            try {
                if (!sandbox.awaitFirstFrame(firstFrameTimeout)) {
                    metrics.attachFailureFirstFrameTimeout.increment();
                    throw new InteractiveSandboxException(
                            "Runner did not emit first frame within " + firstFrameTimeout.toSeconds() + "s");
                }
            } catch (InteractiveSandboxException terminated) {
                // Distinct from timeout: pump/writer terminated before any frame.
                metrics.attachFailureFirstFrameFailed.increment();
                throw terminated;
            }

            InteractiveSandboxRegistry.RegistrationOutcome outcome = registry.tryRegister(sandbox);
            switch (outcome) {
                case DUPLICATE -> {
                    log.debug("Concurrent attach lost the race; returning existing sandbox");
                    DockerAttachedSandboxAdapter winner = registry.findLive(spec.userId(), spec.workspaceId());
                    if (winner != null) {
                        // Loser leaks container/network/process/pump/writer VTs unless we tear it down.
                        // Fire-and-forget: don't block the caller for grace+5s.
                        sandbox.terminate(EvictionReason.ERROR);
                        if (!winner.hasRuntimeKey(runtimeKey)) {
                            throw new InteractiveSandboxException(
                                    "Concurrent attach resolved to an incompatible runtime");
                        }
                        return winner;
                    }
                    throw new InteractiveSandboxException("Race lost but no winner found in registry");
                }
                case MAX_SESSIONS_PER_USER, MAX_SESSIONS_TOTAL -> {
                    metrics.attachFailureMaxSessions.increment();
                    throw new InteractiveSandboxException(
                            outcome == InteractiveSandboxRegistry.RegistrationOutcome.MAX_SESSIONS_PER_USER
                                    ? "Per-user session cap exceeded"
                                    : "Per-replica session cap exceeded");
                }
                case REGISTERED -> registered = true;
            }

            sample.stop(metrics.attachDuration);
            log.info("Mentor attached: sessionId={}", spec.sessionId());
            return sandbox;
        } catch (InteractiveSandboxException e) {
            if (!registered) {
                tearDownPartial(sandbox, process, networkId, containerId);
            }
            throw e;
        } catch (Exception e) {
            metrics.attachFailureOther.increment();
            if (!registered) {
                tearDownPartial(sandbox, process, networkId, containerId);
            }
            throw new InteractiveSandboxException("attach() failed: " + e.getMessage(), e);
        } finally {
            MDC.remove(MDC_SESSION_ID);
        }
    }

    @Override
    public boolean isHealthy() {
        return containerManager.ping();
    }

    private Map<String, String> buildRunnerEnvironment(InteractiveSandboxSpec spec, String appServerIp) {
        Map<String, String> env = new HashMap<>();
        for (var entry : spec.environment().entrySet()) {
            if (SandboxEnvBlocklist.isBlocked(entry.getKey())) {
                log.warn("Blocked dangerous environment variable: {}", entry.getKey());
                continue;
            }
            env.put(entry.getKey(), entry.getValue());
        }
        String traceId = MDC.get(StructuredLogKeys.TRACE_ID);
        String spanId = MDC.get(StructuredLogKeys.SPAN_ID);
        if (traceId != null && spanId != null) {
            env.put("TRACE_ID", traceId);
            env.put("TRACEPARENT", "00-" + traceId + "-" + spanId + "-00");
        }
        String gatewayUrl = appServerIp != null ? "http://" + appServerIp + ":" + gatewayPort : null;
        if (spec.networkPolicy() != null && gatewayUrl != null) {
            env.put("GATEWAY_URL", gatewayUrl);
        }
        if (spec.networkPolicy() != null && spec.networkPolicy().llmProxyUrl() != null) {
            String url = spec.networkPolicy().llmProxyUrl();
            if (url.contains(PROXY_URL_PLACEHOLDER)) {
                url = url.replace(PROXY_URL_PLACEHOLDER, appServerIp);
            }
            env.put("LLM_PROXY_URL", url);
        } else if (spec.networkPolicy() != null && gatewayUrl != null) {
            env.put("LLM_PROXY_URL", gatewayUrl + "/internal/llm");
        }
        if (spec.networkPolicy() != null && spec.networkPolicy().llmProxyToken() != null) {
            env.put("LLM_PROXY_TOKEN", spec.networkPolicy().llmProxyToken());
        }
        return env;
    }

    private DockerAttachedSandboxAdapter buildSandbox(
            InteractiveSandboxSpec spec,
            InteractiveSandboxRuntimeKey runtimeKey,
            String containerId,
            String networkId,
            PiProcessHandle process) {
        FrameRingBuffer ring = new FrameRingBuffer(properties.ringBufferFrames(), metrics.ringBufferDropped);
        DockerAttachedSandboxAdapter.LifecycleOps lifecycleOps = new DockerAttachedSandboxAdapter.LifecycleOps() {
            @Override
            public void stopContainer(String cid, int graceSeconds) {
                containerManager.stopContainer(cid, graceSeconds);
            }

            @Override
            public void removeContainer(String cid) {
                containerManager.forceRemove(cid);
            }

            @Override
            public void disconnectAndRemoveNetwork(String nid) {
                try {
                    networkManager.disconnectAppServer(nid);
                } catch (Exception ignored) {
                }
                networkManager.removeNetwork(nid);
            }
        };
        return new DockerAttachedSandboxAdapter(
                spec.sessionId(),
                spec.userId(),
                spec.workspaceId(),
                containerId,
                networkId,
                runtimeKey,
                process,
                mapper,
                ring,
                properties.subscriberQueueCapacity(),
                properties.stdinWriteTimeoutMs(),
                properties.sendQueueCapacity(),
                properties.maxFrameChars(),
                Duration.ofSeconds(properties.graceTimeoutSeconds()),
                metrics,
                lifecycleOps,
                closeExecutor,
                this::onAttachedSandboxClosed);
    }

    private InteractiveSandboxRuntimeKey runtimeKey(InteractiveSandboxSpec spec) {
        String token = spec.networkPolicy() != null ? spec.networkPolicy().llmProxyToken() : null;
        var routing =
                token != null ? mentorProxyCredentialRegistry.validate(token).orElse(null) : null;
        return InteractiveSandboxRuntimeKey.of(spec, routing);
    }

    /**
     * The single dispose choke point for every close reason, so no proxy credential can outlive the
     * container it was minted for.
     */
    private void onAttachedSandboxClosed(DockerAttachedSandboxAdapter sandbox) {
        registry.onSandboxClosed(sandbox);
        mentorProxyCredentialRegistry.revoke(sandbox.identity().sessionId());
    }

    private static final int PREP_DRAIN_CAP_BYTES = 16 * 1024;

    private void runExec(String containerId, String user, String script, String description) {
        ProcessBuilder pb =
                dockerCli.configure(new ProcessBuilder("exec", "-u", user, containerId, "sh", "-c", script));
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            metrics.attachFailureOther.increment();
            throw new InteractiveSandboxException(description + " failed: " + e.getMessage(), e);
        }
        // Bounded drain — a misbehaving exec emitting a megabyte of stdout would otherwise OOM
        // the app-server. We only need a short preview for the error message.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(PREP_DRAIN_CAP_BYTES);
        Thread drainer = Thread.ofVirtual().start(() -> {
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[1024];
                int read;
                while ((read = in.read(buf)) >= 0) {
                    int room = PREP_DRAIN_CAP_BYTES - out.size();
                    if (room <= 0) {
                        // Capture is full; keep draining (discard) so the pipe doesn't back-pressure.
                        continue;
                    }
                    out.write(buf, 0, Math.min(read, room));
                }
            } catch (IOException ignored) {
            }
        });
        try {
            boolean exited = p.waitFor(PREP_EXEC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                p.destroyForcibly();
                drainer.join(500);
                metrics.attachFailureOther.increment();
                throw new InteractiveSandboxException(
                        description + " timed out after " + PREP_EXEC_TIMEOUT.toSeconds() + "s");
            }
            drainer.join(500);
            int exit = p.exitValue();
            if (exit != 0) {
                String preview = out.toString(StandardCharsets.UTF_8);
                if (preview.length() > PREP_OUTPUT_PREVIEW_CAP) {
                    preview = preview.substring(0, PREP_OUTPUT_PREVIEW_CAP) + "…";
                }
                metrics.attachFailureOther.increment();
                throw new InteractiveSandboxException(description + " failed: exit=" + exit + ", output=" + preview);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            metrics.attachFailureOther.increment();
            throw new InteractiveSandboxException(description + " failed: interrupted", ie);
        }
    }

    private void tearDownPartial(
            @Nullable DockerAttachedSandboxAdapter sandbox,
            @Nullable PiProcessHandle process,
            @Nullable String networkId,
            @Nullable String containerId) {
        // Pump/writer threads may already be running; terminate() drives the full close path.
        if (sandbox != null) {
            sandbox.terminate(EvictionReason.ERROR);
            sandbox.awaitClosed(Duration.ofSeconds(properties.graceTimeoutSeconds() + 5L));
            return;
        }
        if (process != null) {
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        if (containerId != null) {
            try {
                containerManager.forceRemove(containerId);
            } catch (Exception e) {
                log.warn("Partial cleanup: forceRemove failed for {}: {}", containerId, e.getMessage());
            }
        }
        if (networkId != null) {
            try {
                networkManager.disconnectAppServer(networkId);
            } catch (Exception ignored) {
            }
            try {
                networkManager.removeNetwork(networkId);
            } catch (Exception e) {
                log.warn("Partial cleanup: removeNetwork failed for {}: {}", networkId, e.getMessage());
            }
        }
    }
}
