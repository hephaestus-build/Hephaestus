package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.Optional;

/**
 * Read-only container identity — which container an id or name is, whether it runs, and since when —
 * so recording and checking a sandbox's creator depends on nothing that can change a container.
 */
interface DockerInspectOperations {

    /**
     * Inspect a container by id or name; empty when the daemon knows no such container. A response
     * missing the running state, or a running container's start time, is a failure rather than an
     * answer.
     */
    Optional<DockerOperations.ContainerIdentity> inspectContainerIdentity(String idOrName);
}
