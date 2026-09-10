package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class SandboxReconcilerTest extends BaseUnitTest {

    @Mock
    private AgentJobRepository jobRepository;

    @Mock
    private SandboxContainerManager containerManager;

    @Mock
    private SandboxNetworkManager networkManager;

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    /** Older than the grace window, so a fixture is reapable unless it opts out. */
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(1));

    private SandboxReconciler reconciler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        lenient().when(networkManager.networkPrefix()).thenReturn("hephaestus-sandbox-default--");
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new SandboxReconciler(
                jobRepository, containerManager, networkManager, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DockerOperations.ContainerInfo container(String id, UUID jobId, @Nullable Instant createdAt) {
        return new DockerOperations.ContainerInfo(
                id,
                "test",
                Map.of(SandboxLabels.OWNER, "default", SandboxLabels.JOB_ID, jobId.toString()),
                "running",
                createdAt);
    }

    private static DockerOperations.ContainerInfo mentorContainer(String id, UUID sessionId) {
        return new DockerOperations.ContainerInfo(
                id,
                "test",
                Map.of(
                        SandboxLabels.OWNER,
                        "default",
                        SandboxLabels.KIND,
                        SandboxLabels.KIND_INTERACTIVE,
                        SandboxLabels.SESSION_ID,
                        sessionId.toString()),
                "running",
                LONG_AGO);
    }

    @Nested
    class StartupReconciliation {

        @Test
        void missingLocalContainerNeverChangesRunningJobState() {
            UUID jobId = UUID.randomUUID();
            AgentJob runningJob = new AgentJob();
            runningJob.setId(jobId);
            runningJob.setStatus(AgentJobStatus.RUNNING);

            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of(runningJob));
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.onStartup();

            assertThat(runningJob.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
            verify(jobRepository, never()).save(any());
            verify(jobRepository, never()).findByStatus(AgentJobStatus.RUNNING);
        }

        @Test
        void shouldCleanupDockerResourcesDuringStartup() {
            UUID orphanedJobId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("orphaned-ctr", orphanedJobId, LONG_AGO)));
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("net-1", "hephaestus-sandbox-default--" + orphanedJobId)));

            reconciler.onStartup();

            verify(containerManager).forceRemove("orphaned-ctr");
            verify(networkManager).forceRemoveNetwork("net-1", "hephaestus-sandbox-default--" + orphanedJobId);
        }

        @Test
        void shouldNotMarkActiveContainerJobs() {
            UUID jobId = UUID.randomUUID();
            AgentJob activeJob = new AgentJob();
            activeJob.setId(jobId);
            activeJob.setStatus(AgentJobStatus.RUNNING);
            // containerId NOT set — label-based matching should still find the container

            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("active-container", jobId, LONG_AGO)));
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of(activeJob));
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.onStartup();

            verify(jobRepository, never()).save(any());
        }

        @Test
        void shouldDoNothingWithNoManagedResources() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.onStartup();

            verify(jobRepository, never()).save(any());
        }
    }

    @Nested
    class PeriodicReconciliation {

        @Test
        void shouldRemoveOrphanedContainers() {
            UUID orphanedJobId = UUID.randomUUID();
            String orphanedContainerId = "orphaned-container";

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());

            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container(orphanedContainerId, orphanedJobId, LONG_AGO)));

            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            verify(containerManager).forceRemove(orphanedContainerId);
            assertThat(meterRegistry
                            .counter("sandbox.reconciler.orphaned", "resource", "container")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void shouldNotRemoveActiveContainers() {
            UUID activeJobId = UUID.randomUUID();
            String containerId = "active-container";

            AgentJob activeJob = new AgentJob();
            activeJob.setId(activeJobId);
            activeJob.setStatus(AgentJobStatus.RUNNING);

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of(activeJob));

            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container(containerId, activeJobId, LONG_AGO)));

            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            verify(containerManager, never()).forceRemove(containerId);
        }

        @Test
        void shouldRemoveOrphanedNetworks() {
            UUID orphanedJobId = UUID.randomUUID();
            String networkId = "net-orphaned";

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());

            when(containerManager.listManagedContainers()).thenReturn(List.of());

            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            networkId, "hephaestus-sandbox-default--" + orphanedJobId)));

            reconciler.periodicReconciliation();

            verify(networkManager).forceRemoveNetwork(networkId, "hephaestus-sandbox-default--" + orphanedJobId);
            assertThat(meterRegistry
                            .counter("sandbox.reconciler.orphaned", "resource", "network")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("should continue cleaning other containers when one fails")
        void shouldContinueOnContainerCleanupFailure() {
            UUID jobId1 = UUID.randomUUID();
            UUID jobId2 = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());

            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-1", jobId1, LONG_AGO), container("ctr-2", jobId2, LONG_AGO)));

            doThrow(new RuntimeException("stuck container"))
                    .when(containerManager)
                    .forceRemove("ctr-1");

            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            verify(containerManager).forceRemove("ctr-2");
        }

        @Test
        void shouldReapNoNetworksWhenTheContainerInventoryCannotBeRead() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenThrow(new RuntimeException("Docker unreachable"));
            // Orphaned by the job set alone; only the unreadable inventory can spare it.
            lenient()
                    .when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            "net-live", "hephaestus-sandbox-default--" + UUID.randomUUID())));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).forceRemoveNetwork(any(), any());
            assertThat(meterRegistry
                            .counter("sandbox.reconciler.sweeps", "outcome", "skipped")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void shouldReapNothingWhenTheActiveJobSetCannotBeRead() {
            UUID jobId = UUID.randomUUID();

            // Resources that a sweep running on an empty job set would reap, so the assertions below
            // distinguish "stood down" from "ran and found nothing".
            when(jobRepository.findByStatusIn(any())).thenThrow(new RuntimeException("DB unreachable"));
            lenient()
                    .when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-live", jobId, LONG_AGO)));
            lenient()
                    .when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("net-live", "hephaestus-sandbox-default--" + jobId)));

            reconciler.periodicReconciliation();

            verify(containerManager, never()).forceRemove(any());
            verify(networkManager, never()).forceRemoveNetwork(any(), any());
            assertThat(meterRegistry
                            .counter("sandbox.reconciler.sweeps", "outcome", "skipped")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void shouldNotReapAContainerWhenItIsYoungerThanTheGraceWindow() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-young", UUID.randomUUID(), NOW.minus(Duration.ofSeconds(119)))));
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            verify(containerManager, never()).forceRemove(any());
        }

        @Test
        void shouldReapAContainerWhenItIsOlderThanTheGraceWindow() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-old", UUID.randomUUID(), NOW.minus(Duration.ofSeconds(121)))));
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            verify(containerManager).forceRemove("ctr-old");
        }

        @Test
        void shouldNotReapAContainerWhenTheDaemonReportedNoCreationTime() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-ageless", UUID.randomUUID(), null)));
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            verify(containerManager, never()).forceRemove(any());
        }

        @Test
        void shouldKeepANetworkWhenALiveMentorSessionOwnsIt() {
            UUID sessionId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(mentorContainer("ctr-mentor", sessionId)));
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            "net-mentor", "hephaestus-sandbox-default--" + sessionId)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).forceRemoveNetwork(any(), any());
        }

        @Test
        void shouldKeepANetworkWhenItsContainerIsInsideTheGraceWindow() {
            UUID jobId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-young", jobId, NOW.minus(Duration.ofSeconds(30)))));
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("net-young", "hephaestus-sandbox-default--" + jobId)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).forceRemoveNetwork(any(), any());
        }

        @Test
        void shouldKeepANetworkWhenItsContainerCouldNotBeRemoved() {
            UUID jobId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of(container("ctr-stuck", jobId, LONG_AGO)));
            doThrow(new RuntimeException("stuck container"))
                    .when(containerManager)
                    .forceRemove("ctr-stuck");
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("net-stuck", "hephaestus-sandbox-default--" + jobId)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).forceRemoveNetwork(any(), any());
        }

        @Test
        void shouldCountASweepThatRanToCompletion() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            assertThat(meterRegistry
                            .counter("sandbox.reconciler.sweeps", "outcome", "completed")
                            .count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry
                            .counter("sandbox.reconciler.sweeps", "outcome", "skipped")
                            .count())
                    .isZero();
        }

        @Test
        void shouldRecordReconciliationDuration() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of());

            reconciler.periodicReconciliation();

            assertThat(meterRegistry.timer("sandbox.reconciler.duration").count())
                    .isEqualTo(1);
        }
    }
}
