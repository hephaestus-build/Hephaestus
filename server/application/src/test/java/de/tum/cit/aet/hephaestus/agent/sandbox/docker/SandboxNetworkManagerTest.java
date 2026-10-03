package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

class SandboxNetworkManagerTest extends BaseUnitTest {

    @Mock
    private DockerNetworkOperations networkOps;

    @Mock
    private DockerInspectOperations containers;

    private SandboxCreator creator;

    private SandboxNetworkManager manager;

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String NETWORK_ID = "net-abc123";
    private static final String SELF_SHORT_ID = "3f2a9c1b7d4e";
    private static final String SELF_ID = SELF_SHORT_ID + "5a6b7c8d9e0f";

    @Test
    void shouldExcludeOtherOwnersAndLegacyNetworksWhenListingNetworks() {
        var properties = new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, "course");
        var manager = new SandboxNetworkManager(networkOps, properties, creator);
        String id = UUID.randomUUID().toString();
        var own = new DockerOperations.NetworkInfo("own", "hephaestus-sandbox-course--" + id, null, Map.of());
        when(networkOps.listNetworksByName(manager.networkPrefix()))
                .thenReturn(List.of(
                        own,
                        new DockerOperations.NetworkInfo(
                                "foreign", "hephaestus-sandbox-course--other--" + id, null, Map.of()),
                        new DockerOperations.NetworkInfo("legacy", "agent-net-" + id, null, Map.of())));
        assertThat(manager.listOrphanedNetworks()).containsExactly(own);
    }

    @BeforeEach
    void setUp() {
        creator = new SandboxCreator(containers, () -> SELF_SHORT_ID);
        DockerSandboxProperties properties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, "default");
        manager = new SandboxNetworkManager(networkOps, properties, creator);
    }

    @Nested
    class CreateJobNetwork {

        @Test
        void shouldCreateInternalNetwork() {
            when(networkOps.createNetwork(anyString(), eq(true), any())).thenReturn(NETWORK_ID);

            String networkId = manager.createJobNetwork(JOB_ID, false, Map.of());

            assertThat(networkId).isEqualTo(NETWORK_ID);
            verify(networkOps).createNetwork("hephaestus-sandbox-default--" + JOB_ID, true, Map.of());
        }

        @Test
        @DisplayName("a network an interrupted run left under this job's name is removed, not fought over")
        void shouldReplaceLeftoverNetworkOfTheSameJob() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            identifySelf();
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName, null, Map.of())));
            when(networkOps.createNetwork(anyString(), eq(true), any())).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false, Map.of())).isEqualTo(NETWORK_ID);

            verify(networkOps).disconnectFromNetwork("stale-net", SELF_ID);
            verify(networkOps).removeNetwork("stale-net");
            verify(networkOps).createNetwork(networkName, true, Map.of());
        }

        @Test
        @DisplayName("a leftover network that will not go is reported, not worked around")
        void shouldFailWhenLeftoverNetworkCannotBeRemoved() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName, null, Map.of())));
            doThrow(new SandboxInfrastructureException("network has active endpoints"))
                    .when(networkOps)
                    .removeNetwork("stale-net");

            assertThatThrownBy(() -> manager.createJobNetwork(JOB_ID, false, Map.of()))
                    .isInstanceOf(SandboxInfrastructureException.class);

            verify(networkOps, never()).createNetwork(anyString(), anyBoolean(), any());
        }

        @Test
        void shouldKeepTheAppServerOnALeftoverNetworkWhenASandboxIsStillAttached() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName, null, Map.of())));
            when(networkOps.inspectEndpoints("stale-net"))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkEndpoint("9c0ffee00000", "app-server-id"),
                            new DockerOperations.NetworkEndpoint("runtime-full", "runtime")));

            assertThatThrownBy(() -> manager.createJobNetwork(JOB_ID, false, Map.of()))
                    .isInstanceOf(SandboxInfrastructureException.class);

            verify(networkOps, never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, never()).removeNetwork(anyString());
        }

        @Test
        void shouldRefuseToReplaceAMentorNetworkWhenAnotherRunningProcessCreatedIt() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            "live-net", networkName, null, creatorLabels("other-worker", "t1"))));
            when(containers.inspectContainerIdentity(SELF_SHORT_ID))
                    .thenReturn(
                            Optional.of(new DockerOperations.ContainerIdentity(SELF_ID, true, "t0", SELF_SHORT_ID)));
            when(containers.inspectContainerIdentity("other-worker"))
                    .thenReturn(Optional.of(running("other-worker", "t1")));

            assertThatThrownBy(() -> manager.createJobNetwork(JOB_ID, false, Map.of()))
                    .isInstanceOf(SandboxInfrastructureException.class);

            verify(networkOps, never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, never()).removeNetwork(anyString());
        }

        @Test
        void shouldRefuseToReplaceAMentorNetworkWhoseCreatorIsUnknown() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            "unknown-net", networkName, null, Map.of(SandboxLabels.SESSION_ID, JOB_ID.toString()))));

            assertThatThrownBy(() -> manager.createJobNetwork(JOB_ID, false, Map.of()))
                    .isInstanceOf(SandboxInfrastructureException.class)
                    .hasMessageContaining("cannot be identified");

            verify(networkOps, never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, never()).removeNetwork(anyString());
        }

        @Test
        void shouldReplaceAMentorNetworkWhenThisProcessCreatedIt() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            "own-net", networkName, null, creatorLabels(SELF_ID, "t0"))));
            when(containers.inspectContainerIdentity(SELF_SHORT_ID))
                    .thenReturn(
                            Optional.of(new DockerOperations.ContainerIdentity(SELF_ID, true, "t0", SELF_SHORT_ID)));
            when(networkOps.createNetwork(anyString(), eq(true), any())).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false, Map.of())).isEqualTo(NETWORK_ID);

            verify(networkOps).removeNetwork("own-net");
        }

        @Test
        @DisplayName("another job's network is left alone even when the daemon returns it")
        void shouldLeaveOtherJobsNetworksAlone() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkInfo("other-net", networkName + "-suffix", null, Map.of())));
            when(networkOps.createNetwork(anyString(), eq(true), any())).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false, Map.of())).isEqualTo(NETWORK_ID);

            verify(networkOps, never()).removeNetwork(anyString());
        }

        @Test
        @DisplayName("a daemon that cannot list networks does not stop the create")
        void shouldCreateWhenTheLeftoverProbeFails() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenThrow(new SandboxInfrastructureException("Failed to list networks"));
            when(networkOps.createNetwork(anyString(), eq(true), any())).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false, Map.of())).isEqualTo(NETWORK_ID);

            verify(networkOps).createNetwork(networkName, true, Map.of());
        }

        @Test
        void shouldCreateBridgeNetwork() {
            when(networkOps.createNetwork(anyString(), eq(false), any())).thenReturn(NETWORK_ID);

            String networkId = manager.createJobNetwork(JOB_ID, true, Map.of());

            assertThat(networkId).isEqualTo(NETWORK_ID);
            verify(networkOps).createNetwork("hephaestus-sandbox-default--" + JOB_ID, false, Map.of());
        }
    }

    @Nested
    class ConnectAppServer {

        @Test
        void shouldConnectTheContainerThisProcessRunsIn() {
            identifySelf();
            when(networkOps.connectToNetwork(NETWORK_ID, SELF_ID)).thenReturn("172.18.0.2");

            assertThat(manager.connectAppServer(NETWORK_ID)).isEqualTo("172.18.0.2");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "application-server", SELF_SHORT_ID})
        void shouldJoinNothingWhenThisProcessIsNotAnIdentifiedContainer(@Nullable String hostname) {
            // SELF_SHORT_ID has the shape of a container id, but no running container answers to it here.
            SandboxNetworkManager mgr = new SandboxNetworkManager(
                    networkOps,
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, "default"),
                    new SandboxCreator(containers, () -> hostname));

            assertThat(mgr.connectAppServer(NETWORK_ID)).isNull();

            verify(networkOps, never()).connectToNetwork(anyString(), anyString());
        }
    }

    @Nested
    class DisconnectAppServer {

        @Test
        void shouldDisconnectTheContainerThisProcessRunsIn() {
            identifySelf();

            manager.disconnectAppServer(NETWORK_ID);

            verify(networkOps).disconnectFromNetwork(NETWORK_ID, SELF_ID);
        }

        @Test
        void shouldNoOpWhenThisProcessIsNotAnIdentifiedContainer() {
            SandboxNetworkManager mgr = new SandboxNetworkManager(
                    networkOps,
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, "default"),
                    new SandboxCreator(containers, () -> null));

            mgr.disconnectAppServer(NETWORK_ID);

            verify(networkOps, never()).disconnectFromNetwork(anyString(), anyString());
        }
    }

    @Nested
    class RemoveNetwork {

        @Test
        void shouldRemoveNetwork() {
            manager.removeNetwork(NETWORK_ID);

            verify(networkOps).removeNetwork(NETWORK_ID);
        }
    }

    @Nested
    class RemoveUnlessInUse {

        @Test
        void shouldRemoveANetworkOnlyThisContainerIsAttachedTo() {
            identifySelf();
            when(networkOps.inspectEndpoints(NETWORK_ID))
                    .thenReturn(List.of(new DockerOperations.NetworkEndpoint(SELF_ID, "hephaestus-worker-1")));

            assertThat(manager.removeUnlessInUse(NETWORK_ID, "n")).isTrue();

            verify(networkOps).disconnectFromNetwork(NETWORK_ID, SELF_ID);
            verify(networkOps).removeNetwork(NETWORK_ID);
        }

        @Test
        void shouldKeepANetworkAnotherContainerIsAttachedTo() {
            identifySelf();
            // A container whose id starts like this one's is still another container.
            when(networkOps.inspectEndpoints(NETWORK_ID))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkEndpoint(SELF_ID, "hephaestus-worker-1"),
                            new DockerOperations.NetworkEndpoint(SELF_SHORT_ID + "ffffffffffff", "mentor-runtime")));

            assertThat(manager.removeUnlessInUse(NETWORK_ID, "n")).isFalse();

            verify(networkOps, never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, never()).removeNetwork(anyString());
        }
    }

    @Nested
    class ListOrphanedNetworks {

        @Test
        void shouldListByPrefix() {
            when(networkOps.listNetworksByName("hephaestus-sandbox-default--"))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo(
                            "n1", "hephaestus-sandbox-default--" + JOB_ID, null, Map.of())));

            var networks = manager.listOrphanedNetworks();

            assertThat(networks).hasSize(1);
        }
    }

    private static Map<String, String> creatorLabels(String container, String startedAt) {
        return Map.of(
                SandboxLabels.SESSION_ID, JOB_ID.toString(),
                SandboxLabels.CREATOR_CONTAINER, container,
                SandboxLabels.CREATOR_STARTED_AT, startedAt);
    }

    private void identifySelf() {
        when(containers.inspectContainerIdentity(SELF_SHORT_ID))
                .thenReturn(Optional.of(new DockerOperations.ContainerIdentity(SELF_ID, true, "t0", SELF_SHORT_ID)));
    }

    private static DockerOperations.ContainerIdentity running(String id, String startedAt) {
        return new DockerOperations.ContainerIdentity(id, true, startedAt, id);
    }
}
