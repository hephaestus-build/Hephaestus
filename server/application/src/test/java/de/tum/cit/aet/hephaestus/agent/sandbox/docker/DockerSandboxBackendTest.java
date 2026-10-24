package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

/** {@link DockerSandboxBackend} against mocked Docker managers. */
class DockerSandboxBackendTest extends BaseUnitTest {

    private static final String OWNER = "default";
    private static final String PREFIX = "hephaestus-sandbox-default--";
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String NETWORK_ID = "net-123abc";
    private static final String APP_SERVER_IP = "172.18.0.2";
    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

    private static final DockerOperations.HostConfigSpec HOST_CONFIG = new DockerOperations.HostConfigSpec(
            4L * 1024 * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
            2_000_000_000L,
            256,
            true,
            false,
            List.of("ALL"),
            List.of(),
            Map.of(),
            List.of(),
            "private",
            "none",
            null,
            Map.of(),
            List.of());

    @Mock
    private SandboxNetworkManager networkManager;

    @Mock
    private SandboxContainerManager containerManager;

    @Mock
    private ContainerSecurityPolicy securityPolicy;

    @Mock
    private DockerVolumeOperations volumeOperations;

    @Mock
    private SandboxCreator creator;

    private DockerSandboxBackend backend;

    @BeforeEach
    void setUp() {
        lenient().when(networkManager.networkPrefix()).thenReturn(PREFIX);
        backend = backend(8081);
    }

    private DockerSandboxBackend backend(int gatewayPort) {
        return new DockerSandboxBackend(
                networkManager, containerManager, securityPolicy, volumeOperations, creator, OWNER, () -> gatewayPort);
    }

    private static Map<String, String> reviewLabels(UUID job, @Nullable Integer attempt) {
        Map<String, String> labels = new HashMap<>();
        labels.put(SandboxLabels.OWNER, OWNER);
        labels.put(SandboxLabels.JOB_ID, job.toString());
        if (attempt != null) {
            labels.put(SandboxLabels.JOB_ATTEMPT, attempt.toString());
        }
        return Map.copyOf(labels);
    }

    private static Map<String, String> hostLabels(UUID host) {
        return Map.of(
                SandboxLabels.OWNER,
                OWNER,
                SandboxLabels.SESSION_ID,
                host.toString(),
                SandboxLabels.CREATOR_CONTAINER,
                "3f2a9c1b7d4e",
                SandboxLabels.CREATOR_STARTED_AT,
                "t1");
    }

    private static DockerOperations.ContainerInfo container(String id, Map<String, String> labels, String state) {
        return new DockerOperations.ContainerInfo(id, id, labels, state, LONG_AGO);
    }

    private static DockerOperations.NetworkInfo network(String id, UUID owner, Map<String, String> labels) {
        return new DockerOperations.NetworkInfo(id, PREFIX + owner, LONG_AGO, labels);
    }

    private static DockerOperations.VolumeInfo volume(String name, Map<String, String> labels) {
        Map<String, String> volumeLabels = new HashMap<>(labels);
        volumeLabels.put(SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE);
        volumeLabels.put(SandboxLabels.CREATED_AT, LONG_AGO.toString());
        return new DockerOperations.VolumeInfo(name, volumeLabels);
    }

    private static SandboxBackend.Launch review(@Nullable Integer attempt) {
        return new SandboxBackend.Launch(
                SandboxBackend.Kind.REVIEW,
                JOB_ID,
                attempt,
                "alpine:latest",
                List.of("run-review"),
                ResourceLimits.DEFAULT,
                Instant.now().plusSeconds(600),
                gateway -> Map.of("LLM_PROXY_URL", gateway + "/internal/llm"));
    }

