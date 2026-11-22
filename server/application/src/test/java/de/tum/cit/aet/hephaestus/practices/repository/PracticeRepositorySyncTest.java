package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeDrafts;
import de.tum.cit.aet.hephaestus.practices.PracticeFiles;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositorySyncDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncResultDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncStatus;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The apply step of a sync (ADR 0118): each row of the sync table, a second sync of the same commit, and a sync whose
 * setting changed while the folders were read. Reading the folders is {@link PracticeRepositoryFoldersTest}'s, and the
 * rows a sync writes are {@code PracticeRepositorySyncIntegrationTest}'s.
 */
class PracticeRepositorySyncTest extends BaseUnitTest {
    private static final WorkspaceContext CONTEXT = new WorkspaceContext(7L, "acme", "Acme", null, null, Set.of());
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String DIRECTORY = ".hephaestus/practices";
    private static final Instant SET_AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-22T09:00:00Z");
    /** When the folders in these tests were read: after every practice the tests build. */
    private static final Instant READ_STARTED_AT = Instant.parse("2026-11-22T08:00:00Z");

    private static final ItemKind KIND = new ItemKind("scm.pull_request");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final PracticeDefinition DEFINITION = DefinitionTestFixtures.definition();
    private static final PracticeDefinition CHANGED =
            DefinitionTestFixtures.withCriteria(DEFINITION, "Explain why the change is needed.");

    private final AtomicLong ids = new AtomicLong(100);
    private final PracticeRepositorySettingRepository settings = mock(PracticeRepositorySettingRepository.class);
    private final PracticeService practices = mock(PracticeService.class);
    private final PracticeRepository rows = mock(PracticeRepository.class);
    private final PracticeDrafts drafts = mock(PracticeDrafts.class);
    private final PracticeGroupRepository groups = mock(PracticeGroupRepository.class);
    private final PracticeVersionService versions = mock(PracticeVersionService.class);
    private final PracticeVersionRepository versionRows = mock(PracticeVersionRepository.class);
    private final PracticeDefinitionValidator validator = mock(PracticeDefinitionValidator.class);
    private final List<Practice> existing = new ArrayList<>();
    private final PracticeRepositorySync sync;

