package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.catalog.CatalogLock;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PracticeCatalogInstallationManagerTest extends BaseUnitTest {
    @Test
    void shouldKeepAnExistingInstallReceiptWhenTheWorkspaceWasAlreadyInstalled() {
        var workspaces = mock(WorkspaceRepository.class);
        var workspace = new Workspace();
        workspace.setPracticeCatalogInstalledAt(Instant.parse("2026-10-01T12:00:00Z"));
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        var installer = new PracticeCatalogInstallationManager(
                mock(CatalogService.class),
                mock(CatalogLock.class),
                WorkspacePortFixtures.queries(workspaces),
                WorkspacePortFixtures.commands(workspaces),
                mock(PracticeGroupService.class),
                mock(PracticeGroupRepository.class),
                mock(PracticeService.class),
                mock(PracticeRepository.class));
        installer.install(10L);
        assertThat(workspace.getPracticeCatalogInstalledAt()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
    }
}
