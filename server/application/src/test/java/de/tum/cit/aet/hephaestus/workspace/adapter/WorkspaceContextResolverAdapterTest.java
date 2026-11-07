package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationDispatchScope;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceContextResolverAdapterTest {

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceContextResolverAdapter resolver = new WorkspaceContextResolverAdapter(workspaces);

    @Test
    void shouldUseTheDispatchWorkspaceForOrganizationAndRepositoryLookups() {
        new IntegrationDispatchScope(42L).call(() -> {
            assertThat(resolver.findScopeIdByOrgLogin("shared")).contains(42L);
            assertThat(resolver.findScopeIdByRepositoryName("shared/repository"))
                    .contains(42L);
            return null;
        });
    }

    @Test
    void shouldLeaveInstallationDeliveryUnscopedAndRestoreTheOuterDelivery() {
        var workspace = new Workspace();
        workspace.setId(99L);
        when(workspaces.findByAccountLoginIgnoreCase("shared")).thenReturn(Optional.of(workspace));
        when(workspaces.findByRepositoriesToMonitor_NameWithOwner("shared/repository"))
                .thenReturn(Optional.of(workspace));
        new IntegrationDispatchScope(42L).call(() -> {
            new IntegrationDispatchScope(null).call(() -> {
                assertThat(resolver.findScopeIdByOrgLogin("shared")).isEmpty();
                assertThat(resolver.findScopeIdByRepositoryName("shared/repository"))
                        .isEmpty();
                return null;
            });
            assertThat(resolver.findScopeIdByOrgLogin("shared")).contains(42L);
            return null;
        });
    }

    @Test
    void shouldRestoreLookupAfterFailedDispatch() {
        var workspace = new Workspace();
        workspace.setId(99L);
        when(workspaces.findByAccountLoginIgnoreCase("shared")).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> new IntegrationDispatchScope(42L).call(() -> {
                    assertThat(resolver.findScopeIdByOrgLogin("shared")).contains(42L);
                    throw new IllegalStateException("failed dispatch");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(resolver.findScopeIdByOrgLogin("shared")).contains(99L);
    }
}
