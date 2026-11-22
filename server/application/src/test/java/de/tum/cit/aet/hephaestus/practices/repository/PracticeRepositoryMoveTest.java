package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** A move applies its folder only under the setting it was read under. */
class PracticeRepositoryMoveTest extends BaseUnitTest {
    private static final WorkspaceContext CONTEXT = new WorkspaceContext(7L, "acme", "Acme", null, null, Set.of());
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final Instant SET_AT = Instant.parse("2026-11-21T10:00:00Z");

    private final PracticeRepositorySettingRepository settings = mock(PracticeRepositorySettingRepository.class);
    private final PracticeService practices = mock(PracticeService.class);
    private final CatalogPracticeDocument document = mock(CatalogPracticeDocument.class);
    private final PracticeRepositoryMove move;

    PracticeRepositoryMoveTest() {
        var workspaces = mock(WorkspaceRepository.class);
        var workspace = new Workspace();
        workspace.setId(7L);
        when(workspaces.findByIdForUpdate(7L)).thenReturn(Optional.of(workspace));
        move = new PracticeRepositoryMove(settings, practices, WorkspacePortFixtures.queries(workspaces));
    }

    private void current(Instant updatedAt) {
        var setting = new PracticeRepositorySetting();
        setting.setRepositoryItemId(42);
        setting.setDirectory(".hephaestus/practices");
        ReflectionTestUtils.setField(setting, "updatedAt", updatedAt);
        when(settings.findById(7L)).thenReturn(Optional.of(setting));
    }

    private static PracticeRepositoryFolders.Folder folderReadAt(Instant settingUpdatedAt) {
        return new PracticeRepositoryFolders.Folder(
                COMMIT,
                Map.of(),
                new PracticeRepositoryFolders.ReadUnder(42, ".hephaestus/practices", 0, settingUpdatedAt));
    }

    @Test
    void shouldMoveUnderTheSettingTheFolderWasReadUnder() {
        current(SET_AT);

        move.apply(CONTEXT, "explain-the-change", document, folderReadAt(SET_AT));

        verify(practices).moveToRepository(CONTEXT, "explain-the-change", document, COMMIT);
    }

    @Test
    void shouldRefuseWhenTheSettingWasUnsetAndSetAgainMeanwhile() {
        // The same repository, directory and revision, but a new row.
        current(SET_AT.plusSeconds(5));

        assertThatThrownBy(() -> move.apply(CONTEXT, "explain-the-change", document, folderReadAt(SET_AT)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed while its folder was read");
        verify(practices, never()).moveToRepository(any(), anyString(), eq(document), anyString());
    }
}
