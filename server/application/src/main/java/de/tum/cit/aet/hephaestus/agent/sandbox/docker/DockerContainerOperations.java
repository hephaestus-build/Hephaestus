package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.List;

/**
 * Container lifecycle operations against the Docker daemon.
 *
 * <p>Covers container creation, execution, monitoring, and cleanup. Also includes health check
 * (ping) used by readiness probes.
 */
interface DockerContainerOperations {
    /** Ping the Docker daemon to verify connectivity. */
    boolean ping();

    /**
     * Create a container (does not start it).
     *
     * @return the container ID
     */
    String createContainer(DockerOperations.ContainerSpec spec);

    /** Start a created container. */
    void startContainer(String containerId);

    /**
     * Block until the container exits or the caller interrupts.
     *
     * @return the exit status
     */
    DockerOperations.WaitResult waitContainer(String containerId);

    /**
     * Every line of the container's logs, bounded by the collector: the daemon applies a tail before the collector sees
     * a line, so a tail would drop the transcript's beginning — what the run was asked and what it read.
     *
     * @return combined stdout + stderr
     */
    String getLogs(String containerId);

    /**
     * Stop a container with a grace period (SIGTERM → wait → SIGKILL).
     *
     * @param timeoutSeconds seconds to wait after SIGTERM before SIGKILL
     */
    void stopContainer(String containerId, int timeoutSeconds);

    /**
     * Remove a container. No-op if the container does not exist.
     *
     * @param force kill the container if it's still running before removing
     */
    void removeContainer(String containerId, boolean force);

    /** List containers matching a label key=value pair. */
    List<DockerOperations.ContainerInfo> listContainersByLabel(String labelKey, String labelValue);
}
