package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxCreator.Liveness;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class SandboxCreatorTest extends BaseUnitTest {

    /** Docker's default hostname is the short container id. */
    private static final String SHORT_ID = "3f2a9c1b7d4e";

    private static final String FULL_ID = SHORT_ID + "5a6b7c8d9e0f";

    private static final Map<String, String> CREATED_IN_T1 =
            Map.of(SandboxLabels.CREATOR_CONTAINER, FULL_ID, SandboxLabels.CREATOR_STARTED_AT, "t1");

    @Mock
    private DockerInspectOperations containers;

    private void inspecting(String idOrName, String id, boolean running, String startedAt, String hostname) {
        when(containers.inspectContainerIdentity(idOrName))
                .thenReturn(Optional.of(new DockerOperations.ContainerIdentity(id, running, startedAt, hostname)));
    }

    @Test
    void shouldRecordTheContainerWhoseIdIsThisProcessesDefaultHostname() {
        // Independent of whichever app-server container is configured as the model proxy.
        inspecting(SHORT_ID, FULL_ID, true, "t1", SHORT_ID);

        var creator = new SandboxCreator(containers, () -> SHORT_ID);

        assertThat(creator.labels()).isEqualTo(CREATED_IN_T1);
        assertThat(creator.createdBySelf(CREATED_IN_T1)).isTrue();
    }

    @Test
    void shouldRecordNoCreatorWhenTheHostnameIsCustomBecauseOtherContainersMayShareIt() {
        // Container "server" and container "server-replica" can both have hostname "server".
        var creator = new SandboxCreator(containers, () -> "server");

        assertThat(creator.labels()).isEmpty();
        verify(containers, never()).inspectContainerIdentity(any());
    }

    @Test
    void shouldRecordNoCreatorWhenAnIdShapedHostnameResolvesToAnotherContainer() {
        // Docker resolves a name before an id prefix, so a container may be named like an id.
        inspecting(SHORT_ID, "9e8d7c6b5a4f3e2d", true, "t1", SHORT_ID);

        assertThat(new SandboxCreator(containers, () -> SHORT_ID).labels()).isEmpty();
    }

    @Test
    void shouldRecordNoCreatorAndRetryWhenTheOwnStateIsUnreadable() {
        when(containers.inspectContainerIdentity(SHORT_ID))
                .thenThrow(new SandboxInfrastructureException("Incomplete state for container: " + SHORT_ID))
                .thenReturn(Optional.of(new DockerOperations.ContainerIdentity(FULL_ID, true, "t1", SHORT_ID)));
        var creator = new SandboxCreator(containers, () -> SHORT_ID);

        assertThat(creator.labels()).isEmpty();
        assertThat(creator.labels()).isEqualTo(CREATED_IN_T1);
    }

    @Test
    void shouldRecordNoCreatorOutsideDocker() {
        assertThat(new SandboxCreator(containers, () -> null).labels()).isEmpty();
    }

    @Test
    void shouldCallTheCreatorRunningOnlyInTheSameStartAndGoneOnlyOnEvidence() {
        var creator = new SandboxCreator(containers, () -> null);

        inspecting(FULL_ID, FULL_ID, true, "t1", SHORT_ID);
        assertThat(creator.liveness(CREATED_IN_T1)).isEqualTo(Liveness.RUNNING);

        inspecting(FULL_ID, FULL_ID, true, "t2", SHORT_ID);
        assertThat(creator.liveness(CREATED_IN_T1)).isEqualTo(Liveness.GONE);

        inspecting(FULL_ID, FULL_ID, false, "", SHORT_ID);
        assertThat(creator.liveness(CREATED_IN_T1)).isEqualTo(Liveness.GONE);

        when(containers.inspectContainerIdentity(FULL_ID)).thenReturn(Optional.empty());
        assertThat(creator.liveness(CREATED_IN_T1)).isEqualTo(Liveness.GONE);

        when(containers.inspectContainerIdentity(FULL_ID))
                .thenThrow(new SandboxInfrastructureException("Incomplete state for container: " + FULL_ID));
        assertThat(creator.liveness(CREATED_IN_T1)).isEqualTo(Liveness.UNREADABLE);
    }

    @Test
    void shouldNotTakeMissingOrBlankCreatorLabelsForARecord() {
        var creator = new SandboxCreator(containers, () -> null);

        assertThat(creator.liveness(Map.of())).isEqualTo(Liveness.UNRECORDED);
        assertThat(creator.liveness(
                        Map.of(SandboxLabels.CREATOR_CONTAINER, FULL_ID, SandboxLabels.CREATOR_STARTED_AT, "")))
                .isEqualTo(Liveness.UNRECORDED);
        verify(containers, never()).inspectContainerIdentity(any());
    }
}
