package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDraftDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeDraftControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    PracticeFiles files;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeRepository practices;

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

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    String token() {
        return ReviewIntegrationData.memberToken(scenario);
    }

    /** The folder of the scenario's practice, with the criteria changed so it is a definition worth trying. */
    Map<String, String> folder(String criteria) {
        var definition = versions.definitionOf(versionRows
                .findById(Objects.requireNonNull(
                        scenario.practice().getCurrentVersion().getId()))
                .orElseThrow());
        var changed = new PracticeDefinition(
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
                definition.guidance());
        return files.write(new CatalogPracticeDocument(new ItemKind("scm.pull_request"), null, changed));
    }

    PracticeDraftDTO save(String slug, Map<String, String> folder) {
        return Objects.requireNonNull(http.put()
                .uri(root() + "/practice-drafts/" + slug)
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("files", folder))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PracticeDraftDTO.class)
                .returnResult()
                .getResponseBody());
    }

    List<PracticeDraftDTO> listDrafts() {
        return Objects.requireNonNull(http.get()
                .uri(root() + "/practice-drafts")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<PracticeDraftDTO>>() {})
                .returnResult()
                .getResponseBody());
    }

    @Test
    void shouldKeepANewDraftOutOfSelectionAndTheCatalogUntilItIsActivated() {
        var draft = save("explain-the-change", folder("Explain why the change is needed."));

        assertThat(draft.state()).isEqualTo(PracticeState.DRAFT);
        assertThat(draft.current()).isTrue();
        assertThat(listDrafts()).extracting(PracticeDraftDTO::slug).containsExactly("explain-the-change");
        // The framework filter hides the draft from every entity query that selection and members use.
        assertThat(practices.findByWorkspaceIdAndKind(scenario.workspace().getId(), new ItemKind("scm.pull_request")))
                .extracting(practice -> practice.getSlug())
                .doesNotContain("explain-the-change");
        http.get()
                .uri(root() + "/practices/explain-the-change")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        http.patch()
                .uri(root() + "/practice-drafts/explain-the-change/state")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("state", "ACTIVE"))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        var active = practices
                .findByWorkspaceIdAndSlug(scenario.workspace().getId(), "explain-the-change")
                .orElseThrow();
        assertThat(active.getState()).isEqualTo(PracticeState.ACTIVE);
        assertThat(active.getFeedbackMode()).isEqualTo(FeedbackMode.OFF);
        assertThat(listDrafts()).isEmpty();
    }

    @Test
    void shouldTryAVersionBesideAPracticeInUseWithoutChangingItsCurrentVersionOrHistory() {
        var slug = scenario.practice().getSlug();
        var currentId =
                Objects.requireNonNull(scenario.practice().getCurrentVersion().getId());

        var tried = save(slug, folder("A stricter criterion to try first."));
        var again = save(slug, folder("A stricter criterion to try first."));

        assertThat(tried.state()).isEqualTo(PracticeState.ACTIVE);
        assertThat(tried.current()).isFalse();
        assertThat(again.versionId()).isEqualTo(tried.versionId());
        assertThat(practices
                        .findByWorkspaceIdAndSlug(scenario.workspace().getId(), slug)
                        .orElseThrow()
                        .getCurrentVersion()
                        .getId())
                .isEqualTo(currentId);
        var history = http.get()
                .uri(root() + "/practices/" + slug + "/versions")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<PracticeVersionDTO>>() {})
                .returnResult()
                .getResponseBody();
        assertThat(history).extracting(PracticeVersionDTO::id).doesNotContain(tried.versionId());
    }

    @Test
    void shouldDeleteADraftWithItsTrials() {
        var draft = save("explain-the-change", folder("Explain why the change is needed."));
        var practice = draft(draft.slug());
        var trial = admissions.pending(
                new Scenario(scenario.workspace(), scenario.account(), practice, scenario.item()),
                Instant.now(),
                null,
                "item-version-" + UUID.randomUUID(),
                ReviewOrigin.TRIAL);

        http.delete()
                .uri(root() + "/practice-drafts/explain-the-change")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review WHERE id = ?",
                        Long.class,
                        trial.review().getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice WHERE id = ?", Long.class, practice.getId()))
                .isZero();
    }

    @Test
    void shouldListATrialOfADraftAmongTheReviews() {
        var draft = save("explain-the-change", folder("Explain why the change is needed."));
        var practice = draft(draft.slug());
        admissions.pending(
                new Scenario(scenario.workspace(), scenario.account(), practice, scenario.item()),
                Instant.now(),
                null,
                "item-version-" + UUID.randomUUID(),
                ReviewOrigin.TRIAL);

        http.get()
                .uri(root() + "/practices/reviews")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldKeepTheVersionInEffectWhenAVersionIsTriedBesideIt() {
        var slug = scenario.practice().getSlug();
        var currentId =
                Objects.requireNonNull(scenario.practice().getCurrentVersion().getId());
        save(slug, folder("A stricter criterion to try first."));

        assertThat(versionRows.findAt(
                        scenario.workspace().getId(),
                        List.of(scenario.practice().getId()),
                        Instant.now()))
                .extracting(version -> version.getId())
                .containsExactly(currentId);
    }

    @Test
    void shouldRefuseToDeleteAPracticeInUseAsADraft() {
        http.delete()
                .uri(root() + "/practice-drafts/" + scenario.practice().getSlug())
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseAnInvalidFolderWithItsProblemsAndSaveNothing() {
        http.put()
                .uri(root() + "/practice-drafts/broken")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("files", Map.of("practice.md", "no front matter")))
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.problems[0].file")
                .isEqualTo("practice.md");
        assertThat(listDrafts()).isEmpty();
    }

    /** Reads a draft practice by its id, which no state filter hides. */
    private Practice draft(String slug) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM practice WHERE workspace_id = ? AND slug = ?",
                Long.class,
                scenario.workspace().getId(),
                slug);
        return practices.findById(Objects.requireNonNull(id)).orElseThrow();
    }
}