    /** The network, the address and the two containers of a launch: the initializer, then the runtime. */
    private void stubLaunch(Map<String, String> labels) {
        lenient().when(securityPolicy.buildLabels(eq(JOB_ID), any())).thenReturn(labels);
        lenient().when(securityPolicy.buildLabels(JOB_ID)).thenReturn(labels);
        when(networkManager.createJobNetwork(eq(JOB_ID), eq(false), any())).thenReturn(NETWORK_ID);
        when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
        when(securityPolicy.buildHostConfig(any(), any(), anyBoolean())).thenReturn(HOST_CONFIG);
        lenient().when(containerManager.createContainer(any())).thenAnswer(invocation -> {
            DockerOperations.ContainerSpec spec = invocation.getArgument(0);
            return spec.command().equals(SandboxLayout.INITIALIZER_COMMAND) ? "initializer" : "runtime";
        });
    }

    @Nested
    class Launching {

        @Test
        void shouldNameTheSandboxByItsJobAndAttemptAndStartItsInitializerOnItsOwnNetwork() {
            Map<String, String> labels = reviewLabels(JOB_ID, 2);
            stubLaunch(labels);

            SandboxBackend.Handle handle = backend.launch(review(2));

            assertThat(handle.id()).isEqualTo("hephaestus-review-" + JOB_ID + "-2");
            verify(networkManager).createJobNetwork(JOB_ID, false, labels);
            ArgumentCaptor<DockerOperations.ContainerSpec> created =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(created.capture());
            DockerOperations.ContainerSpec initializer = created.getValue();
            assertThat(initializer.command()).isEqualTo(SandboxLayout.INITIALIZER_COMMAND);
            assertThat(initializer.user()).isEqualTo("1000:1000");
            assertThat(initializer.labels()).isEqualTo(labels);
            assertThat(initializer.environment())
                    .containsEntry("LLM_PROXY_URL", "http://" + APP_SERVER_IP + ":8081/internal/llm");
            verify(containerManager).startContainer("initializer");
        }

        @Test
        void shouldNameAReviewWithoutAClaimByItsJobAlone() {
            stubLaunch(reviewLabels(JOB_ID, null));

            assertThat(backend.launch(review(null)).id()).isEqualTo("hephaestus-review-" + JOB_ID);
        }

        @Test
        void shouldPointTheSandboxAtTheConfiguredGatewayPort() {
            backend = backend(8090);
            stubLaunch(reviewLabels(JOB_ID, null));

            backend.launch(review(null));

            ArgumentCaptor<DockerOperations.ContainerSpec> created =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(created.capture());
            assertThat(created.getValue().environment())
                    .containsEntry("LLM_PROXY_URL", "http://" + APP_SERVER_IP + ":8090/internal/llm");
        }

