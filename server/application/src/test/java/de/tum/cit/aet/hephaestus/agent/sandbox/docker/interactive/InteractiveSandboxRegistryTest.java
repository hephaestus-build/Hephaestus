package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerOperations;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxContainerManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxCreator;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxCreator.Liveness;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxLabels;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
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

    @Test
    void shouldRemoveOnlyInteractiveContainersWhoseCreatorIsGoneWhenStarting() {
        var containers = mock(SandboxContainerManager.class);
        var creator = mock(SandboxCreator.class);
        var managed = List.of(
                interactive("other-live-worker", Liveness.RUNNING, creator),
                interactive("earlier-start", Liveness.GONE, creator),
                interactive("no-creator", Liveness.UNRECORDED, creator),
                interactive("unreadable-creator", Liveness.UNREADABLE, creator));
        when(containers.listManagedContainers()).thenReturn(managed);
        var meters = new SimpleMeterRegistry();
        var registry = new InteractiveSandboxRegistry(
                new InteractiveSandboxProperties(900, 1, 1, 512, 5000, 64, 64, 30, 3, 50, 1048576),
                containers,
                new StdinWriteWatchdog(),
                meters,
                creator);

        registry.onStartup();

        verify(containers).forceRemove("earlier-start");
        verify(containers, never()).forceRemove("other-live-worker");
        verify(containers, never()).forceRemove("no-creator");
        verify(containers, never()).forceRemove("unreadable-creator");
    }

    private static DockerOperations.ContainerInfo interactive(
            String id, Liveness creatorLiveness, SandboxCreator creator) {
        var labels = Map.of(SandboxLabels.KIND, SandboxLabels.KIND_INTERACTIVE, SandboxLabels.CREATOR_CONTAINER, id);
        when(creator.liveness(labels)).thenReturn(creatorLiveness);
        return new DockerOperations.ContainerInfo(id, id, labels, "running", null);
    }

    private static InteractiveSandboxRegistry registry(int maxPerUser, int maxTotal) {
        var properties =
                new InteractiveSandboxProperties(900, 1, 1, 512, 5000, 64, 64, 30, maxPerUser, maxTotal, 1048576);
        var meters = new SimpleMeterRegistry();
        return new InteractiveSandboxRegistry(
                properties,
                mock(SandboxContainerManager.class),
                new StdinWriteWatchdog(),
                meters,
                mock(SandboxCreator.class));
    }

    private static DockerAttachedSandboxAdapter session(String userId, String workspaceId) {
        var session = mock(DockerAttachedSandboxAdapter.class);
        when(session.identity()).thenReturn(new SandboxIdentity(UUID.randomUUID(), userId, workspaceId));
        return session;
    }
}
