package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("unit")
class NativeGitVolumeReconcilerTest {
    @Test
    void shouldRemoveDeadWorkerAndUnmonitoredMirrorsButRetainAuthorizedLiveStorage() {
        var volumes = mock(DockerClientOperations.class);
        var properties = mock(DockerSandboxProperties.class);
        var worker = mock(WorkerProperties.class);
        var workers = mock(WorkerRegistryRepository.class);
        var repositories = mock(RepositoryRepository.class);
        var monitors = mock(RepositoryToMonitorRepository.class);
        when(properties.owner()).thenReturn("installation");
        when(worker.resolvedWorkerId()).thenReturn("local");
        when(workers.findLiveWorkerIds(anyLong())).thenReturn(List.of("other-live"));
        when(volumes.listVolumes(anyMap()))
                .thenReturn(List.of(
                        volume("dead-mirror", "dead", "1", "10"),
                        volume("removed-monitor", "local", "2", "10"),
                        volume("retained", "other-live", "3", "10")));
        var repository = new Repository();
        repository.setNameWithOwner("team/repo");
        when(repositories.findById(10L)).thenReturn(Optional.of(repository));
        when(monitors.existsByWorkspaceIdAndNameWithOwner(3L, "team/repo")).thenReturn(true);
        var reconciler = new NativeGitVolumeReconciler(volumes, properties, worker, workers, repositories, monitors);
        reconciler.reconcile();
        verify(volumes).removeVolume("dead-mirror");
        verify(volumes).removeVolume("removed-monitor");
        verify(volumes, never()).removeVolume("retained");
    }

    @Test
    void shouldRetryAnInUseVolumeWithoutSkippingOtherOrphans() {
        var volumes = mock(DockerClientOperations.class);
        var properties = mock(DockerSandboxProperties.class);
        var worker = mock(WorkerProperties.class);
        var workers = mock(WorkerRegistryRepository.class);
        when(properties.owner()).thenReturn("installation");
        when(worker.resolvedWorkerId()).thenReturn("local");
        when(workers.findLiveWorkerIds(anyLong())).thenReturn(List.of());
        when(volumes.listVolumes(anyMap()))
                .thenReturn(List.of(volume("mounted", "dead", "1", "10"), volume("abandoned", "dead", "1", "10")));
        doThrow(new IllegalStateException("volume in use")).when(volumes).removeVolume("mounted");
        var reconciler = new NativeGitVolumeReconciler(
                volumes,
                properties,
                worker,
                workers,
                mock(RepositoryRepository.class),
                mock(RepositoryToMonitorRepository.class));
        reconciler.reconcile();
        reconciler.reconcile();
        verify(volumes, times(2)).removeVolume("mounted");
        verify(volumes, times(2)).removeVolume("abandoned");
    }

    @Test
    void shouldReclaimOwnFinishedAndExpiredHelpersWithoutTouchingAnotherLiveWorker() {
        var docker = mock(DockerClientOperations.class);
        var properties = mock(DockerSandboxProperties.class);
        var worker = mock(WorkerProperties.class);
        var workers = mock(WorkerRegistryRepository.class);
        when(properties.owner()).thenReturn("installation");
        when(worker.resolvedWorkerId()).thenReturn("local");
        when(workers.findLiveWorkerIds(anyLong())).thenReturn(List.of("other-live"));
        when(docker.listContainersByLabel("hephaestus.owner", "installation"))
                .thenReturn(List.of(
                        container("finished", "local", "exited", -600),
                        container("expired", "local", "running", -600),
                        container("active", "local", "running", 600),
                        container("other-finished", "other-live", "exited", -600),
                        container("other-expired", "other-live", "running", -600),
                        container("dead-owner", "dead", "running", 600)));
        var reconciler = new NativeGitVolumeReconciler(
                docker,
                properties,
                worker,
                workers,
                mock(RepositoryRepository.class),
                mock(RepositoryToMonitorRepository.class));
        reconciler.reconcile();
        verify(docker).removeContainer("finished", true);
        verify(docker).removeContainer("expired", true);
        verify(docker).removeContainer("dead-owner", true);
        verify(docker, never()).removeContainer(eq("active"), anyBoolean());
        verify(docker, never()).removeContainer(eq("other-finished"), anyBoolean());
        verify(docker, never()).removeContainer(eq("other-expired"), anyBoolean());
    }

    private static DockerOperations.ContainerInfo container(
            String id, String worker, String state, long deadlineOffset) {
        return new DockerOperations.ContainerInfo(
                id,
                id,
                Map.of(
                        "hephaestus.component",
                        "git-preparation",
                        "hephaestus.worker",
                        worker,
                        "hephaestus.deadline",
                        Instant.now().plusSeconds(deadlineOffset).toString()),
                state,
                Instant.now().minusSeconds(1200));
    }

    private static DockerOperations.VolumeInfo volume(String name, String worker, String workspace, String repository) {
        return new DockerOperations.VolumeInfo(
                name,
                Map.of(
                        "hephaestus.worker",
                        worker,
                        "hephaestus.workspace",
                        workspace,
                        "hephaestus.repository",
                        repository,
                        "hephaestus.created-at",
                        Instant.now().minusSeconds(600).toString()));
    }
}
