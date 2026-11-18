package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeFiles;
import de.tum.cit.aet.hephaestus.practices.PracticeFolderInvalidException;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Move to repository moves a practice only when its folder is on the default branch and valid. */
class PracticeRepositoryServiceTest extends BaseUnitTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final WorkspaceContext CONTEXT = new WorkspaceContext(7L, "acme", "Acme", null, null, Set.of());
    private static final Map<String, String> FILES = Map.of("practice.md", "---\n---\n");

    private final PracticeRepositoryFolders folders = mock(PracticeRepositoryFolders.class);
    private final PracticeFiles files = mock(PracticeFiles.class);
    private final PracticeService practices = mock(PracticeService.class);
    private final PracticeRepositoryService service = new PracticeRepositoryService(
            mock(PracticeRepositorySettingRepository.class),
            mock(MonitoredRepositoryQuery.class),
            folders,
            files,
            practices,
            mock(ConfigAuditPort.class));

    /** Without this guard, the first sync would find no folder and set the practice to Reviewed by Nobody. */
    @Test
    void shouldNotMoveAPracticeWhoseFolderIsNotOnTheDefaultBranch() {
        when(folders.read(7, "explain")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveToRepository(CONTEXT, "explain"))
                .isInstanceOf(ResponseStatusException.class);
        verify(practices, never()).moveToRepository(any(), any(), any(), any());
    }

    @Test
    void shouldNotMoveAPracticeWhoseFolderIsInvalid() {
        when(folders.read(7, "explain")).thenReturn(Optional.of(new PracticeRepositoryFolders.Folder(COMMIT, FILES)));
        when(files.read("explain", FILES))
                .thenReturn(new PracticeFiles.Read(
                        null,
                        List.of(new PracticeFileProblemDTO(
                                "practice.md", 1, 1, PracticeFileProblemDTO.FRONT_MATTER_INVALID, "broken"))));

        assertThatThrownBy(() -> service.moveToRepository(CONTEXT, "explain"))
                .isInstanceOf(PracticeFolderInvalidException.class);
        verify(practices, never()).moveToRepository(any(), any(), any(), any());
    }

    @Test
    void shouldMoveAPracticeWithTheCommitItsFolderWasReadAt() {
        var document =
                new CatalogPracticeDocument(new ItemKind("scm.pull_request"), null, DefinitionTestFixtures.definition());
        when(folders.read(7, "explain")).thenReturn(Optional.of(new PracticeRepositoryFolders.Folder(COMMIT, FILES)));
        when(files.read("explain", FILES)).thenReturn(new PracticeFiles.Read(document, List.of()));

        service.moveToRepository(CONTEXT, "explain");

        verify(practices).moveToRepository(CONTEXT, "explain", document, COMMIT);
    }
}
