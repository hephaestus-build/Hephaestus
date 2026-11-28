package de.tum.cit.aet.hephaestus.core.release;

/** Reads the rollout that runs this server, from the Kubernetes API. Only a server in a Deployment has one. */
public interface RolloutReader {
    /**
     * @throws RuntimeException when the API does not answer or the Pod has no Deployment; the next report reads again
     */
    Rollout read();
}