        @Test
        void shouldStartTheRuntimeWithTheLaunchsCommandOnceTheInitializerEnded() {
            stubLaunch(reviewLabels(JOB_ID, null));
            when(containerManager.waitForCompletion(eq("initializer"), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(0, false));
            when(containerManager.waitForCompletion(eq("runtime"), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(0, false));
            SandboxBackend.Handle handle = backend.launch(review(null));
            Instant deadline = Instant.now().plusSeconds(60);

            assertThat(backend.observe(handle, SandboxBackend.Stage.INITIALIZED, deadline))
                    .isEqualTo(new SandboxBackend.Observation(false, 0));
            assertThat(backend.observe(handle, SandboxBackend.Stage.ENDED, deadline))
                    .isEqualTo(new SandboxBackend.Observation(false, 0));

            ArgumentCaptor<DockerOperations.ContainerSpec> created =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            InOrder order = inOrder(containerManager);
            order.verify(containerManager).forceRemove("initializer");
            order.verify(containerManager).createContainer(created.capture());
            order.verify(containerManager).startContainer("runtime");
            assertThat(created.getValue().command()).containsExactly("run-review");
        }

        @Test
        void shouldStartNoRuntimeOnceTheSandboxWasKilled() {
            stubLaunch(reviewLabels(JOB_ID, null));
            SandboxBackend.Handle handle = backend.launch(review(null));

            backend.kill(handle);

            assertThat(backend.observe(handle, SandboxBackend.Stage.ENDED, Instant.now()))
                    .isEqualTo(new SandboxBackend.Observation(false, null));
            verify(containerManager).stopContainer("initializer");
            verify(containerManager, never()).startContainer("runtime");
        }

        @Test
        void shouldReadEveryLineOfAFailedInitializer() {
            stubLaunch(reviewLabels(JOB_ID, null));
            when(containerManager.waitForCompletion(eq("initializer"), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(1, false));
            when(containerManager.getLogs("initializer")).thenReturn("clone failed");
            SandboxBackend.Handle handle = backend.launch(review(null));

            backend.observe(
                    handle, SandboxBackend.Stage.INITIALIZED, Instant.now().plusSeconds(60));

            assertThat(backend.transcript(handle, SandboxBackend.Container.INITIALIZER))
                    .isEqualTo("clone failed");
        }

        @Test
        void shouldCreateNothingWhenItsNetworkCannotBeCreated() {
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(reviewLabels(JOB_ID, null));
            when(networkManager.createJobNetwork(any(), anyBoolean(), any()))
                    .thenThrow(new SandboxException("Network creation failed"));

            assertThatThrownBy(() -> backend.launch(review(null))).hasMessageContaining("Network creation failed");

            verify(containerManager, never()).createContainer(any());
        }

        @Test
        void shouldRemoveWhatItCreatedWhenAContainerCannotBeCreated() {
            Map<String, String> labels = reviewLabels(JOB_ID, null);
            stubLaunch(labels);
            doThrow(new SandboxException("Image not found"))
                    .when(containerManager)
                    .createContainer(any());
            when(networkManager.listNetworks()).thenReturn(List.of(network(NETWORK_ID, JOB_ID, labels)));
            when(networkManager.removeUnlessInUse(NETWORK_ID, PREFIX + JOB_ID)).thenReturn(true);
            when(volumeOperations.listVolumes(anyMap())).thenReturn(List.of(volume("hephaestus-v-0", labels)));

            assertThatThrownBy(() -> backend.launch(review(null))).hasMessageContaining("Image not found");

            verify(networkManager).removeUnlessInUse(NETWORK_ID, PREFIX + JOB_ID);
            verify(volumeOperations).removeVolume("hephaestus-v-0");
        }
    }

    @Nested
    class SupersededAttempts {

        @Test
        void shouldRemoveOnlyEarlierAttemptsOfThisJobBeforeCreatingItsNetwork() {
            String job = JOB_ID.toString();
            Map<String, String> sessionLabels = new HashMap<>(reviewLabels(JOB_ID, 1));
            sessionLabels.put(SandboxLabels.SESSION_ID, UUID.randomUUID().toString());
            Map<String, String> otherOwner = new HashMap<>(reviewLabels(JOB_ID, 1));
            otherOwner.put(SandboxLabels.OWNER, "other");
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(
                            container("earlier", reviewLabels(JOB_ID, 1), "running"),
                            container("current", reviewLabels(JOB_ID, 2), "running"),
                            container("newer", reviewLabels(JOB_ID, 3), "running"),
                            container("legacy", reviewLabels(JOB_ID, null), "running"),
                            container(
                                    "malformed",
                                    Map.of(
                                            SandboxLabels.OWNER,
                                            OWNER,
                                            SandboxLabels.JOB_ID,
                                            job,
                                            SandboxLabels.JOB_ATTEMPT,
                                            "1x"),
                                    "running"),
                            container("other-job", reviewLabels(UUID.randomUUID(), 1), "running"),
                            container("other-owner", otherOwner, "running"),
                            container("session", sessionLabels, "running")));
            Map<String, String> labels = reviewLabels(JOB_ID, 2);
            stubLaunch(labels);

            backend.launch(review(2));

            InOrder order = inOrder(containerManager, networkManager);
            order.verify(containerManager).forceRemove("earlier");
            order.verify(networkManager).createJobNetwork(JOB_ID, false, labels);
            for (String kept :
                    List.of("current", "newer", "legacy", "malformed", "other-job", "other-owner", "session")) {
                verify(containerManager, never()).forceRemove(kept);
            }
        }

        @Test
        void shouldFailTheAttemptWithoutANetworkWhenAnEarlierAttemptCannotBeRemoved() {
            when(securityPolicy.buildLabels(JOB_ID, 2)).thenReturn(reviewLabels(JOB_ID, 2));
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("earlier", reviewLabels(JOB_ID, 1), "running")));
            doThrow(new IllegalStateException("daemon refused"))
                    .when(containerManager)
                    .forceRemove("earlier");

            assertThatThrownBy(() -> backend.launch(review(2))).hasMessage("daemon refused");

            verify(networkManager, never()).createJobNetwork(any(), anyBoolean(), any());
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(ints = 0)
        void shouldLookForNoEarlierAttemptOfAFirstAttemptOrOfAReviewWithoutAClaim(@Nullable Integer attempt) {
            stubLaunch(reviewLabels(JOB_ID, attempt));

            backend.launch(review(attempt));

            verify(containerManager, never()).listManagedContainers();
        }
    }

