package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;

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
        var properties = new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "course");
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
        DockerSandboxProperties properties = new DockerSandboxProperties(
                "unix:///var/run/docker.sock", false, null, null, "app-server-id", "default");
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
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName, null, Map.of())));
            when(networkOps.createNetwork(anyString(), eq(true), any())).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false, Map.of())).isEqualTo(NETWORK_ID);

            verify(networkOps).disconnectFromNetwork("stale-net", "app-server-id");
            verify(networkOps).removeNetwork("stale-net");
            verify(networkOps).createNetwork(networkName, true, Map.of());
        }

        @Test
        @DisplayName("a leftover network that will not go is reported, not worked around")
        void shouldFailWhenLeftoverNetworkCannotBeRemoved() {
            String networkName = "hephaestus-sandbox-default--" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName, null, Map.of())));
            Mockito.doThrow(new SandboxInfrastructureException("network has active endpoints"))
                    .when(networkOps)
                    .removeNetwork("stale-net");

            assertThatThrownBy(() -> manager.createJobNetwork(JOB_ID, false, Map.of()))
                    .isInstanceOf(SandboxInfrastructureException.class);

            verify(networkOps, Mockito.never()).createNetwork(anyString(), Mockito.anyBoolean(), any());
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

            verify(networkOps, Mockito.never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, Mockito.never()).removeNetwork(anyString());
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

            verify(networkOps, Mockito.never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, Mockito.never()).removeNetwork(anyString());
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

            verify(networkOps, Mockito.never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, Mockito.never()).removeNetwork(anyString());
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

            verify(networkOps, Mockito.never()).removeNetwork(anyString());
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
        void shouldConnectAndReturnIp() {
            when(networkOps.connectToNetwork(NETWORK_ID, "app-server-id")).thenReturn("172.18.0.2");

            String ip = manager.connectAppServer(NETWORK_ID);

            assertThat(ip).isEqualTo("172.18.0.2");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void shouldFallBackToHostnameWhenContainerIdIsMissingOrBlank(@Nullable String containerId) {
            DockerSandboxProperties propsNoId = new DockerSandboxProperties(
                    "unix:///var/run/docker.sock", false, null, null, containerId, "default");
            SandboxNetworkManager mgr =
                    new SandboxNetworkManager(networkOps, propsNoId, creator, () -> "hostname-container-id");

            when(networkOps.connectToNetwork(NETWORK_ID, "hostname-container-id"))
                    .thenReturn("172.18.0.3");

            String ip = mgr.connectAppServer(NETWORK_ID);

            assertThat(ip).isEqualTo("172.18.0.3");
            verify(networkOps).connectToNetwork(NETWORK_ID, "hostname-container-id");
        }

        @Test
        void shouldReturnNullWhenNoContainerId() {
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "default");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, creator, () -> null);

            String ip = mgr.connectAppServer(NETWORK_ID);

            assertThat(ip).isNull();
        }

        @Test
        void shouldReturnNullWhenHostnameBlank() {
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "default");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, creator, () -> "  ");

            String ip = mgr.connectAppServer(NETWORK_ID);

            assertThat(ip).isNull();
        }

        @Test
        void shouldCacheContainerId() {
            var callCount = new AtomicInteger(0);
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "default");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, creator, () -> {
                callCount.incrementAndGet();
                return "cached-id";
            });
            when(networkOps.connectToNetwork(anyString(), eq("cached-id"))).thenReturn("172.18.0.5");

            mgr.connectAppServer(NETWORK_ID);
            mgr.connectAppServer(NETWORK_ID);

            // Supplier should only be invoked once — second call uses cached value
            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class DisconnectAppServer {

        @Test
        @DisplayName("should disconnect app-server from network")
        void shouldDisconnect() {
            manager.disconnectAppServer(NETWORK_ID);

            verify(networkOps).disconnectFromNetwork(NETWORK_ID, "app-server-id");
        }

        @Test
        void shouldNoOpWhenNoContainerId() {
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "default");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, creator, () -> null);

            // Should not throw — silently skips disconnect
            mgr.disconnectAppServer(NETWORK_ID);

            verify(networkOps, Mockito.never()).disconnectFromNetwork(anyString(), anyString());
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

        private static final String PROXY_ID = "3f2a9c1b7d4e5a6b7c8d9e0f";
        private static final DockerOperations.NetworkEndpoint PROXY =
                new DockerOperations.NetworkEndpoint(PROXY_ID, "hephaestus-app-1");

        private SandboxNetworkManager withAppServer(String configured) {
            return new SandboxNetworkManager(
                    networkOps,
                    new DockerSandboxProperties(
                            "unix:///var/run/docker.sock", false, null, null, configured, "default"),
                    creator);
        }

        @ParameterizedTest
        @ValueSource(strings = {"hephaestus-app-1", PROXY_ID, "3f2a9c1b7d4e"})
        void shouldRemoveANetworkOnlyTheAppServerIsAttachedTo(String configured) {
            when(networkOps.inspectEndpoints(NETWORK_ID)).thenReturn(List.of(PROXY));

            assertThat(withAppServer(configured).removeUnlessInUse(NETWORK_ID, "n"))
                    .isTrue();

            verify(networkOps).disconnectFromNetwork(NETWORK_ID, configured);
            verify(networkOps).removeNetwork(NETWORK_ID);
        }

        @Test
        void shouldKeepTheAppServerConnectedWhenItsNameStartsAnotherContainersId() {
            // An app-server named "a", and a sandbox whose id happens to start with "a".
            when(networkOps.inspectEndpoints(NETWORK_ID))
                    .thenReturn(List.of(
                            new DockerOperations.NetworkEndpoint("0b1c2d3e4f5a6b7c", "a"),
                            new DockerOperations.NetworkEndpoint("a1b2c3d4e5f6a7b8", "mentor-runtime")));

            assertThat(withAppServer("a").removeUnlessInUse(NETWORK_ID, "n")).isFalse();

            verify(networkOps, Mockito.never()).disconnectFromNetwork(anyString(), anyString());
            verify(networkOps, Mockito.never()).removeNetwork(anyString());
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

    private static DockerOperations.ContainerIdentity running(String id, String startedAt) {
        return new DockerOperations.ContainerIdentity(id, true, startedAt, id);
    }
}
