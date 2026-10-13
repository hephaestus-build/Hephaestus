package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class DockerHostSandboxTest extends BaseUnitTest {

    private static final HostSandbox.HostSpec SPEC = new HostSandbox.HostSpec(
            Map.of(),
            Map.of(),
            List.of("node"),
            new ResourceLimits(256L * 1024 * 1024, 1.0, 64, Duration.ofMinutes(1)));

    @Mock
    private SandboxNetworkManager networks;

    @Mock
    private SandboxWorkspaceManager workspaces;

    @Mock
    private SandboxContainerManager containers;

    @Mock
    private ContainerSecurityPolicy securityPolicy;

    @Mock
    private SandboxCreator creator;

    @Mock
    private SandboxGatewaySessions gatewaySessions;

    @Mock
    private DockerVolumeOperations volumes;

    private DockerHostSandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new DockerHostSandbox(
                networks,
                workspaces,
                containers,
                securityPolicy,
                creator,
                gatewaySessions,
                volumes,
                "heph-host:test",
                "worker-1",
                8081);
    }

    @Test
    void shouldStartNoHostWhenTheWorkerRunsInAContainerThatItCannotIdentify() {
        when(creator.labels()).thenReturn(Map.of());
        when(creator.outsideContainer()).thenReturn(false);

        assertThatThrownBy(() -> sandbox.launch(SPEC)).isInstanceOf(IOException.class);

        verify(networks, never()).createJobNetwork(any(), anyBoolean(), any());
    }

    @Test
    void shouldGiveTheHostANetworkWithARouteOutOnlyWhenTheWorkerRunsOutsideAnyContainer() {
        when(creator.labels()).thenReturn(Map.of());
        when(creator.outsideContainer()).thenReturn(true);
        when(networks.createJobNetwork(any(), anyBoolean(), any())).thenThrow(new IllegalStateException("stop here"));

        assertThatThrownBy(() -> sandbox.launch(SPEC)).hasMessage("stop here");

        verify(networks).createJobNetwork(any(), eq(true), any());
    }

    @Test
    void shouldGiveTheHostAnInternalNetworkWhenTheWorkerKnowsItsContainer() {
        when(creator.labels())
                .thenReturn(Map.of(
                        SandboxLabels.CREATOR_CONTAINER, "3f2a9c1b7d4e", SandboxLabels.CREATOR_STARTED_AT, "t1"));
        when(networks.createJobNetwork(any(), anyBoolean(), any())).thenThrow(new IllegalStateException("stop here"));

        assertThatThrownBy(() -> sandbox.launch(SPEC)).hasMessage("stop here");

        verify(networks).createJobNetwork(any(), eq(false), any());
    }
}
