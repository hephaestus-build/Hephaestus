package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.List;

/**
 * Network management operations against the Docker daemon.
 *
 * <p>Handles per-job network isolation: creation (internal or bridge), multi-homing the app-server,
 * and cleanup.
 */
interface DockerNetworkOperations {
    /**
     * Create a Docker network.
     *
     * @param name installation-scoped network name
     * @param internal if true, creates an {@code --internal} network with no external connectivity
     * @return the network ID
     */
    String createNetwork(String name, boolean internal);

    /**
     * Connect a container to a network and return its assigned IP.
     *
     * @param networkId the network to connect to
     * @param containerId the container to connect
     * @return the container's IP address on the network
     */
    String connectToNetwork(String networkId, String containerId);

    /** Disconnect a container from a network. No-op if already disconnected. */
    void disconnectFromNetwork(String networkId, String containerId);

    /** Remove a network. */
    void removeNetwork(String networkId);

    /** List networks whose name starts with the given prefix. */
    List<DockerOperations.NetworkInfo> listNetworksByName(String namePrefix);
}
