package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogCustomizationService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.MovePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetFeedbackModeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeCatalogControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    WebTestClient client;

    @Autowired
    CatalogOriginPresenter presenter;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    CatalogService catalog;

    @Autowired
    CatalogCustomizationService customization;

    @Test
    @WithAdminUser
    void shouldPersistAnEventFreeScanWithItsOwnReadRequirements() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-scan", "Scan", "wire-scan-org", AccountType.ORG, owner);
        var reads = new PracticeReads(
                "3.0.0", List.of(new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.COMPLETE)));
        var definition = new PracticeDefinition(
                "Keep repository code clear",
                new ReviewOn(List.of(), Map.of()),
                null,
                null,
                reads,
                new ReviewedBy(ReviewedByKind.HEPHAESTUS, null, List.of()),
                "Each selected file clearly expresses its purpose.",
                null,
                new ScanDefinition(
                        new ItemKind("scm.file"),
                        List.of("src/**/*.ts"),
                        List.of("dist/**"),
                        List.of("src/**/*"),
                        reads),
                false,
                new PracticeGuidance(null, null, null, null, null));
        client.post()
                .uri("/workspaces/{workspace}/practices", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreatePracticeRequestDTO(
                        "repository-scan", "scm.repository", DefinitionTestFixtures.authoring(definition), null, null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.currentVersion.definition.reviewOn.events")
                .isEmpty()
                .jsonPath("$.currentVersion.definition.about")
                .isEmpty()
                .jsonPath("$.currentVersion.definition.scan.reads.sources[0].need")
                .isEqualTo("COMPLETE");
        client.get()
                .uri("/workspaces/{workspace}/practices/repository-scan", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.kind")
                .isEqualTo("scm.repository")
                .jsonPath("$.currentVersion.definition.scan.itemKind")
                .isEqualTo("scm.file")
                .jsonPath("$.currentVersion.definition.scan.reads.contract")
                .isEqualTo("3.0.0")
                .jsonPath("$.currentVersion.definition.scan.reads.sources[0].source")
                .isEqualTo("scm.repository.tree")
                .jsonPath("$.currentVersion.definition.scan.context[0]")
                .isEqualTo("src/**/*");
    }

    @Test
    @WithAdminUser
    void shouldPersistEventFreeGuidanceWithoutAutomatedReadRequirements() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-no-review", "Guidance", "wire-no-review-org", AccountType.ORG, owner);
        var definition = new PracticeDefinition(
                "Discuss team decisions",
                new ReviewOn(List.of(), Map.of()),
                null,
                null,
                new PracticeReads("3.0.0", List.of()),
                new ReviewedBy(ReviewedByKind.NOBODY, null, List.of()),
                "Discuss the decisions that affect the team.",
                null,
                null,
                false,
                new PracticeGuidance("Keeps the team informed.", null, null, null, null));
        client.post()
                .uri("/workspaces/{workspace}/practices", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreatePracticeRequestDTO(
                        "team-decisions", "scm.pull_request", DefinitionTestFixtures.authoring(definition), null, null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.currentVersion.definition.reviewedBy.by")
                .isEqualTo("NOBODY")
                .jsonPath("$.currentVersion.definition.reads.sources")
                .isEmpty()
                .jsonPath("$.currentVersion.definition.guidance.whyItMatters")
                .isEqualTo("Keeps the team informed.");
        client.get()
                .uri("/workspaces/{workspace}/practices/team-decisions", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.currentVersion.definition.reviewOn.events")
                .isEmpty()
                .jsonPath("$.currentVersion.definition.reviewedBy.by")
                .isEqualTo("NOBODY")
                .jsonPath("$.currentVersion.definition.script")
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldExposeStringIdsAndTheCurrentDefinition() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-practice", "Wire", "wire-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "wire-definition",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        client.get()
                .uri("/workspaces/{workspace}/practices/{slug}", workspace.getWorkspaceSlug(), practice.getSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(practice.getId().toString())
                .jsonPath("$.currentVersion.definition.criteria")
                .isEqualTo(DefinitionTestFixtures.definition().criteria());
    }

    @Test
    @WithAdminUser
    void shouldReplaceFeedbackModeAndRestoreInheritance() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-mode", "Modes", "wire-mode-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "mode-definition",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        String uri =
                "/workspaces/" + workspace.getWorkspaceSlug() + "/practices/" + practice.getSlug() + "/feedback-mode";
        client.patch()
                .uri(uri)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetFeedbackModeRequestDTO(FeedbackMode.OFF))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedbackMode.override")
                .isEqualTo("OFF")
                .jsonPath("$.feedbackMode.effective")
                .isEqualTo("OFF");
        client.patch()
                .uri(uri)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetFeedbackModeRequestDTO(null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedbackMode.override")
                .isEmpty()
                .jsonPath("$.feedbackMode.effective")
                .isEqualTo("APPROVE_FIRST");
    }

    @Test
    @WithMentorUser
    void shouldRefuseAdminDefinitionReadsForANonmember() {
        persistUser("mentor");
        var owner = persistUser("wire-owner");
        var workspace = createWorkspace("wire-private", "Private", "wire-private-org", AccountType.ORG, owner);
        client.get()
                .uri("/workspaces/{workspace}/practices", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldRejectAStaleDefinitionWithoutAppendingAVersion() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-stale", "Stale", "stale-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "stale-definition",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        var firstId = Objects.requireNonNull(practice.getCurrentVersion().getId());
        var second = versions.append(
                practice, DefinitionTestFixtures.rename(DefinitionTestFixtures.definition(), "New name"), null);
        client.patch()
                .uri("/workspaces/{workspace}/practices/{slug}", workspace.getWorkspaceSlug(), practice.getSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdatePracticeRequestDTO(
                        DefinitionTestFixtures.authoring(DefinitionTestFixtures.definition()), firstId.toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.currentVersionId")
                .isEqualTo(Objects.requireNonNull(second.getId()).toString());
        assertThat(presenter.history(practice)).hasSize(2);
    }

    @Test
    @WithAdminUser
    void shouldReturnNotFoundForAForeignWorkspacePracticeAndGroup() {
        var owner = persistUser("admin");
        var local = createWorkspace("wire-local", "Local", "local-org", AccountType.ORG, owner);
        var foreign = createWorkspace("wire-foreign", "Foreign", "foreign-org", AccountType.ORG, owner);
        versions.create(new PracticeVersionService.NewPractice(
                foreign,
                "foreign-practice",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        client.get()
                .uri("/workspaces/{workspace}/practices/foreign-practice", local.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/{workspace}/practice-groups/unknown-foreign-group", local.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldRejectRetiredModeFieldsAndMissingExplicitOverrides() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-invalid", "Invalid", "invalid-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "invalid-definition",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        String uri =
                "/workspaces/" + workspace.getWorkspaceSlug() + "/practices/" + practice.getSlug() + "/feedback-mode";
        for (String body : List.of("{}", "{\"feedbackMode\":null,\"autonomy\":\"AUTOMATIC\"}")) {
            client.patch()
                    .uri(uri)
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }
    }

    @Test
    @WithAdminUser
    void shouldKeepGuidanceValidatorsScopedToTheirContent() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-guidance", "Guidance", "guidance-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "stored-guidance",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        String uri = "/workspaces/" + workspace.getWorkspaceSlug() + "/practices/" + practice.getSlug() + "/guidance";
        String tag = Objects.requireNonNull(client.get()
                .uri(uri)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getETag());
        client.get()
                .uri(uri)
                .headers(TestAuthUtils.withCurrentUser())
                .header("If-None-Match", "W/" + tag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectBody(Void.class);
        versions.append(
                practice, DefinitionTestFixtures.rename(DefinitionTestFixtures.definition(), "New display name"), null);
        client.get()
                .uri(uri)
                .headers(TestAuthUtils.withCurrentUser())
                .header("If-None-Match", tag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldMoveAPracticeWithoutChangingItsPinnedVersion() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-move", "Move", "move-org", AccountType.ORG, owner);
        var created = client.post()
                .uri("/workspaces/{workspace}/practices", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreatePracticeRequestDTO(
                        "move-practice",
                        "scm.pull_request",
                        DefinitionTestFixtures.authoring(DefinitionTestFixtures.definition()),
                        null,
                        null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(PracticeDTO.class)
                .returnResult()
                .getResponseBody();
        var version = Objects.requireNonNull(created).currentVersion().id();
        client.post()
                .uri("/workspaces/{workspace}/practice-groups", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreatePracticeGroupRequestDTO(
                        "move-group", "Moved practices", null, null, null, null, true))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(Void.class);
        client.patch()
                .uri("/workspaces/{workspace}/practices/move-practice/group", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MovePracticeRequestDTO("move-group", 0))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].slug")
                .isEqualTo("move-practice")
                .jsonPath("$[0].groupSlug")
                .isEqualTo("move-group")
                .jsonPath("$[0].position")
                .isEqualTo(0)
                .jsonPath("$[0].currentVersion.id")
                .isEqualTo(version);
    }
}
