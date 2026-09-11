package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registered by {@link SandboxMaintenanceConfiguration}: a worker pod runs no server-wide scheduler. */
@WorkspaceAgnostic("Reconciles worker-local Git storage against tenant monitor ownership and worker leases")
public class NativeGitVolumeReconciler {
    private static final Logger log = LoggerFactory.getLogger(NativeGitVolumeReconciler.class);
    private final DockerVolumeOperations volumes;
    private final DockerContainerOperations containers;
    private final DockerSandboxProperties docker;
    private final WorkerProperties worker;
    private final WorkerRegistryRepository workers;
    private final RepositoryRepository repositories;
    private final RepositoryToMonitorRepository monitors;

    public NativeGitVolumeReconciler(
            DockerClientOperations volumes,
            DockerSandboxProperties docker,
            WorkerProperties worker,
            WorkerRegistryRepository workers,
            RepositoryRepository repositories,
            RepositoryToMonitorRepository monitors) {
        this.volumes = volumes;
        this.containers = volumes;
        this.docker = docker;
        this.worker = worker;
        this.workers = workers;
        this.repositories = repositories;
        this.monitors = monitors;
    }

    public void reconcile() {
        var alive = new HashSet<>(workers.findLiveWorkerIds(AgentProperties.WORKER_LEASE_TTL.toSeconds()));
        alive.add(worker.resolvedWorkerId());
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        for (var container : containers.listContainersByLabel(DockerNativeGitExecutor.OWNER_LABEL, docker.owner())) {
            if (!DockerNativeGitExecutor.COMPONENT.equals(container.labels().get(DockerNativeGitExecutor.COMPONENT_LABEL))) continue;
            String owner = container.labels().get(DockerNativeGitExecutor.WORKER_LABEL);
            if (owner == null
                    || container.createdAt() == null
                    || container.createdAt().isAfter(cutoff)) continue;
            try {
                if (alive.contains(owner)) {
                    if (!owner.equals(worker.resolvedWorkerId())) continue;
                    String deadline = container.labels().get(DockerNativeGitExecutor.DEADLINE_LABEL);
                    boolean finished = "exited".equals(container.state()) || "dead".equals(container.state());
                    if (!finished
                            && (deadline == null || Instant.parse(deadline).isAfter(cutoff))) continue;
                }
                containers.removeContainer(container.id(), true);
            } catch (RuntimeException failure) {
                log.warn("Could not reconcile native Git container {}", container.id(), failure);
            }
        }
        for (var volume : volumes.listVolumes(Map.of(
                DockerNativeGitExecutor.OWNER_LABEL,
                docker.owner(),
                DockerNativeGitExecutor.COMPONENT_LABEL,
                DockerNativeGitExecutor.COMPONENT))) {
            try {
                var labels = volume.labels();
                String created = labels.get(DockerNativeGitExecutor.CREATED_AT_LABEL);
                if (created == null || Instant.parse(created).isAfter(cutoff)) continue;
                String owner = labels.get(DockerNativeGitExecutor.WORKER_LABEL);
                if (owner != null && alive.contains(owner) && !owner.equals(worker.resolvedWorkerId())) continue;
                String repositoryId = labels.get(DockerNativeGitExecutor.REPOSITORY_LABEL);
                String workspaceId = labels.get(DockerNativeGitExecutor.WORKSPACE_LABEL);
                boolean retained = owner != null
                        && alive.contains(owner)
                        && repositoryId != null
                        && workspaceId != null
                        && repositories
                                .findById(Long.parseLong(repositoryId))
                                .map(repository -> monitors.existsByWorkspaceIdAndNameWithOwner(
                                        Long.parseLong(workspaceId), repository.getNameWithOwner()))
                                .orElse(false);
                if (!retained || volume.name().startsWith("hephaestus-git-snapshot-"))
                    volumes.removeVolume(volume.name());
            } catch (RuntimeException failure) {
                // Docker refuses removal while mounted; the next sweep retries after the operation exits.
                log.warn("Could not reconcile native Git volume {}", volume.name(), failure);
            }
        }
    }
}
