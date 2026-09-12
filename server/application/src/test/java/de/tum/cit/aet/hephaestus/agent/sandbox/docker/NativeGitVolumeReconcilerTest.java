package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class NativeGitVolumeReconcilerTest extends BaseUnitTest {
    @Mock
    private DockerClientOperations docker;

    @Mock
    private DockerSandboxProperties properties;

    @Mock
    private WorkerProperties worker;

    @Mock
    private WorkerRegistryRepository workers;

    @Mock
    private RepositoryRepository repositories;

    @Mock
    private RepositoryToMonitorRepository monitors;

    private NativeGitVolumeReconciler reconciler;

    @BeforeEach
    void localWorker() {
        when(properties.owner()).thenReturn("installation");
        when(worker.resolvedWorkerId()).thenReturn("local");
        reconciler = new NativeGitVolumeReconciler(docker, properties, worker, workers, repositories, monitors);
    }

    @Test
    void shouldRemoveDeadWorkerAndUnmonitoredMirrorsButRetainMonitoredOwnStorage() {
        when(workers.findLiveWorkerIds(anyLong())).thenReturn(List.of("other-live"));
        when(docker.listVolumes(anyMap()))
                .thenReturn(List.of(
                        volume("dead-mirror", "dead", "1", "10"),
                        volume("removed-monitor", "local", "2", "10"),
                        volume("retained", "local", "3", "10"),
                        volume("other-workers", "other-live", "4", "10")));
        var repository = new Repository();
        repository.setNameWithOwner("team/repo");
        when(repositories.findById(10L)).thenReturn(Optional.of(repository));
        when(monitors.existsByWorkspaceIdAndNameWithOwner(3L, "team/repo")).thenReturn(true);
        when(monitors.existsByWorkspaceIdAndNameWithOwner(2L, "team/repo")).thenReturn(false);

        reconciler.reconcile();

        verify(docker).removeVolume("dead-mirror");
        verify(docker).removeVolume("removed-monitor");
        verify(docker, never()).removeVolume("retained");
        verify(docker, never()).removeVolume("other-workers");
    }

    @Test
    void shouldRetryAnInUseVolumeWithoutSkippingOtherOrphans() {
        when(workers.findLiveWorkerIds(anyLong())).thenReturn(List.of());
        when(docker.listVolumes(anyMap()))
                .thenReturn(List.of(volume("mounted", "dead", "1", "10"), volume("abandoned", "dead", "1", "10")));
        doThrow(new IllegalStateException("volume in use")).when(docker).removeVolume("mounted");

        reconciler.reconcile();
        reconciler.reconcile();

        verify(docker, times(2)).removeVolume("mounted");
        verify(docker, times(2)).removeVolume("abandoned");
    }

    @Test
    void shouldReclaimOwnFinishedAndExpiredHelpersWithoutTouchingAnotherLiveWorker() {
        when(workers.findLiveWorkerIds(anyLong())).thenReturn(List.of("other-live"));
        when(docker.listContainersByLabel(SandboxLabels.GIT_OWNER, "installation"))
                .thenReturn(List.of(
                        container("finished", "local", "exited", -600),
                        container("expired", "local", "running", -600),
                        container("active", "local", "running", 600),
                        container("other-finished", "other-live", "exited", -600),
                        container("other-expired", "other-live", "running", -600),
                        container("dead-owner", "dead", "running", 600)));

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
                        SandboxLabels.GIT_COMPONENT,
                        SandboxLabels.GIT_COMPONENT_PREPARATION,
                        SandboxLabels.GIT_WORKER,
                        worker,
                        SandboxLabels.GIT_DEADLINE,
                        Instant.now().plusSeconds(deadlineOffset).toString()),
                state,
                Instant.now().minusSeconds(1200));
    }

    private static DockerOperations.VolumeInfo volume(String name, String worker, String workspace, String repository) {
        return new DockerOperations.VolumeInfo(
                name,
                Map.of(
                        SandboxLabels.GIT_WORKER,
                        worker,
                        SandboxLabels.GIT_WORKSPACE,
                        workspace,
                        SandboxLabels.GIT_REPOSITORY,
                        repository,
                        SandboxLabels.CREATED_AT,
                        Instant.now().minusSeconds(600).toString()));
    }
}
