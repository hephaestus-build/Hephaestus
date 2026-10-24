package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxReconciler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * {@link SandboxReconciler} over the Docker backend, on the daemon's own records: what it removes of the containers,
 * networks and attempt volumes that this installation's labels name, and what it keeps.
 */
class DockerSandboxReconciliationTest extends BaseUnitTest {

    private static final String OWNER = "default";
    private static final String PREFIX = "hephaestus-sandbox-default--";
    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    /** Older than the grace window, so a fixture is reapable unless it opts out. */
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(1));

    private static final String RECONCILER_SHORT_ID = "0a1b2c3d4e5f";
    private static final String RECONCILER_ID = RECONCILER_SHORT_ID + "6a7b8c9d0e1f";

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private SandboxContainerManager containerManager;

    @Mock
    private DockerNetworkOperations networkOps;

    @Mock
    private DockerVolumeOperations volumes;

    @Mock
    private DockerInspectOperations creatorContainers;

    @Mock
    private ContainerSecurityPolicy securityPolicy;

    private SandboxReconciler reconciler;

    @BeforeEach
    void setUp() {
        var properties = new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, OWNER);
        // The reconciling worker, which is also the container joined to the networks it creates.
        lenient()
                .when(creatorContainers.inspectContainerIdentity(RECONCILER_SHORT_ID))
                .thenReturn(Optional.of(
                        new DockerOperations.ContainerIdentity(RECONCILER_ID, true, "t0", RECONCILER_SHORT_ID)));
        var creator = new SandboxCreator(creatorContainers, () -> RECONCILER_SHORT_ID);
        var backend = new DockerSandboxBackend(
                new SandboxNetworkManager(networkOps, properties, creator),
                containerManager,
                securityPolicy,
                volumes,
                creator,
                OWNER,
                () -> 8081);
        reconciler = new SandboxReconciler(
                jobRepository, backend, new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(containerManager.listManagedContainers()).thenReturn(List.of());
        lenient().when(networkOps.listNetworksByName(PREFIX)).thenReturn(List.of());
        lenient().when(volumes.listVolumes(any())).thenReturn(List.of());
    }

    private void activeJobs(UUID... ids) {
        when(jobRepository.findByStatusIn(any()))
                .thenReturn(Arrays.stream(ids)
                        .map(id -> {
                            var job = new ReviewJob();
                            job.setId(id);
                            return job;
                        })
                        .toList());
    }

    private static Map<String, String> jobLabels(UUID jobId) {
        return Map.of(SandboxLabels.OWNER, OWNER, SandboxLabels.JOB_ID, jobId.toString());
    }

    private static DockerOperations.ContainerInfo container(String id, UUID jobId, @Nullable Instant createdAt) {
        return new DockerOperations.ContainerInfo(id, id, jobLabels(jobId), "running", createdAt);
    }

    private static DockerOperations.NetworkInfo jobNetwork(String id, UUID jobId, @Nullable Instant createdAt) {
        return new DockerOperations.NetworkInfo(id, PREFIX + jobId, createdAt, jobLabels(jobId));
    }

    private static DockerOperations.VolumeInfo volume(String name, UUID jobId, Instant createdAt) {
        Map<String, String> labels = new HashMap<>(jobLabels(jobId));
        labels.put(SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE);
        labels.put(SandboxLabels.CREATED_AT, createdAt.toString());
        return new DockerOperations.VolumeInfo(name, labels);
    }

    @Nested
    class Reviews {

        @Test
        void shouldRemoveTheContainersOfAJobThatEndedAndKeepThoseOfAnActiveJob() {
            UUID active = UUID.randomUUID();
            UUID ended = UUID.randomUUID();
            activeJobs(active);
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("active", active, LONG_AGO), container("ended", ended, LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(containerManager).forceRemove("ended");
            verify(containerManager, never()).forceRemove("active");
        }

        @Test
        void shouldKeepASandboxInsideTheGraceWindowOrOfUnknownAge() {
            activeJobs();
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(
                            container("young", UUID.randomUUID(), NOW.minusSeconds(30)),
                            container("ageless", UUID.randomUUID(), null)));

            reconciler.onStartup();

            verify(containerManager, never()).forceRemove(any());
        }

        @Test
        void shouldRemoveOnlyAbandonedAttemptVolumesAfterTheCreationGrace() {
            UUID active = UUID.randomUUID();
            UUID orphan = UUID.randomUUID();
            UUID starting = UUID.randomUUID();
            activeJobs(active);
            when(volumes.listVolumes(any()))
                    .thenReturn(List.of(
                            volume("active", active, LONG_AGO),
                            volume("orphan", orphan, LONG_AGO),
                            volume("starting", starting, NOW)));

            reconciler.onStartup();

            verify(volumes).removeVolume("orphan");
            verify(volumes, never()).removeVolume("active");
            verify(volumes, never()).removeVolume("starting");
        }

        @Test
        void shouldReapAnOldJobNetworkOnTheJobRulesAlone() {
            UUID jobId = UUID.randomUUID();
            activeJobs();
            when(networkOps.listNetworksByName(PREFIX)).thenReturn(List.of(jobNetwork("net-job", jobId, LONG_AGO)));

            reconciler.periodicReconciliation();

            verify(networkOps).removeNetwork("net-job");
            // Only the worker's own identity is read, to disconnect itself; no creator's liveness is asked.
            verify(creatorContainers, never()).inspectContainerIdentity(argThat(id -> !RECONCILER_SHORT_ID.equals(id)));
        }

        @Test
        void shouldKeepAJobNetworkWhileItsContainerCouldNotBeRemoved() {
            UUID jobId = UUID.randomUUID();
            activeJobs();
            when(containerManager.listManagedContainers()).thenReturn(List.of(container("stuck", jobId, LONG_AGO)));
            doThrow(new IllegalStateException("daemon refused"))
                    .when(containerManager)
                    .forceRemove("stuck");
            when(networkOps.listNetworksByName(PREFIX)).thenReturn(List.of(jobNetwork("net-stuck", jobId, LONG_AGO)));
            when(networkOps.inspectEndpoints("net-stuck"))
                    .thenReturn(List.of(new DockerOperations.NetworkEndpoint("stuck-full-id", "agent")));

            reconciler.periodicReconciliation();

            verify(networkOps, never()).removeNetwork(any());
        }
    }

    @Nested
    class Hosts {

        private static final UUID SESSION = UUID.randomUUID();
        private static final Instant MINUTES_AGO = NOW.minus(Duration.ofMinutes(10));

        @BeforeEach
        void noJobs() {
            activeJobs();
        }

        private void hostCreatedBy(String container, String startedAt) {
            var labels = Map.of(
                    SandboxLabels.OWNER,
                    OWNER,
                    SandboxLabels.KIND,
                    SandboxLabels.KIND_HEPH_HOST,
                    SandboxLabels.SESSION_ID,
                    SESSION.toString(),
                    SandboxLabels.CREATOR_CONTAINER,
                    container,
                    SandboxLabels.CREATOR_STARTED_AT,
                    startedAt);
            when(networkOps.listNetworksByName(PREFIX))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("net-session", PREFIX + SESSION, MINUTES_AGO, labels)));
            Map<String, String> volumeLabels = new HashMap<>(labels);
            volumeLabels.put(SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE);
            volumeLabels.put(SandboxLabels.JOB_ID, UUID.randomUUID().toString());
            volumeLabels.put(SandboxLabels.CREATED_AT, MINUTES_AGO.toString());
            when(volumes.listVolumes(any()))
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
            hostCreatedBy("creator-worker", "t1");
            creatorIs(
                    "creator-worker",
                    new DockerOperations.ContainerIdentity("creator-worker", true, "t1", "creator-worker"));

            reconciler.periodicReconciliation();
            reconciler.onStartup();

            assertUntouched();
        }

        @Test
        void shouldKeepTheNetworkAndVolumesWhenTheCreatorCannotBeRead() {
            hostCreatedBy("creator-worker", "t1");
            when(creatorContainers.inspectContainerIdentity("creator-worker"))
                    .thenThrow(new RuntimeException("daemon busy"));

            reconciler.periodicReconciliation();

            assertUntouched();
        }

        @Test
        void shouldKeepNetworksWhoseOwnerIsUnknownHoweverOld() {
            // One host network records no creator (its process could not identify itself); one predates labels.
            when(networkOps.listNetworksByName(PREFIX))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo(
                                    "net-unrecorded",
                                    PREFIX + SESSION,
                                    MINUTES_AGO,
                                    Map.of(SandboxLabels.OWNER, OWNER, SandboxLabels.SESSION_ID, SESSION.toString())),
                            new DockerOperations.NetworkInfo(
                                    "net-legacy", PREFIX + UUID.randomUUID(), MINUTES_AGO, Map.of())));

            reconciler.periodicReconciliation();

            verify(networkOps, never()).removeNetwork(any());
        }

        @Test
        void shouldReapTheNetworkAndVolumesWhenTheCreatorIsGone() {
            hostCreatedBy("creator-worker", "t1");
            creatorIs("creator-worker", null);
            when(networkOps.inspectEndpoints("net-session"))
                    .thenReturn(List.of(new DockerOperations.NetworkEndpoint(RECONCILER_ID, "worker")));

            reconciler.periodicReconciliation();

            verify(networkOps).disconnectFromNetwork("net-session", RECONCILER_ID);
            verify(networkOps).removeNetwork("net-session");
            verify(volumes).removeVolume("vol-session");
        }

        @Test
        void shouldReapTheNetworkWhenTheCreatorHasRestarted() {
            hostCreatedBy("creator-worker", "t1");
            creatorIs(
                    "creator-worker",
                    new DockerOperations.ContainerIdentity("creator-worker", true, "t2", "creator-worker"));

            reconciler.periodicReconciliation();

            verify(networkOps).removeNetwork("net-session");
        }

        @Test
        void shouldKeepTheProxyConnectedWhenASandboxAttachedAfterTheInventory() {
            hostCreatedBy("creator-worker", "t1");
            creatorIs(
                    "creator-worker",
                    new DockerOperations.ContainerIdentity("creator-worker", false, "t1", "creator-worker"));
            when(networkOps.inspectEndpoints("net-session"))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkEndpoint(RECONCILER_ID, "worker"),
                            new DockerOperations.NetworkEndpoint("runtime-full-id", "mentor-runtime")));

            reconciler.periodicReconciliation();

            verify(networkOps, never()).disconnectFromNetwork(any(), any());
            verify(networkOps, never()).removeNetwork(any());
        }
    }
}
