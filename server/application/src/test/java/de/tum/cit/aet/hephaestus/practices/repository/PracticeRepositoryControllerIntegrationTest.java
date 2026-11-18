package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeFiles;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The practice repository and moving a practice both ways (ADR 0118): each direction reverses the other. */
class PracticeRepositoryControllerIntegrationTest extends BaseIntegrationTest {
    private static final long REPOSITORY_ITEM = 4242;
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @MockitoBean
    MonitoredRepositoryQuery monitored;

    @MockitoBean
    RepositoryCheckouts checkouts;

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    PracticeFiles files;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeVersionRepository versionRows;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path tree;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        when(monitored.repositories(anyLong()))
                .thenReturn(Map.of(
                        REPOSITORY_ITEM, new MonitoredRepositoryRef(REPOSITORY_ITEM, 1, "acme/practices", "main")));
        when(checkouts.checkout(anyLong(), eq("acme/practices"), eq("main"), any()))
                .thenAnswer(call -> checkout());
    }

    @Test
    void shouldMoveAPracticeToTheRepositoryAndBackToHephaestus() throws IOException {
        setRepository();
        commit(slug(), folder("Each pull request says why the change is needed."));

        changeEditedIn("REPOSITORY").expectStatus().isNoContent().expectBody(Void.class);

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
    void shouldKeepTheVersionWhenTheFolderHoldsTheCurrentDefinition() throws IOException {
        setRepository();
        long versionBefore = currentVersionId();
        commit(slug(), files.write(new CatalogPracticeDocument(kind(), null, currentDefinition())));

        changeEditedIn("REPOSITORY").expectStatus().isNoContent().expectBody(Void.class);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.REPOSITORY);
        assertThat(currentVersionId()).isEqualTo(versionBefore);
    }

    /** A missing folder would make the first sync treat the practice as removed, so the move is refused. */
    @Test
    void shouldRefuseTheMoveWhenTheDefaultBranchHasNoFolderForThePractice() {
        setRepository();

        changeEditedIn("REPOSITORY").expectStatus().isEqualTo(409).expectBody(Void.class);

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.HEPHAESTUS);
    }

    @Test
    void shouldRefuseTheMoveWithTheProblemsOfAnInvalidFolder() throws IOException {
        setRepository();
        commit(slug(), Map.of("practice.md", "no front matter"));

        changeEditedIn("REPOSITORY")
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.problems[0].file")
                .isEqualTo("practice.md");

        assertThat(editedIn()).isEqualTo(PracticeEditedIn.HEPHAESTUS);
    }

    @Test
    void shouldMoveEveryPracticeBackToHephaestusWhenTheRepositoryIsUnset() throws IOException {
        setRepository();
        commit(slug(), folder("Each pull request says why the change is needed."));
        changeEditedIn("REPOSITORY").expectStatus().isNoContent().expectBody(Void.class);

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

    private void setRepository() {
        http.put()
                .uri(root() + "/practice-repository")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("repositoryItemId", String.valueOf(REPOSITORY_ITEM)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.nameWithOwner")
                .isEqualTo("acme/practices")
                .jsonPath("$.directory")
                .isEqualTo(PracticeRepositorySetting.DEFAULT_DIRECTORY);
    }

    private WebTestClient.ResponseSpec changeEditedIn(String editedIn) {
        return http.put()
                .uri(root() + "/practice-repository/practices/" + slug() + "/edited-in")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("editedIn", editedIn))
                .exchange();
    }

    private void commit(String slug, Map<String, String> folder) throws IOException {
        Path directory =
                tree.resolve(PracticeRepositorySetting.DEFAULT_DIRECTORY).resolve(slug);
        for (var file : folder.entrySet()) {
            Path path = directory.resolve(file.getKey());
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.writeString(path, file.getValue());
        }
    }

    /** The scenario's practice, with other criteria, as the folder the practice repository holds. */
    private Map<String, String> folder(String criteria) {
        var definition = currentDefinition();
        return files.write(new CatalogPracticeDocument(
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
                        definition.guidance())));
    }

    private RepositoryCheckouts.Checkout checkout() {
        return new RepositoryCheckouts.Checkout() {
            @Override
            public String repository() {
                return "acme/practices";
            }

            @Override
            public String head() {
                return COMMIT;
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