    PracticeRepositorySyncTest() {
        var workspaces = mock(WorkspaceRepository.class);
        var workspace = new Workspace();
        workspace.setId(7L);
        when(workspaces.findByIdForUpdate(7L)).thenReturn(Optional.of(workspace));
        sync = new PracticeRepositorySync(
                settings,
                practices,
                rows,
                drafts,
                groups,
                versions,
                versionRows,
                validator,
                WorkspacePortFixtures.queries(workspaces),
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        current(SET_AT);
        when(rows.findByWorkspaceId(7L)).thenReturn(existing);
        when(drafts.withDrafts(any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        when(versions.definitionOf(any()))
                .thenAnswer(call -> MAPPER.treeToValue(
                        call.<PracticeVersion>getArgument(0).getDefinition(), PracticeDefinition.class));
    }

    @Test
    void shouldCreateAPracticeFromAFolderWhoseSlugTheWorkspaceLacks() {
        when(practices.createFromRepository(CONTEXT, "explain", document(DEFINITION), COMMIT))
                .thenReturn(detached("explain", DEFINITION, PracticeEditedIn.REPOSITORY));

        var report = apply(Map.of("explain", valid(DEFINITION)));

        verify(practices).createFromRepository(CONTEXT, "explain", document(DEFINITION), COMMIT);
        // No trial ran the new version's review digest.
        assertThat(report.practices()).containsExactly(untried("explain", PracticeSyncStatus.CREATED));
    }

    @Test
    void shouldTakeOverADraftPracticeWithTheFolderSlug() {
        var draft = practice("explain", DEFINITION, PracticeEditedIn.HEPHAESTUS);
        ReflectionTestUtils.setField(draft, "state", PracticeState.DRAFT);

        when(practices.takeOverDraft(CONTEXT, draft, document(DEFINITION), COMMIT))
                .thenReturn(draft);
        when(versionRows.existsTrialWithDigest(
                        7L, draft.getId(), draft.getCurrentVersion().getReviewDigest()))
                .thenReturn(true);

        var report = apply(Map.of("explain", valid(DEFINITION)));

        verify(practices).takeOverDraft(CONTEXT, draft, document(DEFINITION), COMMIT);
        // A trial of the draft ran this review digest.
        assertThat(report.practices()).containsExactly(result("explain", PracticeSyncStatus.TAKEN_OVER));
    }

    @Test
    void shouldUpdateAPracticeEditedInTheRepositoryWhenItsFolderChanged() {
        practice("explain", DEFINITION, PracticeEditedIn.REPOSITORY);
        when(practices.moveToRepository(CONTEXT, "explain", document(CHANGED), COMMIT))
                .thenReturn(detached("explain", CHANGED, PracticeEditedIn.REPOSITORY));

        var report = apply(Map.of("explain", valid(CHANGED)));

        verify(practices).moveToRepository(CONTEXT, "explain", document(CHANGED), COMMIT);
        assertThat(report.practices()).containsExactly(untried("explain", PracticeSyncStatus.UPDATED));
    }

    /** The state after a sync is the input of the next one: a second sync of the same commit writes nothing. */
    @Test
    void shouldChangeNothingWhenTheSameCommitIsSyncedAgain() {
        practice("explain", CHANGED, PracticeEditedIn.REPOSITORY);
        practice("gone", retired(DEFINITION), PracticeEditedIn.REPOSITORY);

        var report = apply(Map.of("explain", valid(CHANGED)));

        verifyNoInteractions(practices);
        assertThat(report.practices())
                .containsExactly(
                        result("explain", PracticeSyncStatus.UNCHANGED), result("gone", PracticeSyncStatus.RETIRED));
    }

    @Test
    void shouldLeaveAPracticeEditedInHephaestusAsItIs() {
        practice("explain", DEFINITION, PracticeEditedIn.HEPHAESTUS);

        var report = apply(Map.of("explain", valid(CHANGED)));

        verifyNoInteractions(practices);
        assertThat(report.practices()).containsExactly(result("explain", PracticeSyncStatus.EDITED_IN_HEPHAESTUS));
    }

    /** A broken file never removes, retires or edits a practice: the practice keeps its current version. */
    @Test
    void shouldChangeNothingForAnInvalidOrUnreadableFolder() {
        practice("explain", DEFINITION, PracticeEditedIn.REPOSITORY);
        practice("linked", DEFINITION, PracticeEditedIn.REPOSITORY);
        var problem = new PracticeFileProblemDTO(
                PracticeFiles.PRACTICE, 4, 3, PracticeFileProblemDTO.UNKNOWN_KEY, "Remove the key colour.");
        var link = new PracticeFileProblemDTO(
                "guide.md", null, null, PracticeFileProblemDTO.FILE_INVALID, "A practice folder holds no links.");

        var report = sync.apply(
                CONTEXT,
                tree(Map.of("explain", Map.of()), Map.of("linked", List.of(link))),
                Map.of("explain", new PracticeFiles.Read(null, List.of(problem))));

        verifyNoInteractions(practices);
        assertThat(report.practices())
                .containsExactly(
                        new PracticeSyncResultDTO("explain", PracticeSyncStatus.INVALID, List.of(problem), false),
                        new PracticeSyncResultDTO("linked", PracticeSyncStatus.INVALID, List.of(link), false));
    }

    @Test
    void shouldRefuseAFolderThatChangesTheKindOrNamesAGroupTheWorkspaceLacks() {
        practice("explain", DEFINITION, PracticeEditedIn.REPOSITORY);
        when(groups.findByWorkspaceIdAndSlug(7L, "release")).thenReturn(Optional.empty());

        var report = apply(Map.of(
                "explain",
                new PracticeFiles.Read(
                        new CatalogPracticeDocument(new ItemKind("scm.issue"), null, CHANGED), List.of()),
                "grouped",
                new PracticeFiles.Read(new CatalogPracticeDocument(KIND, "release", DEFINITION), List.of())));

        verifyNoInteractions(practices);
        assertThat(report.practices())
                .extracting(PracticeSyncResultDTO::status)
                .containsOnly(PracticeSyncStatus.INVALID);
        assertThat(report.practices())
                .flatExtracting(PracticeSyncResultDTO::problems)
                .extracting(PracticeFileProblemDTO::code)
                .containsExactly(PracticeFileProblemDTO.KIND_CHANGED, PracticeFileProblemDTO.UNKNOWN_GROUP);
    }

    @Test
    void shouldRetireAPracticeEditedInTheRepositoryWhoseFolderIsGone() {
        practice("gone", DEFINITION, PracticeEditedIn.REPOSITORY);
        practice("kept-here", DEFINITION, PracticeEditedIn.HEPHAESTUS);

        var report = apply(Map.of());

        // As Reviewed by Nobody in the editor: everything else stays, so a returning folder restores it.
        verify(practices).moveToRepository(CONTEXT, "gone", document(retired(DEFINITION)), COMMIT);
        assertThat(report.practices()).containsExactly(result("gone", PracticeSyncStatus.RETIRED));
    }

    /**
     * A practice moved to the repository, or given a version, after the folders were read is newer than the read. Its
     * folder may be missing only from the older read, so the sync leaves it, and a folder of its slug, alone.
     */
    @Test
    void shouldLeaveAPracticeThatChangedWhileTheFoldersWereRead() {
        var moved = practice("moved-meanwhile", DEFINITION, PracticeEditedIn.REPOSITORY);
        ReflectionTestUtils.setField(moved, "updatedAt", READ_STARTED_AT.plusSeconds(1));
        var versioned = practice("versioned-meanwhile", DEFINITION, PracticeEditedIn.REPOSITORY);
        ReflectionTestUtils.setField(versioned.getCurrentVersion(), "createdAt", READ_STARTED_AT.plusSeconds(2));

        var report = apply(Map.of("versioned-meanwhile", valid(CHANGED)));

        verifyNoInteractions(practices);
        assertThat(report.practices())
                .containsExactly(
                        result("moved-meanwhile", PracticeSyncStatus.CHANGED_DURING_SYNC),
                        result("versioned-meanwhile", PracticeSyncStatus.CHANGED_DURING_SYNC));
    }

    /**
     * A current version on an older source contract fails the checks of a save, and so would its Nobody version. The
     * write would roll back the whole sync, and every later one: the practice stays, and the other folders apply.
     */
    @Test
    void shouldLeaveAPracticeWhoseVersionCannotBeRetiredAndApplyTheOtherFolders() {
        practice("gone", DEFINITION, PracticeEditedIn.REPOSITORY);
        when(practices.createFromRepository(CONTEXT, "explain", document(DEFINITION), COMMIT))
                .thenReturn(detached("explain", DEFINITION, PracticeEditedIn.REPOSITORY));
        doThrow(new InvalidInputException("Choose the current source contract for a new definition."))
                .when(validator)
                .validate(KIND, retired(DEFINITION));

        var report = apply(Map.of("explain", valid(DEFINITION)));

        verify(practices).createFromRepository(CONTEXT, "explain", document(DEFINITION), COMMIT);
        verify(practices, never()).moveToRepository(any(), eq("gone"), any(), anyString());
        assertThat(report.practices())
                .extracting(PracticeSyncResultDTO::status)
                .containsExactly(PracticeSyncStatus.CREATED, PracticeSyncStatus.RETIRE_REFUSED);
        assertThat(report.practices().get(1).problems())
                .singleElement()
                .satisfies(problem -> assertThat(problem.message())
                        .contains("current source contract")
                        .contains("Restore the folder"));
    }

    /** A missing directory is more often a wrong setting than the removal of every practice. */
    @Test
    void shouldChangeNothingWhenTheDirectoryCannotBeRead() {
        practice("gone", DEFINITION, PracticeEditedIn.REPOSITORY);
        var missing = new PracticeFileProblemDTO(
                DIRECTORY,
                null,
                null,
                PracticeFileProblemDTO.DIRECTORY_MISSING,
                "The default branch has no directory.");
        var tree = new PracticeRepositoryFolders.Tree(
                COMMIT, new TreeMap<>(), new TreeMap<>(), List.of(missing), readAt(SET_AT), null, READ_STARTED_AT);

        var report = sync.apply(CONTEXT, tree, Map.of());

        verifyNoInteractions(practices);
        assertThat(report.practices()).isEmpty();
        assertThat(report.problems()).containsExactly(missing);
        verify(settings).recordSync(eq(7L), eq(COMMIT), eq(NOW), argThat(json -> json.contains("DIRECTORY_MISSING")));
    }

    @Test
    void shouldStoreTheReportWithTheCommitAndTheTime() {
        when(practices.createFromRepository(CONTEXT, "explain", document(DEFINITION), COMMIT))
                .thenReturn(detached("explain", DEFINITION, PracticeEditedIn.REPOSITORY));

        var report = apply(Map.of("explain", valid(DEFINITION)));

        assertThat(report.commit()).isEqualTo(COMMIT);
        assertThat(report.syncedAt()).isEqualTo(NOW);
        verify(settings)
                .recordSync(
                        eq(7L),
                        eq(COMMIT),
                        eq(NOW),
                        argThat(json -> MAPPER.readValue(json, PracticeRepositorySetting.SyncReport.class)
                                .equals(new PracticeRepositorySetting.SyncReport(report.practices(), List.of()))));
    }

    @Test
    void shouldRefuseWhenTheSettingWasUnsetAndSetAgainWhileTheFoldersWereRead() {
        practice("gone", DEFINITION, PracticeEditedIn.REPOSITORY);
        // The same repository, directory and revision, but a new row.
        current(SET_AT.plusSeconds(5));

        assertThatThrownBy(() -> apply(Map.of("explain", valid(DEFINITION))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed while its folders were read");
        verifyNoInteractions(practices);
        verify(settings, never()).recordSync(anyLong(), anyString(), any(), anyString());
    }

    /** Sync B read commit C1, sync A read C2 and finished first: B must not roll A's changes back. */
    @Test
    void shouldRefuseWhenAnotherSyncFinishedWhileTheFoldersWereRead() {
        practice("gone", DEFINITION, PracticeEditedIn.REPOSITORY);
        ReflectionTestUtils.setField(current(SET_AT), "syncedAt", NOW.minusSeconds(1));

        assertThatThrownBy(() -> apply(Map.of("explain", valid(DEFINITION))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Another sync finished");
        verifyNoInteractions(practices);
        verify(settings, never()).recordSync(anyLong(), anyString(), any(), anyString());
    }

    private PracticeRepositorySyncDTO apply(Map<String, PracticeFiles.Read> reads) {
        var folders = new TreeMap<String, Map<String, String>>();
        reads.keySet().forEach(slug -> folders.put(slug, Map.of(PracticeFiles.PRACTICE, "---\n---\n")));
        return sync.apply(CONTEXT, tree(folders, Map.of()), new TreeMap<>(reads));
    }

    private static PracticeRepositoryFolders.Tree tree(
            Map<String, Map<String, String>> folders, Map<String, List<PracticeFileProblemDTO>> unreadable) {
        return new PracticeRepositoryFolders.Tree(
                COMMIT,
                new TreeMap<>(folders),
                new TreeMap<>(unreadable),
                List.of(),
                readAt(SET_AT),
                null,
                READ_STARTED_AT);
    }

    private static PracticeRepositoryFolders.ReadUnder readAt(Instant settingUpdatedAt) {
        return new PracticeRepositoryFolders.ReadUnder(42, DIRECTORY, 0, settingUpdatedAt);
    }

    private PracticeRepositorySetting current(Instant updatedAt) {
        var setting = new PracticeRepositorySetting();
        setting.setRepositoryItemId(42);
        setting.setDirectory(DIRECTORY);
        ReflectionTestUtils.setField(setting, "updatedAt", updatedAt);
        when(settings.findById(7L)).thenReturn(Optional.of(setting));
        return setting;
    }

    private Practice practice(String slug, PracticeDefinition definition, PracticeEditedIn editedIn) {
        var practice = detached(slug, definition, editedIn);
        existing.add(practice);
        return practice;
    }

    /** A practice that the workspace does not hold before the sync, as a write returns it. */
    private Practice detached(String slug, PracticeDefinition definition, PracticeEditedIn editedIn) {
        var practice = new Practice();
        practice.setId(ids.incrementAndGet());
        practice.setSlug(slug);
        practice.setKind(KIND);
        practice.setEditedIn(editedIn);
        practice.setCurrentVersion(TestEntities.practiceVersion(
                ids.incrementAndGet(), 1, MAPPER.valueToTree(definition), "v6:" + "a".repeat(64)));
        ReflectionTestUtils.setField(practice, "createdAt", SET_AT);
        return practice;
    }

    private static PracticeDefinition retired(PracticeDefinition definition) {
        return new PracticeDefinition(
                definition.name(),
                definition.reviewOn(),
                definition.appliesWhen(),
                definition.about(),
                definition.reads(),
                new ReviewedBy(ReviewedByKind.NOBODY, null, List.of()),
                definition.criteria(),
                definition.script(),
                definition.scan(),
                definition.lineNotes(),
                definition.guidance());
    }

    private static CatalogPracticeDocument document(PracticeDefinition definition) {
        return new CatalogPracticeDocument(KIND, null, definition);
    }

    private static PracticeFiles.Read valid(PracticeDefinition definition) {
        return new PracticeFiles.Read(document(definition), List.of());
    }

    private static PracticeSyncResultDTO result(String slug, PracticeSyncStatus status) {
        return new PracticeSyncResultDTO(slug, status, List.of(), false);
    }

    private static PracticeSyncResultDTO untried(String slug, PracticeSyncStatus status) {
        return new PracticeSyncResultDTO(slug, status, List.of(), true);
    }
}
