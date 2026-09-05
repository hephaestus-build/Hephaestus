package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages per-job Docker networks for sandbox isolation.
 *
 * <p>For {@code allowInternet=false}: creates an {@code --internal} network with zero external
 * connectivity. The app-server container is multi-homed onto the network so agent containers can
 * reach the LLM proxy.
 *
 * <p>For {@code allowInternet=true}: creates a normal bridge network. The app-server is still
 * connected to provide LLM proxy access.
 */
public class SandboxNetworkManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxNetworkManager.class);
    static final String NETWORK_PREFIX = "agent-net-";

    private final DockerNetworkOperations networkOps;
    private final DockerSandboxProperties properties;
    private final Supplier<String> hostnameSupplier;

    /** Resolved once at startup; cached for the lifetime of the bean. */
    private volatile @Nullable String appServerContainerId;

    public SandboxNetworkManager(DockerNetworkOperations networkOps, DockerSandboxProperties properties) {
        this(networkOps, properties, () -> System.getenv("HOSTNAME"));
    }

    /**
     * @param hostnameSupplier provides the container HOSTNAME fallback (testable seam)
     */
    SandboxNetworkManager(
            DockerNetworkOperations networkOps, DockerSandboxProperties properties, Supplier<String> hostnameSupplier) {
        this.networkOps = networkOps;
        this.properties = properties;
        this.hostnameSupplier = hostnameSupplier;
    }

    /**
     * Create an isolated network for a job.
     *
     * @param jobId unique job identifier (used in network name)
     * @param allowInternet if false, network is {@code --internal} (no external access)
     * @return the Docker network ID
     */
    public String createJobNetwork(UUID jobId, boolean allowInternet) {
        String networkName = NETWORK_PREFIX + jobId;
        boolean internal = !allowInternet;
        String networkId = networkOps.createNetwork(networkName, internal);
        log.info("Created job network: name={}, internal={}, networkId={}", networkName, internal, networkId);
        return networkId;
    }

    /**
     * Connect the app-server container to a job network and return its IP.
     *
     * <p>The agent container uses this IP as the LLM proxy endpoint.
     *
     * @param networkId the network to connect to
     * @return the app-server's IP address on the network
     */
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

    /** Remove a job network. */
    public void removeNetwork(String networkId) {
        networkOps.removeNetwork(networkId);
    }

    /** List orphaned job networks (matching the agent-net-* prefix). */
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
        // Fall back to HOSTNAME env var — Docker sets this to the container short ID
        return hostnameSupplier.get();
    }
}
