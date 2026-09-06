package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Ulimit;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production implementation of Docker operations using docker-java.
 *
 * <p>Implements all three focused operation interfaces. Each method is a thin delegate to {@link
 * DockerClient}, translating {@link DockerException} to {@link SandboxException}.
 */
public class DockerClientOperations
        implements DockerContainerOperations, DockerNetworkOperations, DockerFileOperations, DockerImageOperations {

    private static final Logger log = LoggerFactory.getLogger(DockerClientOperations.class);
    private static final int LOG_COLLECTION_TIMEOUT_SECONDS = 30;

    /** A pull that has not finished by here is treated as failed; the RPC client's idle timeout sits above it. */
    static final Duration IMAGE_PULL_TIMEOUT = Duration.ofMinutes(5);

    /**
     * How much of a container's output is held in memory before its middle is dropped, split evenly
     * between the beginning and the ending. A review's transcript is the only account of what it did,
     * so the whole of it is kept unless a run is large enough to threaten the collecting process; see
     * {@link DockerSandboxAdapter#MAX_LOG_EVENT_CHARS} for the separate limit on what is echoed to the
     * application's own log.
     */
    static final int MAX_LOG_CHARS = 8 * 1024 * 1024; // 8 MB

    private final DockerClient dockerClient;

    /** `docker wait` is silent until the container exits, so it needs a socket timeout no RPC call wants. */
    private final DockerClient streamingClient;

    public DockerClientOperations(DockerClient dockerClient, DockerClient streamingClient) {
        this.dockerClient = dockerClient;
        this.streamingClient = streamingClient;
    }

    @Override
    public boolean ping() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            log.debug("Docker ping failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean imageIsPresent(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            log.debug("imageIsPresent check failed for {}: {}", image, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<Map<String, String>> imageLabels(String image) {
        try {
            var config = dockerClient.inspectImageCmd(image).exec().getConfig();
            Map<String, String> labels = config == null ? null : config.getLabels();
            return Optional.of(labels == null ? Map.of() : Map.copyOf(labels));
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.debug("imageLabels lookup failed for {}: {}", image, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean pullImage(String image) {
        if (image == null || image.isBlank()) {
            log.warn("pullImage called with blank image reference, skipping");
            return false;
        }
        try {
            log.info("Pulling Docker image: {}", image);
            long startMs = System.currentTimeMillis();
            boolean completed = dockerClient
                    .pullImageCmd(image)
                    .start()
                    .awaitCompletion(IMAGE_PULL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - startMs;
            if (!completed) {
                log.warn("Docker pull timed out after {}: image={}", IMAGE_PULL_TIMEOUT, image);
                return false;
            }
            log.info("Pulled Docker image in {} ms: {}", durationMs, image);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Docker pull interrupted: image={}", image);
            return false;
        } catch (Exception e) {
            log.warn("Docker pull failed (will fall back to cached image): image={}, error={}", image, e.getMessage());
            return false;
        }
    }

    @Override
    public String createNetwork(String name, boolean internal) {
        try {
            CreateNetworkResponse response = dockerClient
                    .createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withInternal(internal)
                    .withCheckDuplicate(true)
                    .exec();
            log.debug("Created Docker network: name={}, id={}, internal={}", name, response.getId(), internal);
            return response.getId();
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to create network: " + name, e);
        }
    }

    @Override
    public String connectToNetwork(String networkId, String containerId) {
        try {
            dockerClient
                    .connectToNetworkCmd()
                    .withNetworkId(networkId)
                    .withContainerId(containerId)
                    .exec();

            // Read back the assigned IP.
            // Network.getContainers() is keyed by FULL 64-char container ID, but containerId
            // may be a short 12-char ID (e.g. from HOSTNAME env var). Try exact match first,
            // then fall back to prefix match.
            Network network =
                    dockerClient.inspectNetworkCmd().withNetworkId(networkId).exec();
            Network.ContainerNetworkConfig containerConfig =
                    network.getContainers().get(containerId);
            if (containerConfig == null) {
                // Prefix match: short ID → full ID lookup
                containerConfig = network.getContainers().entrySet().stream()
                        .filter(e -> e.getKey().startsWith(containerId))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (containerConfig == null) {
                throw new SandboxException(
                        "Container " + containerId + " not found in network " + networkId + " after connect");
            }
            String ipAddress = containerConfig.getIpv4Address();
            // Remove CIDR suffix if present (e.g. "172.18.0.2/16" -> "172.18.0.2")
            if (ipAddress != null && ipAddress.contains("/")) {
                ipAddress = ipAddress.substring(0, ipAddress.indexOf('/'));
            }
            log.debug("Connected container {} to network {}: ip={}", containerId, networkId, ipAddress);
            return ipAddress;
        } catch (DockerException e) {
            throw new SandboxInfrastructureException(
                    "Failed to connect container " + containerId + " to network " + networkId, e);
        }
    }

    @Override
    public void disconnectFromNetwork(String networkId, String containerId) {
        try {
            dockerClient
                    .disconnectFromNetworkCmd()
                    .withNetworkId(networkId)
                    .withContainerId(containerId)
                    .withForce(true)
                    .exec();
            log.debug("Disconnected container {} from network {}", containerId, networkId);
        } catch (NotFoundException | NotModifiedException e) {
            log.debug("Container {} already disconnected from network {}", containerId, networkId);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException(
                    "Failed to disconnect container " + containerId + " from network " + networkId, e);
        }
    }

    @Override
    public void removeNetwork(String networkId) {
        try {
            dockerClient.removeNetworkCmd(networkId).exec();
            log.debug("Removed network: {}", networkId);
        } catch (NotFoundException e) {
            log.debug("Network {} already removed", networkId);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to remove network: " + networkId, e);
        }
    }

    @Override
    public String createContainer(DockerOperations.ContainerSpec spec) {
        try {
            HostConfig hostConfig = buildHostConfig(spec.hostConfig());

            // Extra hosts (e.g. "host.docker.internal:host-gateway" for host access on Linux)
            if (spec.extraHosts() != null && !spec.extraHosts().isEmpty()) {
                hostConfig.withExtraHosts(spec.extraHosts().toArray(String[]::new));
            }

            CreateContainerCmd cmd = dockerClient
                    .createContainerCmd(spec.image())
                    .withHostConfig(hostConfig)
                    .withNetworkMode(spec.networkId())
                    .withLabels(spec.labels());

            if (spec.command() != null && !spec.command().isEmpty()) {
                cmd.withCmd(spec.command());
            }
            if (spec.environment() != null && !spec.environment().isEmpty()) {
                cmd.withEnv(spec.environment().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList());
            }
            if (spec.hostname() != null) {
                cmd.withHostName(spec.hostname());
            }
            if (spec.user() != null) {
                cmd.withUser(spec.user());
            }

            String containerId = cmd.exec().getId();
            log.debug("Created container: image={}, id={}", spec.image(), containerId);
            return containerId;
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to create container from image: " + spec.image(), e);
        }
    }

    @Override
    public void startContainer(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
            log.debug("Started container: {}", containerId);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to start container: " + containerId, e);
        }
    }

    @Override
    public DockerOperations.WaitResult waitContainer(String containerId) {
        try {
            var callback = streamingClient.waitContainerCmd(containerId).start();
            try {
                int exitCode = callback.awaitStatusCode();
                log.debug("Container {} exited with code {}", containerId, exitCode);
                return new DockerOperations.WaitResult(exitCode);
            } finally {
                try {
                    callback.close();
                } catch (Exception closeEx) {
                    log.debug("Failed to close wait callback for container {}: {}", containerId, closeEx.getMessage());
                }
            }
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to wait for container: " + containerId, e);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SandboxInfrastructureException("Failed waiting for container: " + containerId, e);
        }
    }

    @Override
    public String getLogs(String containerId, int tailLines) {
        // Best-effort log collection: an error returns nothing, and a timeout says so in the transcript.
        try {
            // Synchronised inside: onNext is called from a Docker callback thread.
            BoundedTranscript logs = new BoundedTranscript(MAX_LOG_CHARS / 2, MAX_LOG_CHARS / 2);
            var callback = new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame != null && frame.getPayload() != null) {
                        logs.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }
            };

            boolean complete;
            try {
                complete = dockerClient
                        .logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withFollowStream(false)
                        .withTail(tailLines > 0 ? tailLines : Integer.MAX_VALUE)
                        .exec(callback)
                        .awaitCompletion(LOG_COLLECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                callback.close();
            }
            if (!complete) {
                // Say so in the transcript itself: a reader who cannot tell a short run from an
                // abandoned collection cannot trust either.
                logs.append("\n[hephaestus] Log collection stopped after " + LOG_COLLECTION_TIMEOUT_SECONDS
                        + " seconds; this transcript ends early.\n");
            }

            return logs.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted collecting logs for container {}", containerId);
            return "";
        } catch (Exception e) {
            log.warn("Failed to collect logs for container {}: {}", containerId, e.getMessage());
            return "";
        }
    }

    @Override
    public void copyHostDirectoryToContainer(String containerId, String hostPath, String remotePath) {
        try {
            dockerClient
                    .copyArchiveToContainerCmd(containerId)
                    .withHostResource(hostPath)
                    .withRemotePath(remotePath)
                    .exec();
            log.debug("Copied host directory to container {}: {} -> {}", containerId, hostPath, remotePath);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException(
                    "Failed to copy directory to container " + containerId + ": " + hostPath + " -> " + remotePath, e);
        }
    }

    @Override
    public void copyArchiveToContainer(String containerId, String remotePath, InputStream tarStream) {
        try {
            dockerClient
                    .copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(tarStream)
                    .exec();
            log.debug("Copied archive to container {} at {}", containerId, remotePath);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException(
                    "Failed to copy archive to container " + containerId + " at " + remotePath, e);
        }
    }

    @Override
    public InputStream copyArchiveFromContainer(String containerId, String remotePath) {
        try {
            return dockerClient
                    .copyArchiveFromContainerCmd(containerId, remotePath)
                    .exec();
        } catch (NotFoundException e) {
            // A path the container never wrote is absent on every retry, so this is not infrastructure.
            throw new SandboxException("No archive at " + remotePath + " in container " + containerId, e);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException(
                    "Failed to copy archive from container " + containerId + " at " + remotePath, e);
        }
    }

    @Override
    public void stopContainer(String containerId, int timeoutSeconds) {
        try {
            dockerClient
                    .stopContainerCmd(containerId)
                    .withTimeout(timeoutSeconds)
                    .exec();
            log.debug("Stopped container {}: timeout={}s", containerId, timeoutSeconds);
        } catch (NotFoundException | NotModifiedException e) {
            log.debug("Container {} already stopped or removed", containerId);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to stop container: " + containerId, e);
        }
    }

    @Override
    public void removeContainer(String containerId, boolean force) {
        try {
            dockerClient
                    .removeContainerCmd(containerId)
                    .withForce(force)
                    .withRemoveVolumes(true)
                    .exec();
            log.debug("Removed container: id={}, force={}", containerId, force);
        } catch (NotFoundException e) {
            log.debug("Container {} already removed", containerId);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to remove container: " + containerId, e);
        }
    }

    @Override
    public List<DockerOperations.ContainerInfo> listContainersByLabel(String labelKey, String labelValue) {
        try {
            List<Container> containers = dockerClient
                    .listContainersCmd()
                    .withLabelFilter(Map.of(labelKey, labelValue))
                    .withShowAll(true)
                    .exec();
            return containers.stream()
                    .map(c -> new DockerOperations.ContainerInfo(
                            c.getId(),
                            c.getNames() != null && c.getNames().length > 0 ? c.getNames()[0] : "",
                            c.getLabels() != null ? c.getLabels() : Map.of(),
                            c.getState() != null ? c.getState() : "unknown",
                            c.getCreated() != null ? Instant.ofEpochSecond(c.getCreated()) : null))
                    .toList();
        } catch (DockerException e) {
            throw new SandboxInfrastructureException(
                    "Failed to list containers by label: " + labelKey + "=" + labelValue, e);
        }
    }

    @Override
    public List<DockerOperations.NetworkInfo> listNetworksByName(String namePrefix) {
        try {
            List<Network> networks =
                    dockerClient.listNetworksCmd().withNameFilter(namePrefix).exec();
            return networks.stream()
                    .map(n -> new DockerOperations.NetworkInfo(n.getId(), n.getName()))
                    .toList();
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Failed to list networks with prefix: " + namePrefix, e);
        }
    }

    // Internal helpers

    private HostConfig buildHostConfig(DockerOperations.HostConfigSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(spec.memoryBytes())
                .withMemorySwap(spec.memorySwapBytes())
                .withNanoCPUs(spec.nanoCpus())
                .withPidsLimit((long) spec.pidsLimit())
                .withReadonlyRootfs(spec.readonlyRootfs())
                .withPrivileged(spec.privileged());

        if (spec.capDrop() != null && !spec.capDrop().isEmpty()) {
            hostConfig.withCapDrop(
                    spec.capDrop().stream().map(Capability::valueOf).toArray(Capability[]::new));
        }

        if (spec.securityOpts() != null && !spec.securityOpts().isEmpty()) {
            hostConfig.withSecurityOpts(spec.securityOpts());
        }

        if (spec.tmpfsMounts() != null && !spec.tmpfsMounts().isEmpty()) {
            hostConfig.withTmpFs(spec.tmpfsMounts());
        }

        if (spec.dns() != null && !spec.dns().isEmpty()) {
            hostConfig.withDns(spec.dns().toArray(String[]::new));
        }

        if (spec.cgroupnsMode() != null) {
            hostConfig.withCgroupnsMode(spec.cgroupnsMode());
        }

        if (spec.ipcMode() != null) {
            hostConfig.withIpcMode(spec.ipcMode());
        }

        if (spec.runtime() != null && !spec.runtime().isBlank()) {
            hostConfig.withRuntime(spec.runtime());
        }

        if (spec.ulimits() != null && !spec.ulimits().isEmpty()) {
            hostConfig.withUlimits(spec.ulimits().entrySet().stream()
                    .map(e -> new Ulimit(
                            e.getKey(), e.getValue().soft(), e.getValue().hard()))
                    .toArray(Ulimit[]::new));
        }

        return hostConfig;
    }
}
