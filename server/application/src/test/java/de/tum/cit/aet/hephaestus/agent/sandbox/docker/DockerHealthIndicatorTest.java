package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DockerHealthIndicatorTest extends BaseUnitTest {

    @Mock
    private SandboxContainerManager containerManager;

    private DockerHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        SandboxProperties properties = new SandboxProperties(5, 10, 60, 209_715_200L, 500_000, null);
        var dockerProperties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
        indicator = new DockerHealthIndicator(containerManager, properties, dockerProperties);
    }

    @Test
    void shouldReportUpWhenReachable() {
        when(containerManager.ping()).thenReturn(true);
        when(containerManager.listManagedContainers()).thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("dockerHost");
        assertThat(health.getDetails()).containsEntry("activeContainers", 0);
        assertThat(health.getDetails()).containsEntry("maxConcurrentContainers", 5);
    }

    @Test
    void shouldReportDownWhenUnreachable() {
        when(containerManager.ping()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Docker daemon not reachable");
    }

    @Test
    void shouldReportDownOnException() {
        when(containerManager.ping()).thenThrow(new RuntimeException("Connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Connection refused");
    }
}
