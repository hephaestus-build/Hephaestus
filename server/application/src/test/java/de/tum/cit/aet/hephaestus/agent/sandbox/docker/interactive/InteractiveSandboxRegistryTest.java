package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxContainerManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InteractiveSandboxRegistryTest extends BaseUnitTest {

    @Test
    void shouldReportNoCapacityOnceADeveloperReachesThePerUserCap() {
        InteractiveSandboxRegistry registry = registry(2, 50);

        registry.tryRegister(session("ada", "1"));
        assertThat(registry.hasCapacity("ada")).isTrue();
        DockerAttachedSandboxAdapter second = session("ada", "2");
        registry.tryRegister(second);

        assertThat(registry.hasCapacity("ada")).isFalse();
        assertThat(registry.hasCapacity("grace")).isTrue();

        registry.onSandboxClosed(second);
        assertThat(registry.hasCapacity("ada")).isTrue();
    }

    @Test
    void shouldReportNoCapacityForAnyoneOnceTheReplicaReachesTheTotalCap() {
        InteractiveSandboxRegistry registry = registry(3, 2);

        registry.tryRegister(session("ada", "1"));
        registry.tryRegister(session("grace", "1"));

        assertThat(registry.hasCapacity("ada")).isFalse();
        assertThat(registry.hasCapacity("linus")).isFalse();
    }

    private static InteractiveSandboxRegistry registry(int maxPerUser, int maxTotal) {
        var properties =
                new InteractiveSandboxProperties(900, 1, 1, 512, 5000, 64, 64, 30, maxPerUser, maxTotal, 1048576);
        var meters = new SimpleMeterRegistry();
        return new InteractiveSandboxRegistry(
                properties,
                mock(SandboxContainerManager.class),
                new InteractiveSandboxMetrics(meters),
                new StdinWriteWatchdog(),
                meters);
    }

    private static DockerAttachedSandboxAdapter session(String userId, String workspaceId) {
        var session = mock(DockerAttachedSandboxAdapter.class);
        when(session.identity()).thenReturn(new SandboxIdentity(UUID.randomUUID(), userId, workspaceId));
        return session;
    }
}
