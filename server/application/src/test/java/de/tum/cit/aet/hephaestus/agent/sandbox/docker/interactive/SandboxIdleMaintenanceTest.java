package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxContainerManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxMaintenanceConfiguration;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxReconciler;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandboxState;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.EvictionReason;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SandboxIdleMaintenanceTest extends BaseUnitTest {

    @Test
    void shouldEvictOnlyIdleSessionsOnAWorkerWithoutServerScheduling() {
        var properties = new InteractiveSandboxProperties(300, 1, 1, 512, 5000, 64, 64, 30, 3, 50, 1048576);
        var watchdog = new StdinWriteWatchdog();
        var meters = new SimpleMeterRegistry();
        var registry = new InteractiveSandboxRegistry(
                properties,
                mock(SandboxContainerManager.class),
                new InteractiveSandboxMetrics(meters),
                watchdog,
                meters);
        var idle = session("idle-user", Duration.ofMinutes(6));
        var active = session("active-user", Duration.ZERO);

        new ApplicationContextRunner()
                .withUserConfiguration(SandboxMaintenanceConfiguration.class)
                .withPropertyValues("hephaestus.runtime.server.enabled=false")
                .withBean(InteractiveSandboxProperties.class, () -> properties)
                .withBean(SandboxProperties.class, () -> new SandboxProperties(5, 10, 60, null))
                .withBean(StdinWriteWatchdog.class, () -> watchdog)
                .withBean(InteractiveSandboxRegistry.class, () -> registry)
                .withBean(SandboxReconciler.class, () -> mock(SandboxReconciler.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(registry.tryRegister(idle))
                            .isEqualTo(InteractiveSandboxRegistry.RegistrationOutcome.REGISTERED);
                    assertThat(registry.tryRegister(active))
                            .isEqualTo(InteractiveSandboxRegistry.RegistrationOutcome.REGISTERED);
                    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                        verify(idle, atLeastOnce()).terminate(EvictionReason.IDLE);
                        verify(active, atLeastOnce()).idleFor();
                    });
                    verify(active, never()).terminate(EvictionReason.IDLE);
                });
        meters.close();
    }

    private static DockerAttachedSandboxAdapter session(String userId, Duration idleFor) {
        var session = mock(DockerAttachedSandboxAdapter.class);
        when(session.identity()).thenReturn(new SandboxIdentity(UUID.randomUUID(), userId, "workspace"));
        when(session.state()).thenReturn(AttachedSandboxState.ATTACHED);
        when(session.idleFor()).thenReturn(idleFor);
        return session;
    }
}
