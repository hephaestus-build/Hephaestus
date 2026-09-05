package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates per-job networks and attaches the gateway container for sandbox access. */
public class SandboxNetworkManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxNetworkManager.class);
    static final String NETWORK_PREFIX = "agent-net-";

    private final DockerNetworkOperations networkOps;
    private final DockerSandboxProperties properties;
    private final Supplier<String> hostnameSupplier;

    private volatile @Nullable String appServerContainerId;

    public SandboxNetworkManager(DockerNetworkOperations networkOps, DockerSandboxProperties properties) {
        this(networkOps, properties, () -> System.getenv("HOSTNAME"));
    }

    SandboxNetworkManager(
            DockerNetworkOperations networkOps, DockerSandboxProperties properties, Supplier<String> hostnameSupplier) {
        this.networkOps = networkOps;
        this.properties = properties;
        this.hostnameSupplier = hostnameSupplier;
    }

    public String createJobNetwork(UUID jobId, boolean allowInternet) {
        String networkName = NETWORK_PREFIX + jobId;
        boolean internal = !allowInternet;
        String networkId = networkOps.createNetwork(networkName, internal);
        log.info("Created job network: name={}, internal={}, networkId={}", networkName, internal, networkId);
        return networkId;
    }

    /** Returns the gateway container's IP on this network, or null if its ID is unavailable. */
    public @Nullable String connectAppServer(String networkId) {
        String containerId = resolveAppServerContainerId();
        if (containerId == null || containerId.isBlank()) {
            log.warn("Cannot determine app-server container ID — app server is likely running on the host, "
                    + "not in Docker. Agent containers will use host.docker.internal to reach the LLM proxy. "
                    + "Set hephaestus.sandbox.docker.app-server-container-id to suppress this warning.");
            return null;
        }
        String ip = networkOps.connectToNetwork(networkId, containerId);
        log.info("Connected app-server to network {}: containerId={}, ip={}", networkId, containerId, ip);
        return ip;
    }

    /** Disconnect the app-server from a job network. Idempotent — no-op if already disconnected. */
    public void disconnectAppServer(String networkId) {
        String containerId = resolveAppServerContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        networkOps.disconnectFromNetwork(networkId, containerId);
    }

    public void removeNetwork(String networkId) {
        networkOps.removeNetwork(networkId);
    }

    public List<DockerOperations.NetworkInfo> listOrphanedNetworks() {
        return networkOps.listNetworksByName(NETWORK_PREFIX);
    }

    private String resolveAppServerContainerId() {
        if (appServerContainerId == null) {
            synchronized (this) {
                if (appServerContainerId == null) {
                    appServerContainerId = resolveContainerId();
                    if (appServerContainerId != null) {
                        log.info("Resolved app-server container ID: {}", appServerContainerId);
                    }
                }
            }
        }
        return appServerContainerId;
    }

    private String resolveContainerId() {
        String id = properties.resolvedAppServerContainerId();
        if (id != null) {
            return id;
        }
        // Docker defaults HOSTNAME to the container short ID.
        return hostnameSupplier.get();
    }
}
