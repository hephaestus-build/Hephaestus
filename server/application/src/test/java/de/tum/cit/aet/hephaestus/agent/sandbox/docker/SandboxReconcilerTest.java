package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private DockerVolumeOperations volumes;

    @Mock
    private DockerInspectOperations creatorContainers;

    private SandboxReconciler reconciler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        lenient().when(networkManager.networkPrefix()).thenReturn("hephaestus-sandbox-default--");
        lenient().when(networkManager.removeUnlessInUse(any(), any())).thenReturn(true);
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new SandboxReconciler(
                jobRepository,
                containerManager,
                networkManager,
                new SandboxVolumeManager(
                        volumes,
                        new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, "default")),
                new SandboxCreator(creatorContainers, () -> "reconciling-worker"),
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldRemoveOnlyAbandonedAttemptVolumesAfterTheCreationGrace() {
        UUID active = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        UUID starting = UUID.randomUUID();
        var job = new AgentJob();
        job.setId(active);
        when(jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
        when(containerManager.listManagedContainers()).thenReturn(List.of());
        when(volumes.listVolumes(any()))
                .thenReturn(List.of(
                        new DockerOperations.VolumeInfo(
                                "active",
                                Map.of(
                                        SandboxLabels.JOB_ID,
                                        active.toString(),
                                        SandboxLabels.CREATED_AT,
                                        LONG_AGO.toString())),
                        new DockerOperations.VolumeInfo(
                                "orphan",
                                Map.of(
                                        SandboxLabels.JOB_ID,
                                        orphan.toString(),
                                        SandboxLabels.CREATED_AT,
                                        LONG_AGO.toString())),
                        new DockerOperations.VolumeInfo(
                                "starting",
                                Map.of(
                                        SandboxLabels.JOB_ID,
                                        starting.toString(),
                                        SandboxLabels.CREATED_AT,
                                        NOW.toString()))));

        reconciler.onStartup();

        verify(volumes).removeVolume("orphan");
        verify(volumes, never()).removeVolume("active");
        verify(volumes, never()).removeVolume("starting");
    }

    /** A practice review's network, labelled as the batch adapter creates it. */
    private static DockerOperations.NetworkInfo jobNetwork(String id, UUID jobId, @Nullable Instant createdAt) {
        return new DockerOperations.NetworkInfo(
                id,
                "hephaestus-sandbox-default--" + jobId,
                createdAt,
                Map.of(SandboxLabels.OWNER, "default", SandboxLabels.JOB_ID, jobId.toString()));
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
                    .thenReturn(List.of(jobNetwork("net-1", orphanedJobId, LONG_AGO)));

            reconciler.onStartup();

            verify(containerManager).forceRemove("orphaned-ctr");
            verify(networkManager).removeUnlessInUse("net-1", "hephaestus-sandbox-default--" + orphanedJobId);
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
                    .thenReturn(List.of(jobNetwork(networkId, orphanedJobId, LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(networkManager).removeUnlessInUse(networkId, "hephaestus-sandbox-default--" + orphanedJobId);
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
                    .thenReturn(List.of(jobNetwork("net-live", UUID.randomUUID(), LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).removeUnlessInUse(any(), any());
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
                    .thenReturn(List.of(jobNetwork("net-live", jobId, LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(containerManager, never()).forceRemove(any());
            verify(networkManager, never()).removeUnlessInUse(any(), any());
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
                            "net-mentor",
                            "hephaestus-sandbox-default--" + sessionId,
                            LONG_AGO,
                            Map.of(SandboxLabels.SESSION_ID, sessionId.toString()))));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).removeUnlessInUse(any(), any());
        }

        @Test
        void shouldKeepAJobNetworkThatNoContainerClaimsYetWhileInsideTheGraceWindow() {
            // The job set is read before the containers and networks, so a job started since is not in it.
            UUID jobId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(jobNetwork("net-starting", jobId, NOW.minus(Duration.ofSeconds(2)))));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).removeUnlessInUse(any(), any());
        }

        @Test
        void shouldReapAJobNetworkThatNoContainerClaimedOnceTheGraceWindowHasPassed() {
            UUID jobId = UUID.randomUUID();
            String name = "hephaestus-sandbox-default--" + jobId;

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(jobNetwork("net-abandoned", jobId, NOW.minus(Duration.ofSeconds(121)))));

            reconciler.periodicReconciliation();

            verify(networkManager).removeUnlessInUse("net-abandoned", name);
        }

        @Test
        void shouldNotReapANetworkWhenTheDaemonReportedNoCreationTime() {
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listOrphanedNetworks())
                    .thenReturn(List.of(jobNetwork("net-ageless", UUID.randomUUID(), null)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).removeUnlessInUse(any(), any());
        }

        @Test
        void shouldKeepANetworkWhenItsContainerIsInsideTheGraceWindow() {
            UUID jobId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("ctr-young", jobId, NOW.minus(Duration.ofSeconds(30)))));
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of(jobNetwork("net-young", jobId, LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).removeUnlessInUse(any(), any());
        }

        @Test
        void shouldKeepANetworkWhenItsContainerCouldNotBeRemoved() {
            UUID jobId = UUID.randomUUID();

            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of(container("ctr-stuck", jobId, LONG_AGO)));
            doThrow(new RuntimeException("stuck container"))
                    .when(containerManager)
                    .forceRemove("ctr-stuck");
            when(networkManager.listOrphanedNetworks()).thenReturn(List.of(jobNetwork("net-stuck", jobId, LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(networkManager, never()).removeUnlessInUse(any(), any());
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

    /**
     * A real network manager and creator over the Docker boundary: what reaches the daemon is what is
     * asserted. The mentor admission creates its network and volumes minutes before any container
     * claims them — through an image pull, the initializer, and the handoff after the initializer is
     * removed and before the runtime is created — so the sweep below sees no claiming container.
     */
    @Nested
    class InteractiveAdmission {

        private static final String RECONCILER_SHORT_ID = "0a1b2c3d4e5f";
        private static final String RECONCILER_ID = RECONCILER_SHORT_ID + "6a7b8c9d0e1f";

        private static final UUID SESSION = UUID.randomUUID();
        private static final String NAME = "hephaestus-sandbox-default--" + SESSION;
        private static final Instant MINUTES_AGO = NOW.minus(Duration.ofMinutes(10));

        @Mock
        private DockerNetworkOperations networkOps;

        private SandboxReconciler sweeper;

        @BeforeEach
        void setUp() {
            var properties = new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, "default");
            // The reconciling worker, which is also the container joined to the networks it creates.
            lenient()
                    .when(creatorContainers.inspectContainerIdentity(RECONCILER_SHORT_ID))
                    .thenReturn(Optional.of(
                            new DockerOperations.ContainerIdentity(RECONCILER_ID, true, "t0", RECONCILER_SHORT_ID)));
            var creator = new SandboxCreator(creatorContainers, () -> RECONCILER_SHORT_ID);
            sweeper = new SandboxReconciler(
                    jobRepository,
                    containerManager,
                    new SandboxNetworkManager(networkOps, properties, creator),
                    new SandboxVolumeManager(volumes, properties),
                    creator,
                    meterRegistry,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
            when(containerManager.listManagedContainers()).thenReturn(List.of());
        }

        private void admissionCreatedBy(String container, String startedAt) {
            var labels = Map.of(
                    SandboxLabels.OWNER,
                    "default",
                    SandboxLabels.KIND,
                    SandboxLabels.KIND_INTERACTIVE,
                    SandboxLabels.SESSION_ID,
                    SESSION.toString(),
                    SandboxLabels.CREATOR_CONTAINER,
                    container,
                    SandboxLabels.CREATOR_STARTED_AT,
                    startedAt);
            when(networkOps.listNetworksByName("hephaestus-sandbox-default--"))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("net-session", NAME, MINUTES_AGO, labels)));
            // The attempt volumes carry whatever labels the workspace itself writes, created minutes ago.
            new DockerAttemptWorkspace(volumes, SESSION, labels);
            ArgumentCaptor<Map<String, String>> written = ArgumentCaptor.captor();
            verify(volumes, atLeastOnce()).createVolume(any(), written.capture());
            var volumeLabels = new HashMap<>(written.getValue());
            volumeLabels.put(SandboxLabels.CREATED_AT, MINUTES_AGO.toString());
            when(volumes.listVolumes(Map.of(
                            SandboxLabels.OWNER, "default", SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE)))
                    .thenReturn(List.of(new DockerOperations.VolumeInfo("vol-session", volumeLabels)));
        }

        private void creatorIs(String container, DockerOperations.@Nullable ContainerIdentity identity) {
            when(creatorContainers.inspectContainerIdentity(container)).thenReturn(Optional.ofNullable(identity));
        }

        private void assertUntouched() {
            verify(networkOps, never()).disconnectFromNetwork(any(), any());
            verify(networkOps, never()).removeNetwork(any());
            verify(volumes, never()).removeVolume(any());
        }

        @Test
        void shouldKeepTheNetworkAndVolumesWhileTheCreatorRunsLongAfterTheGraceWindow() {
            admissionCreatedBy("creator-worker", "t1");
            creatorIs(
                    "creator-worker",
                    new DockerOperations.ContainerIdentity("creator-worker", true, "t1", "creator-worker"));

            sweeper.periodicReconciliation();
            sweeper.onStartup();

            assertUntouched();
        }

        @Test
        void shouldKeepTheNetworkAndVolumesWhenTheCreatorCannotBeRead() {
            admissionCreatedBy("creator-worker", "t1");
            when(creatorContainers.inspectContainerIdentity("creator-worker"))
                    .thenThrow(new RuntimeException("daemon busy"));

            sweeper.periodicReconciliation();

            assertUntouched();
        }

        @Test
        void shouldKeepNetworksWhoseOwnerIsUnknownHoweverOld() {
            // One mentor network records no creator (its process could not identify itself); one predates labels.
            UUID legacy = UUID.randomUUID();
            when(networkOps.listNetworksByName("hephaestus-sandbox-default--"))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo(
                                    "net-unrecorded",
                                    NAME,
                                    MINUTES_AGO,
                                    Map.of(SandboxLabels.SESSION_ID, SESSION.toString())),
                            new DockerOperations.NetworkInfo(
                                    "net-legacy", "hephaestus-sandbox-default--" + legacy, MINUTES_AGO, Map.of())));

            sweeper.periodicReconciliation();

            verify(networkOps, never()).removeNetwork(any());
        }

        @Test
        void shouldReapTheNetworkAndVolumesWhenTheCreatorIsGone() {
            admissionCreatedBy("creator-worker", "t1");
            creatorIs("creator-worker", null);
            when(networkOps.inspectEndpoints("net-session"))
                    .thenReturn(List.of(new DockerOperations.NetworkEndpoint(RECONCILER_ID, "worker")));

            sweeper.periodicReconciliation();

            verify(networkOps).disconnectFromNetwork("net-session", RECONCILER_ID);
            verify(networkOps).removeNetwork("net-session");
            verify(volumes).removeVolume("vol-session");
        }

        @Test
        void shouldReapTheNetworkWhenTheCreatorHasRestartedOrStopped() {
            admissionCreatedBy("creator-worker", "t1");
            creatorIs(
                    "creator-worker",
                    new DockerOperations.ContainerIdentity("creator-worker", true, "t2", "creator-worker"));

            sweeper.periodicReconciliation();

            verify(networkOps).removeNetwork("net-session");
        }

        @Test
        void shouldKeepTheProxyConnectedWhenASandboxAttachedAfterTheInventory() {
            admissionCreatedBy("creator-worker", "t1");
            creatorIs(
                    "creator-worker",
                    new DockerOperations.ContainerIdentity("creator-worker", false, "t1", "creator-worker"));
            when(networkOps.inspectEndpoints("net-session"))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkEndpoint(RECONCILER_ID, "worker"),
                            new DockerOperations.NetworkEndpoint("runtime-full-id", "mentor-runtime")));

            sweeper.periodicReconciliation();

            verify(networkOps, never()).disconnectFromNetwork(any(), any());
            verify(networkOps, never()).removeNetwork(any());
        }

        @Test
        void shouldReapAnOldJobNetworkOnTheJobRulesAlone() {
            UUID jobId = UUID.randomUUID();
            when(networkOps.listNetworksByName("hephaestus-sandbox-default--"))
                    .thenReturn(List.of(jobNetwork("net-job", jobId, MINUTES_AGO)));

            sweeper.periodicReconciliation();

            verify(networkOps).removeNetwork("net-job");
            // Only the worker's own identity is read, to disconnect itself; no creator's liveness is asked.
            verify(creatorContainers, never()).inspectContainerIdentity(argThat(id -> !RECONCILER_SHORT_ID.equals(id)));
        }
    }
}
