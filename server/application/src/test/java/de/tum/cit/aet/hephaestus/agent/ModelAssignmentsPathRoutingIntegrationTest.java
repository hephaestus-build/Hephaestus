package de.tum.cit.aet.hephaestus.agent;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class ModelAssignmentsPathRoutingIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private Workspace setupWorkspace() {
        User owner = persistUser("routing-owner");
        Workspace workspace = createWorkspace("routing-ws", "Routing Workspace", "routing-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    @Test
    @WithAdminUser
    @DisplayName("the sibling collection GET /model-assignments is still its own route")
    void agentsCollectionIsNotShadowedByTheJobsRoute() {
        Workspace workspace = setupWorkspace();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray();
    }

    @Test
    @WithAdminUser
    @DisplayName("PUT /model-assignments/{purpose} rejects a value that is not a purpose")
    void aNonPurposeIsRefusedWith400RatherThanFallingThroughTo404() {
        Workspace workspace = setupWorkspace();

        webTestClient
                .put()
                .uri("/workspaces/{slug}/model-assignments/not-a-purpose", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", 1))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }
}
