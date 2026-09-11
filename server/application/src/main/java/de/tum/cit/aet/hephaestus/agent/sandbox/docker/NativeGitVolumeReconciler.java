package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
@WorkspaceAgnostic("Reconciles worker-local Git storage against tenant monitor ownership and worker leases")
public class NativeGitVolumeReconciler {
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

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    public void reconcile() {
        var alive = new HashSet<>(workers.findLiveWorkerIds(AgentProperties.WORKER_LEASE_TTL.toSeconds()));
        alive.add(worker.resolvedWorkerId());
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        for (var container : containers.listContainersByLabel("hephaestus.owner", docker.owner())) {
            if (!"git-preparation".equals(container.labels().get("hephaestus.component"))) continue;
            String owner = container.labels().get("hephaestus.worker");
            if (owner == null
                    || container.createdAt() == null
                    || container.createdAt().isAfter(cutoff)) continue;
            try {
                if (alive.contains(owner)) {
                    if (!owner.equals(worker.resolvedWorkerId())) continue;
                    String deadline = container.labels().get("hephaestus.deadline");
                    boolean finished = "exited".equals(container.state()) || "dead".equals(container.state());
                    if (!finished
                            && (deadline == null || Instant.parse(deadline).isAfter(cutoff))) continue;
                }
                containers.removeContainer(container.id(), true);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(NativeGitVolumeReconciler.class)
                        .warn("Could not reconcile native Git container {}", container.id());
            }
        }
        for (var volume : volumes.listVolumes(
                Map.of("hephaestus.owner", docker.owner(), "hephaestus.component", "git-preparation"))) {
            try {
                var labels = volume.labels();
                String created = labels.get("hephaestus.created-at");
                if (created == null || Instant.parse(created).isAfter(cutoff)) continue;
                String owner = labels.get("hephaestus.worker");
                if (owner != null && alive.contains(owner) && !owner.equals(worker.resolvedWorkerId())) continue;
                String repositoryId = labels.get("hephaestus.repository");
                String workspaceId = labels.get("hephaestus.workspace");
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
                LoggerFactory.getLogger(NativeGitVolumeReconciler.class)
                        .warn("Could not reconcile native Git volume {}", volume.name());
            }
        }
    }
}
