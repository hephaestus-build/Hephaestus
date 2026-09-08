package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceLlmConnectionControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InstanceLlmSettingsRepository instanceLlmSettingsRepository;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private WorkspaceLlmConnectionDTO createConnection(Workspace workspace, String slug) {
        var request = new CreateWorkspaceLlmConnectionRequestDTO(
                slug,
                "My Provider",
                LlmCatalogTestFixtures.PUBLIC_BASE_URL,
                "openai-completions",
                LlmAuthMode.BEARER,
                "sk-workspace-secret-9999",
                true);
        return Objects.requireNonNull(webTestClient
                .post()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceLlmConnectionDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    @WithAdminUser
    void workspaceAdminCanCreateGetListUpdateAndDeleteAConnection() {
        Workspace workspace = setupWorkspace("byo-crud-ws");
        WorkspaceLlmConnectionDTO created = createConnection(workspace, "my-openai");

        assertThat(created).isNotNull();
        assertThat(created.hasApiKey()).isTrue();
        assertThat(created.apiKeyLast4()).isEqualTo("9999");

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("my-openai");

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1);

        var updateRequest = new UpdateWorkspaceLlmConnectionRequestDTO("Renamed", null, null, null);
        webTestClient
                .patch()
                .uri("/workspaces/{slug}/llm/connections/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Renamed");

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/llm/connections/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithAdminUser
    void apiKeyIsNeverExposedInAnyResponse() {
        Workspace workspace = setupWorkspace("byo-redaction-ws");

        String body = webTestClient
                .post()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkspaceLlmConnectionRequestDTO(
                        "redact-me",
                        "Redact Me",
                        LlmCatalogTestFixtures.PUBLIC_BASE_URL,
                        "openai-completions",
                        LlmAuthMode.BEARER,
                        "sk-super-secret-workspace-value",
                        true))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("sk-super-secret-workspace-value");
        assertThat(body).doesNotContain("\"apiKey\"");
        assertThat(body).contains("hasApiKey");
    }

    @Test
    @WithMentorUser
    void aWorkspaceMemberCannotReachTheEndpoint() {
        Workspace workspace = setupWorkspace("byo-member-ws");
        // Login must match @WithMentorUser's default "mentor" principal so the workspace membership
        // resolver finds this exact row.
        User mentor = persistUser("mentor");
        ensureWorkspaceMembership(workspace, mentor, WorkspaceRole.MEMBER);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @WithAdminUser
    void aConnectionIdFromAnotherWorkspaceIsNotFoundThroughYourOwnSlug() {
        Workspace workspaceA = setupWorkspace("byo-tenancy-a");
        WorkspaceLlmConnectionDTO connectionInA = createConnection(workspaceA, "conn-in-a");

        User ownerB = persistUser("byo-tenancy-owner-b");
        Workspace workspaceB =
                createWorkspace("byo-tenancy-b", "Tenancy B", "byo-tenancy-org-b", AccountType.ORG, ownerB);
        ensureAdminMembership(workspaceB);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections/{id}", workspaceB.getWorkspaceSlug(), connectionInA.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithMentorUser
    void aWorkspaceAdminIsForbiddenInAnotherWorkspaceButStillAdmittedInTheirOwn() {
        // A genuine (non-superadmin) workspace ADMIN — app_admin JWT authority is a separate,
        // instance-wide elevation (see WithAdminUser's javadoc); this proves the ordinary
        // membership-scoped path independent of that bypass.
        User admin = persistUser("mentor");
        Workspace workspace =
                createWorkspace("byo-realadmin-ws", "Real Admin", "byo-realadmin-org", AccountType.ORG, admin);
        ensureWorkspaceMembership(workspace, admin, WorkspaceRole.ADMIN);

        Workspace otherWorkspace = createWorkspace(
                "byo-nonmember-ws-2",
                "Non Member 2",
                "byo-nonmember-org-2",
                AccountType.ORG,
                persistUser("byo-nonmember-owner-2"));

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections", otherWorkspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @WithAdminUser
    void creatingAConnectionIsForbiddenWhenTheInstanceHasDisabledWorkspaceByoConnections() {
        Workspace workspace = setupWorkspace("byo-gate-ws");
        InstanceLlmSettings settings = new InstanceLlmSettings();
        settings.setId((short) 1);
        settings.setAllowWorkspaceConnections(false);
        instanceLlmSettingsRepository.save(settings);

        var request = new CreateWorkspaceLlmConnectionRequestDTO(
                "gated-out",
                "Gated Out",
                LlmCatalogTestFixtures.PUBLIC_BASE_URL,
                "openai-completions",
                LlmAuthMode.BEARER,
                null,
                true);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isForbidden();
    }
}
