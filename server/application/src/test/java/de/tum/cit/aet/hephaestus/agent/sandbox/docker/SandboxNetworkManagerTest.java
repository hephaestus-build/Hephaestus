package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
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

    private SandboxNetworkManager manager;

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String NETWORK_ID = "net-abc123";

    @BeforeEach
    void setUp() {
        DockerSandboxProperties properties = new DockerSandboxProperties(
                "unix:///var/run/docker.sock", false, null, null, "app-server-id", "docker");
        manager = new SandboxNetworkManager(networkOps, properties);
    }

    @Nested
    class CreateJobNetwork {

        @Test
        void shouldCreateInternalNetwork() {
            when(networkOps.createNetwork(anyString(), eq(true))).thenReturn(NETWORK_ID);

            String networkId = manager.createJobNetwork(JOB_ID, false);

            assertThat(networkId).isEqualTo(NETWORK_ID);
            verify(networkOps).createNetwork("agent-net-" + JOB_ID, true);
        }

        @Test
        @DisplayName("a network an interrupted run left under this job's name is removed, not fought over")
        void shouldReplaceLeftoverNetworkOfTheSameJob() {
            String networkName = "agent-net-" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName)));
            when(networkOps.createNetwork(anyString(), eq(true))).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false)).isEqualTo(NETWORK_ID);

            verify(networkOps).disconnectFromNetwork("stale-net", "app-server-id");
            verify(networkOps).removeNetwork("stale-net");
            verify(networkOps).createNetwork(networkName, true);
        }

        @Test
        @DisplayName("a leftover network that will not go is reported, not worked around")
        void shouldFailWhenLeftoverNetworkCannotBeRemoved() {
            String networkName = "agent-net-" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("stale-net", networkName)));
            Mockito.doThrow(new SandboxInfrastructureException("network has active endpoints"))
                    .when(networkOps)
                    .removeNetwork("stale-net");

            assertThatThrownBy(() -> manager.createJobNetwork(JOB_ID, false))
                    .isInstanceOf(SandboxInfrastructureException.class);

            verify(networkOps, Mockito.never()).createNetwork(anyString(), Mockito.anyBoolean());
        }

        @Test
        @DisplayName("another job's network is left alone even when the daemon returns it")
        void shouldLeaveOtherJobsNetworksAlone() {
            String networkName = "agent-net-" + JOB_ID;
            when(networkOps.listNetworksByName(networkName))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("other-net", networkName + "-suffix")));
            when(networkOps.createNetwork(anyString(), eq(true))).thenReturn(NETWORK_ID);

            assertThat(manager.createJobNetwork(JOB_ID, false)).isEqualTo(NETWORK_ID);

            verify(networkOps, Mockito.never()).removeNetwork(anyString());
        }

        @Test
        void shouldCreateBridgeNetwork() {
            when(networkOps.createNetwork(anyString(), eq(false))).thenReturn(NETWORK_ID);

            String networkId = manager.createJobNetwork(JOB_ID, true);

            assertThat(networkId).isEqualTo(NETWORK_ID);
            verify(networkOps).createNetwork("agent-net-" + JOB_ID, false);
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
                    "unix:///var/run/docker.sock", false, null, null, containerId, "docker");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, () -> "hostname-container-id");

            when(networkOps.connectToNetwork(NETWORK_ID, "hostname-container-id"))
                    .thenReturn("172.18.0.3");

            String ip = mgr.connectAppServer(NETWORK_ID);

            assertThat(ip).isEqualTo("172.18.0.3");
            verify(networkOps).connectToNetwork(NETWORK_ID, "hostname-container-id");
        }

        @Test
        void shouldReturnNullWhenNoContainerId() {
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, () -> null);

            String ip = mgr.connectAppServer(NETWORK_ID);

            assertThat(ip).isNull();
        }

        @Test
        void shouldReturnNullWhenHostnameBlank() {
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, () -> "  ");

            String ip = mgr.connectAppServer(NETWORK_ID);

            assertThat(ip).isNull();
        }

        @Test
        void shouldCacheContainerId() {
            var callCount = new AtomicInteger(0);
            DockerSandboxProperties propsNoId =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, () -> {
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
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
            SandboxNetworkManager mgr = new SandboxNetworkManager(networkOps, propsNoId, () -> null);

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
    class ListOrphanedNetworks {

        @Test
        void shouldListByPrefix() {
            when(networkOps.listNetworksByName("agent-net-"))
                    .thenReturn(List.of(new DockerOperations.NetworkInfo("n1", "agent-net-" + JOB_ID)));

            var networks = manager.listOrphanedNetworks();

            assertThat(networks).hasSize(1);
        }
    }
}