    @Nested
    class ByName {

        private final SandboxBackend.Handle attempt2 = new SandboxBackend.Handle("hephaestus-review-" + JOB_ID + "-2");

        @Test
        void shouldRemoveEveryPartOfTheNamedSandboxAndNothingElse() {
            UUID other = UUID.randomUUID();
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(
                            container("runtime", reviewLabels(JOB_ID, 2), "exited"),
                            container("superseded", reviewLabels(JOB_ID, 1), "running"),
                            container("other-job", reviewLabels(other, 2), "running")));
            when(networkManager.listNetworks())
                    .thenReturn(List.of(
                            network("net-own", JOB_ID, reviewLabels(JOB_ID, 2)),
                            network("net-other", other, reviewLabels(other, 2))));
            when(networkManager.removeUnlessInUse("net-own", PREFIX + JOB_ID)).thenReturn(true);
            when(volumeOperations.listVolumes(Map.of(
                            SandboxLabels.OWNER, OWNER, SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE)))
                    .thenReturn(List.of(
                            volume("own-0", reviewLabels(JOB_ID, 2)), volume("superseded-0", reviewLabels(JOB_ID, 1))));

            backend.remove(attempt2);

            verify(containerManager).forceRemove("runtime");
            verify(containerManager, never()).forceRemove("superseded");
            verify(containerManager, never()).forceRemove("other-job");
            verify(networkManager, never()).removeUnlessInUse(eq("net-other"), anyString());
            verify(volumeOperations).removeVolume("own-0");
            verify(volumeOperations, never()).removeVolume("superseded-0");
        }

        @Test
        void shouldKeepTheJobsNetworkWhenANewerAttemptCreatedItAgain() {
            when(networkManager.listNetworks())
                    .thenReturn(List.of(network("net-newer", JOB_ID, reviewLabels(JOB_ID, 3))));

            backend.remove(attempt2);

            verify(networkManager, never()).removeUnlessInUse(anyString(), anyString());
        }

        @Test
        void shouldRunEveryStepAndThenFailWhenAPartStays() {
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(container("runtime", reviewLabels(JOB_ID, 2), "exited")));
            doThrow(new SandboxException("stuck")).when(containerManager).forceRemove("runtime");
            when(networkManager.listNetworks())
                    .thenReturn(List.of(network("net-own", JOB_ID, reviewLabels(JOB_ID, 2))));
            when(networkManager.removeUnlessInUse("net-own", PREFIX + JOB_ID)).thenReturn(false);
            when(volumeOperations.listVolumes(anyMap())).thenReturn(List.of(volume("own-0", reviewLabels(JOB_ID, 2))));

            assertThatThrownBy(() -> backend.remove(attempt2))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("remove container")
                    .hasMessageContaining("remove network");

            verify(volumeOperations).removeVolume("own-0");
        }

        @Test
        void shouldStopTheRunningContainersOfASandboxThatAnotherProcessLaunched() {
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(
                            container("running", reviewLabels(JOB_ID, 2), "running"),
                            container("exited", reviewLabels(JOB_ID, 2), "exited"),
                            container("superseded", reviewLabels(JOB_ID, 1), "running")));

            backend.kill(attempt2);

