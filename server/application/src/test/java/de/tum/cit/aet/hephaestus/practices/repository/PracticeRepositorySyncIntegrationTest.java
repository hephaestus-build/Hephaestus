package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeFiles;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositoryDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositorySyncDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncResultDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncStatus;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.testconfig.AuditedRequestCheck;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sync now through the controller (ADR 0118), with a checkout of a fixture tree. The checkout is the one place where
 * GitHub and GitLab differ, and {@code ScmRepositoryCheckoutsIntegrationTest} covers both, so this test fakes it. It
 * asserts on the rows that the sync writes: the practices, their versions, the feedback mode, the configuration audit
 * and the stored report.
 */
class PracticeRepositorySyncIntegrationTest extends BaseIntegrationTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String LATER = "89abcdef0123456789abcdef0123456789abcdef";
    private static final long REPOSITORY = 4242;

    @MockitoBean
    MonitoredRepositoryQuery monitored;

    @MockitoBean
    RepositoryCheckouts checkouts;

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    PracticeService practiceService;

    @Autowired
    PracticeFiles files;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    PracticeRepositoryFolders folders;

    @Autowired
    PracticeRepositorySync syncs;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path tree;

    Scenario scenario;
    String head = COMMIT;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        jdbc.update(
                "INSERT INTO workspace_practice_repository (workspace_id, repository_item_id, updated_at)"
                        + " VALUES (?, ?, now())",
                workspaceId(),
                REPOSITORY);
        when(monitored.repositories(workspaceId()))
                .thenReturn(Map.of(REPOSITORY, new MonitoredRepositoryRef(REPOSITORY, 1, "acme/practices", "main")));
        when(checkouts.checkout(eq(workspaceId()), eq("acme/practices"), eq("main"), isNull()))
                .thenAnswer(call -> checkout());
    }

    @Test
    void shouldApplyEachFolderAndChangeNothingWhenTheSameCommitIsSyncedAgain() throws IOException {
        folder("explain-why", "Explain why the change is needed.");
        // The scenario's practice is edited in Hephaestus, so its folder must not change it.
        folder(scenario.practice().getSlug(), "Something else entirely.");
        var draft = practiceService.createDraft(context(), "draft-practice", document("Say what the change does."));
        admissions.pending(
                new Scenario(scenario.workspace(), scenario.account(), draft, scenario.item()),
                Instant.now(),
                null,
                "item-version-" + UUID.randomUUID(),
                ReviewOrigin.TRIAL);
        folder("draft-practice", "Say what the change does.");
        write("broken/practice.md", "no front matter\n");
        long scenarioVersion = currentVersionId(scenario.practice().getSlug());

        var first = sync();
        AuditedRequestCheck.acceptBulk(PracticeRepositoryController.class, "sync", 2);

        assertThat(statuses(first))
                .containsExactly(
                        Map.entry("broken", PracticeSyncStatus.INVALID),
                        Map.entry("draft-practice", PracticeSyncStatus.TAKEN_OVER),
                        Map.entry("explain-why", PracticeSyncStatus.CREATED),
                        Map.entry(scenario.practice().getSlug(), PracticeSyncStatus.EDITED_IN_HEPHAESTUS));
        assertThat(first.practices().getFirst().problems()).isNotEmpty();
        // The draft's trial ran the review digest that went live; nobody tried the new practice.
        assertThat(first.practices())
                .filteredOn(PracticeSyncResultDTO::untried)
                .extracting(PracticeSyncResultDTO::slug)
                .containsExactly("explain-why");
        for (String slug : new String[] {"explain-why", "draft-practice"}) {
            assertThat(practiceRow(slug))
                    .containsEntry("state", "ACTIVE")
                    .containsEntry("edited_in", "REPOSITORY")
                    .containsEntry("feedback_mode", "OFF")
                    .containsEntry("source_commit", COMMIT);
            assertThat(auditRows(slug)).isPositive();
        }
        assertThat(currentVersionId(scenario.practice().getSlug())).isEqualTo(scenarioVersion);
        assertThat(jdbc.queryForObject(
                        "SELECT sync_commit FROM workspace_practice_repository WHERE workspace_id = ?",
                        String.class,
                        workspaceId()))
                .isEqualTo(COMMIT);
        assertThat(jdbc.queryForObject(
                        "SELECT sync_report -> 'practices' -> 2 ->> 'status' FROM workspace_practice_repository"
                                + " WHERE workspace_id = ?",
                        String.class,
                        workspaceId()))
                .isEqualTo("CREATED");

        long created = currentVersionId("explain-why");
        int audits = auditRows("explain-why");
        var second = sync();
        AuditedRequestCheck.acceptUnchanged(PracticeRepositoryController.class, "sync");

        assertThat(statuses(second))
                .containsEntry("explain-why", PracticeSyncStatus.UNCHANGED)
                .containsEntry("draft-practice", PracticeSyncStatus.UNCHANGED);
        assertThat(currentVersionId("explain-why")).isEqualTo(created);
        assertThat(auditRows("explain-why")).isEqualTo(audits);
        assertThat(lastSync()).isEqualTo(second);
        assertThat(second.syncedAt()).isAfterOrEqualTo(first.syncedAt());
    }

    /** The folder's removal keeps the practice and its history; its return adds a version from the file again. */
    @Test
    void shouldRetireAPracticeWhoseFolderIsGoneAndRestoreItWhenTheFolderReturns() throws IOException {
        folder("explain-why", "Explain why the change is needed.");
        sync();
        AuditedRequestCheck.acceptBulk(PracticeRepositoryController.class, "sync", 1);
        FileSystemUtils.deleteRecursively(base().resolve("explain-why"));
        write("README.md", "The practices of this repository.");
        head = LATER;

        var removed = sync();

        assertThat(statuses(removed)).containsExactly(Map.entry("explain-why", PracticeSyncStatus.RETIRED));
        assertThat(practiceRow("explain-why"))
                .containsEntry("reviewed_by", "NOBODY")
                .containsEntry("source_commit", LATER);
        long retired = currentVersionId("explain-why");

        var again = sync();
        AuditedRequestCheck.acceptUnchanged(PracticeRepositoryController.class, "sync");

        assertThat(statuses(again)).containsExactly(Map.entry("explain-why", PracticeSyncStatus.RETIRED));
        assertThat(currentVersionId("explain-why")).isEqualTo(retired);
        assertThat(versionCount("explain-why")).isEqualTo(2);

        folder("explain-why", "Explain why the change is needed.");
        var restored = sync();

        assertThat(statuses(restored)).containsExactly(Map.entry("explain-why", PracticeSyncStatus.UPDATED));
        assertThat(practiceRow("explain-why")).containsEntry("reviewed_by", "HEPHAESTUS");
        assertThat(versionCount("explain-why")).isEqualTo(3);
    }

    /**
     * A version on an older source contract fails the checks of a save, and so would its Nobody version. Writing it
     * would roll back this sync and every later one, so the practice stays and the other folders apply.
     */
    @Test
    void shouldKeepAPracticeWhoseVersionCannotBeRetiredAndApplyTheOtherFolders() throws IOException {
        var slug = scenario.practice().getSlug();
        long older = Objects.requireNonNull(
                jdbc.queryForObject("""
                INSERT INTO practice_version (workspace_id, practice_id, number, definition)
                SELECT workspace_id, practice_id, 2, jsonb_set(definition, '{reads,contract}', '"2.0.0"')
                FROM practice_version WHERE id = (SELECT current_version_id FROM practice WHERE id = ?)
                RETURNING id
                """, Long.class, scenario.practice().getId()));
        jdbc.update(
                "UPDATE practice SET current_version_id = ?, edited_in = 'REPOSITORY' WHERE id = ?",
                older,
                scenario.practice().getId());
        folder("explain-why", "Explain why the change is needed.");

        var report = sync();
        AuditedRequestCheck.acceptBulk(PracticeRepositoryController.class, "sync", 1);

        assertThat(statuses(report))
                .containsExactly(
                        Map.entry("explain-why", PracticeSyncStatus.CREATED),
                        Map.entry(slug, PracticeSyncStatus.RETIRE_REFUSED));
        assertThat(report.practices().get(1).problems()).isNotEmpty();
        assertThat(currentVersionId(slug)).isEqualTo(older);
        assertThat(versionCount(slug)).isEqualTo(2);
        assertThat(practiceRow("explain-why")).containsEntry("source_commit", COMMIT);
    }

    /** A read that started before another sync finished may hold an older commit, so it must not apply after it. */
    @Test
    void shouldRefuseAnOlderReadWhenAnotherSyncFinishedMeanwhile() throws IOException {
        folder("explain-why", "Explain why the change is needed.");
        var older = folders.readAll(workspaceId());
        sync();
        AuditedRequestCheck.acceptBulk(PracticeRepositoryController.class, "sync", 1);
        var reads = new TreeMap<String, PracticeFiles.Read>();
        older.folders().forEach((name, folder) -> reads.put(name, files.read(name, folder)));

        assertThatThrownBy(() -> syncs.apply(context(), older, reads))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode().value()).isEqualTo(409));
        assertThat(versionCount("explain-why")).isEqualTo(1);
    }

    @Test
    void shouldRefuseASyncWhileTheWorkspaceHasNoPracticeRepository() {
        jdbc.update("DELETE FROM workspace_practice_repository WHERE workspace_id = ?", workspaceId());

        http.post()
                .uri(root() + "/practice-repository/sync")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }

    private PracticeRepositorySyncDTO sync() {
        return Objects.requireNonNull(http.post()
                .uri(root() + "/practice-repository/sync")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PracticeRepositorySyncDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private @Nullable PracticeRepositorySyncDTO lastSync() {
        return Objects.requireNonNull(http.get()
                        .uri(root() + "/practice-repository")
                        .headers(h -> h.setBearerAuth(token()))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(PracticeRepositoryDTO.class)
                        .returnResult()
                        .getResponseBody())
                .lastSync();
    }

    private static Map<String, PracticeSyncStatus> statuses(PracticeRepositorySyncDTO report) {
        var statuses = new LinkedHashMap<String, PracticeSyncStatus>();
        for (PracticeSyncResultDTO result : report.practices()) statuses.put(result.slug(), result.status());
        return statuses;
    }

    private Map<String, @Nullable Object> practiceRow(String slug) {
        return jdbc.queryForMap(
                "SELECT p.state, p.edited_in, p.feedback_mode, v.source_commit,"
                        + " v.definition -> 'reviewedBy' ->> 'by' AS reviewed_by"
                        + " FROM practice p JOIN practice_version v ON v.id = p.current_version_id"
                        + " WHERE p.workspace_id = ? AND p.slug = ?",
                workspaceId(),
                slug);
    }

    private int auditRows(String slug) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM config_audit_event e JOIN practice p ON e.entity_id = CAST(p.id AS text)"
                        + " WHERE e.workspace_id = ? AND e.entity_type = 'PRACTICE_DEFINITION' AND p.slug = ?",
                Integer.class,
                workspaceId(),
                slug));
    }

    private int versionCount(String slug) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM practice_version v JOIN practice p ON p.id = v.practice_id"
                        + " WHERE p.workspace_id = ? AND p.slug = ?",
                Integer.class,
                workspaceId(),
                slug));
    }

    private long currentVersionId(String slug) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT current_version_id FROM practice WHERE workspace_id = ? AND slug = ?",
                Long.class,
                workspaceId(),
                slug));
    }

    private void folder(String slug, String criteria) throws IOException {
        for (var file : files.write(document(criteria)).entrySet()) write(slug + "/" + file.getKey(), file.getValue());
    }

    private CatalogPracticeDocument document(String criteria) {
        return new CatalogPracticeDocument(
                scenario.practice().getKind(),
                null,
                DefinitionTestFixtures.withCriteria(DefinitionTestFixtures.definition(), criteria));
    }

    private void write(String path, String content) throws IOException {
        Path file = base().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path base() {
        return tree.resolve(PracticeRepositorySetting.DEFAULT_DIRECTORY);
    }

    private RepositoryCheckouts.Checkout checkout() {
        String commit = head;
        return new RepositoryCheckouts.Checkout() {
            @Override
            public String repository() {
                return "acme/practices";
            }

            @Override
            public String head() {
                return commit;
            }

            @Override
            public @Nullable String base() {
                return null;
            }

            @Override
            public Path directory() {
                return tree;
            }

            @Override
            public void close() {}
        };
    }

    private WorkspaceContext context() {
        var workspace = scenario.workspace();
        return new WorkspaceContext(
                workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName(), null, null, Set.of());
    }

    private long workspaceId() {
        return scenario.workspace().getId();
    }

    private String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    private String token() {
        return ReviewIntegrationData.memberToken(scenario);
    }
}
