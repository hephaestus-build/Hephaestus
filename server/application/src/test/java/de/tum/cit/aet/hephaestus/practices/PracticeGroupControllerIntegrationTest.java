package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.dto.BindPracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeGroupControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    WebTestClient client;

    @Autowired
    PracticeGroupRepository groups;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    CatalogService catalog;

    @Autowired
    PracticeGroupService groupService;

    @Autowired
    PracticeCatalogInstallationManager installer;

    @Test
    @WithAdminUser
    void shouldMoveAGroupWithoutAppendingADefinitionVersion() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-group", "Groups", "wire-group-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(),
                "grouped-definition",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        var group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug("destination");
        group.setName("Destination");
        groups.saveAndFlush(group);
        String version =
                Objects.requireNonNull(practice.getCurrentVersion().getId()).toString();
        client.put()
                .uri("/workspaces/{workspace}/practices/{slug}/group", workspace.getWorkspaceSlug(), practice.getSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BindPracticeGroupRequestDTO(group.getSlug()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.groupSlug")
                .isEqualTo(group.getSlug())
                .jsonPath("$.currentVersion.id")
                .isEqualTo(version);
    }

    @Test
    @WithAdminUser
    void shouldStoreDeveloperVisibilityInTheInitialGroupCreation() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-hidden-group", "Groups", "hidden-group-org", AccountType.ORG, owner);
        client.post()
                .uri("/workspaces/{workspace}/practice-groups", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreatePracticeGroupRequestDTO(
                        "hidden-group", "Hidden group", null, null, null, null, false))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.shownToDevelopers")
                .isEqualTo(false);
        var stored = groups.findByWorkspaceIdAndSlug(workspace.getId(), "hidden-group")
                .orElseThrow();
        assertThat(stored.isShownToDevelopers()).isFalse();
        client.get()
                .uri("/workspaces/{workspace}/practice-groups/{slug}", workspace.getWorkspaceSlug(), stored.getSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists("ETag")
                .expectBody()
                .jsonPath("$.shownToDevelopers")
                .isEqualTo(false);
    }

    @Test
    @WithAdminUser
    void shouldResetCatalogGroupMetadataAndPreserveIndependentControls() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("wire-group-reset", "Reset", "group-reset-org", AccountType.ORG, owner);
        installer.install(workspace.getId());
        var source = catalog.effectiveCatalog().installableGroups().getFirst();
        var ctx = WorkspaceContext.fromWorkspace(workspace, Set.of(), null);
        groupService.updateGroup(
                ctx, source.slug(), new GroupAttributes("Edited group", "Local description", 7, null, null), false);
        groupService.setFeedbackMode(ctx, source.slug(), FeedbackMode.OFF);
        String uri = "/workspaces/" + workspace.getWorkspaceSlug() + "/practice-groups/" + source.slug();
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
        client.delete()
                .uri(uri + "/catalog-customization")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody(Void.class);
        client.delete()
                .uri(uri + "/catalog-customization")
                .headers(TestAuthUtils.withCurrentUser())
                .header("If-Match", tag)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo(source.effective().name())
                .jsonPath("$.position")
                .isEqualTo(7)
                .jsonPath("$.shownToDevelopers")
                .isEqualTo(false)
                .jsonPath("$.feedbackMode.override")
                .isEqualTo("OFF");
        var stored = groups.findByWorkspaceIdAndSlug(workspace.getId(), source.slug())
                .orElseThrow();
        assertThat(stored.getName()).isEqualTo(source.effective().name());
        assertThat(stored.getDescription()).isEqualTo(source.effective().description());
        assertThat(stored.getIcon()).isEqualTo(source.effective().icon());
        assertThat(stored.getColor()).isEqualTo(source.effective().color());
        assertThat(stored.getCatalogBase()).isEqualTo(source.effective());
        assertThat(stored.getPosition()).isEqualTo(7);
        assertThat(stored.isShownToDevelopers()).isFalse();
        assertThat(stored.getFeedbackMode()).isEqualTo(FeedbackMode.OFF);
    }
}