            verify(containerManager).stopContainer("running");
            verify(containerManager, never()).stopContainer("exited");
            verify(containerManager, never()).stopContainer("superseded");
        }

        @Test
        void shouldIgnoreAHandleThatItDidNotGive() {
            SandboxBackend.Handle foreign = new SandboxBackend.Handle("hephaestus-review-not-a-job");

            assertThatCode(() -> {
                        backend.kill(foreign);
                        backend.remove(foreign);
                    })
                    .doesNotThrowAnyException();
            verifyNoInteractions(containerManager, networkManager, volumeOperations);
        }
    }

    @Nested
    class Listing {

        @Test
        void shouldListEachSandboxOnceByItsNewestPart() {
            UUID host = UUID.randomUUID();
            Instant newer = LONG_AGO.plusSeconds(60);
            Map<String, String> otherOwner = new HashMap<>(reviewLabels(UUID.randomUUID(), 1));
            otherOwner.put(SandboxLabels.OWNER, "other");
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(
                            container("runtime", reviewLabels(JOB_ID, 2), "running"),
                            container("host", hostLabels(host), "running"),
                            container(
                                    "turn",
                                    Map.of(
                                            SandboxLabels.OWNER,
                                            OWNER,
                                            SandboxLabels.KIND,
                                            SandboxLabels.KIND_HEPH_SANDBOX),
                                    "running"),
                            container("other-owner", otherOwner, "running")));
            when(networkManager.listNetworks())
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("net", PREFIX + JOB_ID, newer, reviewLabels(JOB_ID, 2))));
            when(volumeOperations.listVolumes(anyMap())).thenReturn(List.of(volume("host-0", hostLabels(host))));
            when(creator.liveness(any())).thenReturn(SandboxCreator.Liveness.GONE);

            List<SandboxBackend.Listed> listed = backend.list();

            assertThat(listed)
                    .containsExactlyInAnyOrder(
                            new SandboxBackend.Listed(
                                    new SandboxBackend.Handle("hephaestus-review-" + JOB_ID + "-2"),
                                    SandboxBackend.Kind.REVIEW,
                                    JOB_ID,
                                    2,
                                    newer,
                                    false),
                            new SandboxBackend.Listed(
                                    new SandboxBackend.Handle("hephaestus-host-" + host),
                                    SandboxBackend.Kind.HOST,
                                    host,
                                    null,
                                    LONG_AGO,
                                    true));
        }

        @Test
        void shouldGiveNoAgeToASandboxWithAPartOfUnknownAge() {
            when(containerManager.listManagedContainers())
                    .thenReturn(List.of(
                            container("runtime", reviewLabels(JOB_ID, 2), "running"),
                            new DockerOperations.ContainerInfo(
                                    "fresh", "fresh", reviewLabels(JOB_ID, 2), "created", null)));
            when(networkManager.listNetworks()).thenReturn(List.of());
            when(volumeOperations.listVolumes(anyMap())).thenReturn(List.of());

            assertThat(backend.list())
                    .singleElement()
                    .satisfies(sandbox -> assertThat(sandbox.created()).isNull());
        }

        @Test
        void shouldFailWhenAnInventoryCannotBeRead() {
            when(containerManager.listManagedContainers()).thenReturn(List.of());
            when(networkManager.listNetworks()).thenThrow(new IllegalStateException("daemon gone"));

            assertThatThrownBy(() -> backend.list()).isInstanceOf(SandboxException.class);
        }

        @Test
        void shouldRoundTripTheNamesItGives() {
            List<DockerSandboxBackend.Name> names = List.of(
                    new DockerSandboxBackend.Name(SandboxBackend.Kind.REVIEW, JOB_ID, 7),
                    new DockerSandboxBackend.Name(SandboxBackend.Kind.REVIEW, JOB_ID, null),
                    new DockerSandboxBackend.Name(SandboxBackend.Kind.HOST, JOB_ID, null));

            assertThat(names)
                    .allSatisfy(name -> assertThat(DockerSandboxBackend.Name.of(name.handle()))
                            .contains(name));
            assertThat(DockerSandboxBackend.Name.of(new SandboxBackend.Handle("hephaestus-review-" + JOB_ID + "-x")))
                    .isEmpty();
        }
    }
}
