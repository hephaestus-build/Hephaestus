package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceSlugServiceTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(strings = {"docs", "pr123", "a", "über/team", "--", "team-", "xn--example"})
    void shouldAllocateAssignableLabelWhenProviderNameIsNotASafeWorkspaceAddress(String desired) {
        var service = new WorkspaceSlugService(
                mock(WorkspaceRepository.class),
                mock(WorkspaceSlugHistoryRepository.class),
                mock(WorkspaceSlugReservationRepository.class));
        assertThat(WorkspaceSlugValidator.isAssignable(service.allocate(desired, "installation")))
                .isTrue();
        assertThat(WorkspaceSlugValidator.isAssignable(service.allocate(desired.repeat(100), "installation")))
                .isTrue();
    }

    @Test
    void shouldPreserveProviderNameWhenPrefixIsNotPunycode() {
        var service = new WorkspaceSlugService(
                mock(WorkspaceRepository.class),
                mock(WorkspaceSlugHistoryRepository.class),
                mock(WorkspaceSlugReservationRepository.class));
        assertThat(service.allocate("xn-project", "installation")).isEqualTo("xn-project");
    }

    @Test
    void shouldRejectAvailabilityWhenSlugReservationSurvivesWorkspaceDeletion() {
        var reservations = mock(WorkspaceSlugReservationRepository.class);
        when(reservations.existsById("deleted-team")).thenReturn(true);
        var service = new WorkspaceSlugService(
                mock(WorkspaceRepository.class), mock(WorkspaceSlugHistoryRepository.class), reservations);
        assertThat(service.isAvailable("deleted-team")).isFalse();
        assertThat(service.allocate("deleted-team", "other-installation")).isNotEqualTo("deleted-team");
    }
}
