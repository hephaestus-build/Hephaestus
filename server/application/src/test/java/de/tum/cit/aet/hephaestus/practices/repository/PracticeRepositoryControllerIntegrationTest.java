package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Moving a practice between Hephaestus and the practice repository (ADR 0118): each direction reverses the other, and
 * unsetting the repository reverses every move. Reading the folder from a checkout is {@link
 * PracticeRepositoryFoldersTest}'s.
 */
class PracticeRepositoryControllerIntegrationTest extends BaseIntegrationTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    PracticeService practiceService;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeVersionRepository versionRows;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcTemplate jdbc;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    @Test
    void shouldMoveAPracticeToTheRepositoryAndBackToHephaestus() {
        practiceService.moveToRepository(
                context(), slug(), document("Each pull request says why the change is needed."), COMMIT);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.REPOSITORY);
        assertThat(jdbc.queryForObject(
                        "SELECT source_commit FROM practice_version WHERE id = ?", String.class, currentVersionId()))
                .isEqualTo(COMMIT);
        // The consoles and the MCP write tools cannot change a practice that the repository edits.
        http.delete()
                .uri(root() + "/practices/" + slug())
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);

        long versionBefore = currentVersionId();
        changeEditedIn("HEPHAESTUS").expectStatus().isNoContent().expectBody(Void.class);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.HEPHAESTUS);
        assertThat(currentVersionId()).isEqualTo(versionBefore);
    }

    @Test
    void shouldKeepTheVersionWhenTheFolderHoldsTheCurrentDefinition() {
        long versionBefore = currentVersionId();

        practiceService.moveToRepository(
                context(), slug(), new CatalogPracticeDocument(kind(), null, currentDefinition()), COMMIT);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.REPOSITORY);
        assertThat(currentVersionId()).isEqualTo(versionBefore);
    }

    @Test
    void shouldMoveEveryPracticeBackToHephaestusWhenTheRepositoryIsUnset() {
        jdbc.update(
                "INSERT INTO workspace_practice_repository (workspace_id, repository_item_id, updated_at)"
                        + " VALUES (?, 4242, now())",
                scenario.workspace().getId());
        practiceService.moveToRepository(
                context(), slug(), document("Each pull request says why the change is needed."), COMMIT);

        http.delete()
                .uri(root() + "/practice-repository")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.HEPHAESTUS);
        http.get()
                .uri(root() + "/practice-repository")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseToMoveAPracticeWhileTheWorkspaceHasNoPracticeRepository() {
        changeEditedIn("REPOSITORY").expectStatus().isEqualTo(409).expectBody(Void.class);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.HEPHAESTUS);
    }

    @Test
    void shouldRefuseARepositoryThatTheWorkspaceDoesNotMonitor() {
        http.put()
                .uri(root() + "/practice-repository")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("repositoryItemId", "999"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    private WebTestClient.ResponseSpec changeEditedIn(String editedIn) {
        return http.put()
                .uri(root() + "/practice-repository/practices/" + slug() + "/edited-in")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("editedIn", editedIn))
                .exchange();
    }

    /** The scenario's practice with other criteria, as the folder the practice repository holds. */
    private CatalogPracticeDocument document(String criteria) {
        var definition = currentDefinition();
        return new CatalogPracticeDocument(
                kind(),
                null,
                new PracticeDefinition(
                        definition.name(),
                        definition.reviewOn(),
                        definition.appliesWhen(),
                        definition.about(),
                        definition.reads(),
                        definition.reviewedBy(),
                        criteria,
                        definition.script(),
                        definition.scan(),
                        definition.lineNotes(),
                        definition.guidance()));
    }

    private WorkspaceContext context() {
        var workspace = scenario.workspace();
        return new WorkspaceContext(
                workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName(), null, null, Set.of());
    }

    private PracticeDefinition currentDefinition() {
        return versions.definitionOf(versionRows.findById(currentVersionId()).orElseThrow());
    }

    private long currentVersionId() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT current_version_id FROM practice WHERE id = ?",
                Long.class,
                scenario.practice().getId()));
    }

    private PracticeEditedIn editedIn() {
        return PracticeEditedIn.valueOf(Objects.requireNonNull(jdbc.queryForObject(
                "SELECT edited_in FROM practice WHERE id = ?",
                String.class,
                scenario.practice().getId())));
    }

    private ItemKind kind() {
        return scenario.practice().getKind();
    }

    private String slug() {
        return scenario.practice().getSlug();
    }

    private String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    private String token() {
        return ReviewIntegrationData.memberToken(scenario);
    }
}
