package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The launch steps a practice review's sandbox and the Heph host share: bind the attempt to the credential
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
    private final IntSupplier gatewayPort;

    public SandboxAttemptLauncher(
            SandboxContainerManager containerManager,
            SandboxGatewaySessions gatewaySessions,
            DockerVolumeOperations volumeOperations,
            IntSupplier gatewayPort) {
        this.containerManager = containerManager;
        this.gatewaySessions = gatewaySessions;
        this.volumeOperations = volumeOperations;
        this.gatewayPort = gatewayPort;
    }

    /**
     * Registers a review attempt's gateway session for the credential and creates the attempt volumes, or neither. A
     * review attempt with a declared attempt number is bound to that attempt and image before anything can upload for
     * it.
     *
     * @param id the review job the session serves
     */
    public Attempt open(
            UUID id,
            @Nullable Integer attemptNumber,
            @Nullable String image,
            @Nullable NetworkPolicy policy,
            Path inputTar,
            Map<String, String> labels)
            throws IOException {
        return open(
                policy,
                inputTar,
                labels,
                token -> attemptNumber == null || image == null
                        ? gatewaySessions.register(id, token, inputTar, "out")
                        : gatewaySessions.registerAttempt(id, attemptNumber, image, token, inputTar, "out"));
    }

    /** As {@link #open}, for a Heph host: its session is registered as a host session, which no job token backs. */
    public Attempt openHost(UUID id, NetworkPolicy policy, Path inputTar, Map<String, String> labels)
            throws IOException {
        return open(policy, inputTar, labels, token -> gatewaySessions.registerHost(id, token, inputTar, "out"));
    }

    private interface Registration {
        SandboxGatewaySessions.Session register(String token) throws IOException;
    }

    private Attempt open(
            @Nullable NetworkPolicy policy, Path inputTar, Map<String, String> labels, Registration registration)
            throws IOException {
        String token = policy == null ? null : policy.llmProxyToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Gateway credential required");
        }
        SandboxGatewaySessions.Session session;
        try {
            session = registration.register(token);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(inputTar);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
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
            return "http://" + appServerIp + ":" + gatewayPort.getAsInt() + "/internal/llm/runtime/" + session.id();
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
                log.debug(
                        "Could not read initializer transcript: errorType={}",
                        exception.getClass().getName());
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
