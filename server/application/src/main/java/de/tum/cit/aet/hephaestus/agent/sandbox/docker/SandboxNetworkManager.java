package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    public String networkPrefix() {
        return "hephaestus-sandbox-" + properties.owner() + "--";
    }

    private final DockerNetworkOperations networkOps;
    private final DockerSandboxProperties properties;
    private final SandboxCreator creator;

    public SandboxNetworkManager(
            DockerNetworkOperations networkOps, DockerSandboxProperties properties, SandboxCreator creator) {
        this.networkOps = networkOps;
        this.properties = properties;
        this.creator = creator;
    }

    /**
     * Create an isolated network for a job.
     *
     * @param jobId unique job identifier (used in network name)
     * @param allowInternet if false, network is {@code --internal} (no external access)
     * @param labels set on the network as it is created
     * @return the Docker network ID
     */
    public String createJobNetwork(UUID jobId, boolean allowInternet, Map<String, String> labels) {
        String networkName = networkPrefix() + jobId;
        boolean internal = !allowInternet;
        removeLeftoverNetwork(networkName);
        String networkId = networkOps.createNetwork(networkName, internal, labels);
        log.info("Created job network: name={}, internal={}, networkId={}", networkName, internal, networkId);
        return networkId;
    }

    /**
     * Connect the container this process runs in, which serves the sandbox gateway, to a job network and
     * return its IP there. Only that container joins: it is the one {@link SandboxCreator} identifies, so
     * no configuration can attach another.
     *
     * <p>The agent container uses this IP as the gateway and LLM proxy endpoint.
     *
     * @param networkId the network to connect to
     * @return this container's IP address on the network, or null when this process runs outside Docker
     */
    public @Nullable String connectAppServer(String networkId) {
        String containerId = resolveAppServerContainerId();
        if (containerId == null) {
            log.warn("This process does not run in an identifiable container, so it joins no job network; agent "
                    + "containers will reach the LLM proxy through host.docker.internal.");
            return null;
        }
        String ip = networkOps.connectToNetwork(networkId, containerId);
        log.info("Connected app-server to network {}: containerId={}, ip={}", networkId, containerId, ip);
        return ip;
    }

    /**
     * A network under this job's own name is left from an earlier run of this same job, and nothing
     * else reclaims it: Docker refuses the duplicate name, and the reconciler spares a network whose
     * job is still queued — which a job retrying on that very conflict is. An attempt that was
     * requeued as orphaned can still be running on a worker whose heartbeat only lapsed, but that
     * attempt is superseded already. A mentor session's network is replaced only when this process
     * created it or its recorded creator is positively gone, and a network a sandbox is still attached to
     * is never taken. Both fail loudly rather than pulling the network out from under it.
     */
    private void removeLeftoverNetwork(String networkName) {
        List<DockerOperations.NetworkInfo> candidates;
        try {
            candidates = networkOps.listNetworksByName(networkName);
        } catch (RuntimeException e) {
            // A probe that cannot answer is not a leftover, and createNetwork still refuses a duplicate name.
            log.debug("Could not check for a leftover network {}: {}", networkName, e.getMessage());
            return;
        }
        for (DockerOperations.NetworkInfo leftover : candidates) {
            if (!networkName.equals(leftover.name())) {
                continue; // the daemon's name filter is not exact; only this exact name is ours to remove
            }
            if (leftover.labels().containsKey(SandboxLabels.SESSION_ID)
                    && !creator.createdBySelf(leftover.labels())
                    && creator.liveness(leftover.labels()) != SandboxCreator.Liveness.GONE) {
                throw new SandboxInfrastructureException(
                        "Network " + networkName
                                + " belongs to a mentor session whose creating application container is still running or"
                                + " cannot be identified; end the session there, or remove the network once no container uses it");
            }
            log.warn("Removing the network an interrupted run left behind: name={}, id={}", networkName, leftover.id());
            if (!removeUnlessInUse(leftover.id(), networkName)) {
                throw new SandboxInfrastructureException("Network " + networkName + " is still attached to a sandbox");
            }
        }
    }

    /** This container among a network's endpoints, by the full id Docker reports for both. */
    private static Optional<DockerOperations.NetworkEndpoint> appServerEndpoint(
            List<DockerOperations.NetworkEndpoint> endpoints, @Nullable String appServer) {
        return endpoints.stream()
                .filter(endpoint -> endpoint.containerId().equals(appServer))
                .findFirst();
    }

    /** Disconnect the app-server from a job network. Idempotent — no-op if already disconnected. */
    public void disconnectAppServer(String networkId) {
        String containerId = resolveAppServerContainerId();
        if (containerId == null) {
            return;
        }
        networkOps.disconnectFromNetwork(networkId, containerId);
    }

    public void removeNetwork(String networkId) {
        networkOps.removeNetwork(networkId);
    }

    /**
     * Remove a network nothing but the app-server is attached to, and report whether it was. A run that
     * never cleaned up leaves the app-server on its network, and Docker refuses to remove a network
     * with any endpoint — but only after the app-server has been disconnected, which would cut the
     * model proxy of a sandbox attached since the caller last looked. So the endpoints are read fresh
     * first. A disconnect that fails is not worth stopping for — the removal reports what the daemon
     * actually refuses.
     *
     * @param name the network name, for the log line only
     */
    public boolean removeUnlessInUse(String networkId, String name) {
        List<DockerOperations.NetworkEndpoint> endpoints = networkOps.inspectEndpoints(networkId);
        Optional<DockerOperations.NetworkEndpoint> appServer =
                appServerEndpoint(endpoints, resolveAppServerContainerId());
        if (endpoints.stream().anyMatch(endpoint -> !appServer.equals(Optional.of(endpoint)))) {
            return false;
        }
        try {
            disconnectAppServer(networkId);
        } catch (RuntimeException e) {
            log.debug("Could not disconnect app-server from {}: {}", name, e.getMessage());
        }
        networkOps.removeNetwork(networkId);
        return true;
    }

    /** List candidate networks owned by this installation. */
    public List<DockerOperations.NetworkInfo> listOrphanedNetworks() {
        return networkOps.listNetworksByName(networkPrefix()).stream()
                .filter(network -> network.name().startsWith(networkPrefix())
                        && network.name().length() == networkPrefix().length() + 36)
                .toList();
    }

    private @Nullable String resolveAppServerContainerId() {
        return creator.labels().get(SandboxLabels.CREATOR_CONTAINER);
    }
}
