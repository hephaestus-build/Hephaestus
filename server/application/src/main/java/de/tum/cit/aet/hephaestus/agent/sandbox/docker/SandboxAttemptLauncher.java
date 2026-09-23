package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The launch steps the batch and the interactive adapter share: bind the attempt to the credential
 * its sandbox will present, create the attempt volumes, and run the workspace initializer to
 * completion before the runtime container is created on the same volumes.
 */
public final class SandboxAttemptLauncher {
    private static final Logger log = LoggerFactory.getLogger(SandboxAttemptLauncher.class);
    private static final List<String> INITIALIZER_COMMAND = List.of("node", "/opt/pi-sdk/gateway-init.ts");

    /** Docker applies the tail before anything here sees a line, so a failed initializer's transcript is asked whole. */
    private static final int WHOLE_TRANSCRIPT = 0;

    private final SandboxContainerManager containerManager;
    private final SandboxGatewaySessions gatewaySessions;
    private final DockerVolumeOperations volumeOperations;
    private final int gatewayPort;

    public SandboxAttemptLauncher(
            SandboxContainerManager containerManager,
            SandboxGatewaySessions gatewaySessions,
            DockerVolumeOperations volumeOperations,
            int gatewayPort) {
        this.containerManager = containerManager;
        this.gatewaySessions = gatewaySessions;
        this.volumeOperations = volumeOperations;
        this.gatewayPort = gatewayPort;
    }

    /** Registers the gateway session for the credential and creates the attempt volumes, or neither. */
    public Attempt open(@Nullable NetworkPolicy policy, Path inputTar, Map<String, String> labels) throws IOException {
        String token = policy == null ? null : policy.llmProxyToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Gateway credential required");
        }
        var session = gatewaySessions.register(token, inputTar, "out");
        try {
            return new Attempt(session, new DockerAttemptWorkspace(volumeOperations, session.id(), labels));
        } catch (RuntimeException exception) {
            try {
                session.close();
            } catch (IOException | RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public record Initialization(boolean timedOut, int exitCode, String transcript) {
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    public final class Attempt implements AutoCloseable {
        private final SandboxGatewaySessions.Session session;
        private final DockerAttemptWorkspace workspace;

        private Attempt(SandboxGatewaySessions.Session session, DockerAttemptWorkspace workspace) {
            this.session = session;
            this.workspace = workspace;
        }

        public SandboxGatewaySessions.Session session() {
            return session;
        }

        public String runtimeUrl(String appServerIp) {
            return "http://" + appServerIp + ":" + gatewayPort + "/internal/llm/runtime/" + session.id();
        }

        /**
         * Runs the initializer on the attempt volumes and removes it whatever happens; {@code onCreated}
         * sees the container id before it starts, so a cancellation can still stop it. The transcript
         * is captured only when the run did not succeed.
         */
        public Initialization initialize(
                DockerOperations.ContainerSpec template, Duration timeout, Consumer<String> onCreated) {
            String initializerId = containerManager.createContainer(container(template, INITIALIZER_COMMAND, true));
            try {
                onCreated.accept(initializerId);
                containerManager.startContainer(initializerId);
                var outcome = containerManager.waitForCompletion(initializerId, timeout);
                var result = new Initialization(outcome.timedOut(), outcome.exitCode(), "");
                return result.succeeded()
                        ? result
                        : new Initialization(outcome.timedOut(), outcome.exitCode(), transcript(initializerId));
            } finally {
                try {
                    containerManager.forceRemove(initializerId);
                } catch (RuntimeException exception) {
                    log.warn(
                            "Could not remove workspace initializer {}; left for reconciliation",
                            initializerId,
                            exception);
                }
            }
        }

        private String transcript(String containerId) {
            try {
                return containerManager.getLogs(containerId, WHOLE_TRANSCRIPT);
            } catch (RuntimeException exception) {
                log.debug("Could not read initializer transcript: {}", exception.getMessage());
                return "";
            }
        }

        /** The runtime container, on the same volumes with {@code /workspace} read-only. */
        public DockerOperations.ContainerSpec runtime(DockerOperations.ContainerSpec template, List<String> command) {
            return container(template, command, false);
        }

        private DockerOperations.ContainerSpec container(
                DockerOperations.ContainerSpec template, List<String> command, boolean initialization) {
            return new DockerOperations.ContainerSpec(
                    template.image(),
                    command,
                    template.environment(),
                    template.networkId(),
                    template.hostname(),
                    template.user(),
                    template.labels(),
                    workspace.configure(template.hostConfig(), initialization),
                    template.extraHosts());
        }

        @Override
        public void close() throws IOException {
            try {
                workspace.close();
            } finally {
                session.close();
            }
        }
    }
}
