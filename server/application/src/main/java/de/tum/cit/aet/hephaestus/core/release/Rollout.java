package de.tum.cit.aet.hephaestus.core.release;

/**
 * A rollout of the server Deployment, as Kubernetes names it.
 *
 * @param deploymentUid the UID of the Deployment; a new install, such as one into a fresh cluster, has a new UID
 * @param revision      the {@code deployment.kubernetes.io/revision} of the ReplicaSet that runs this server. The
 *                      Deployment controller gives every rollout the next revision, also {@code kubectl rollout undo}
 *                      and {@code helm rollback}, which scale an earlier ReplicaSet up again.
 */
public record Rollout(String deploymentUid, long revision) {}
