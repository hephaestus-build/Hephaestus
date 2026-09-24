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

/**
 * {@code GET /agents/jobs} (literal) and {@code GET /agents/{purpose}} (template) are both two-segment
 * patterns under the same parent. Spring's {@code PathPattern} comparator ranks the literal higher; if
 * that ordering ever flipped, the job-history screen would start failing enum conversion with a 400
 * while both controllers kept passing their own suites in isolation.
 */
class AgentsPathDispatchIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private Workspace setupWorkspace() {
        User owner = persistUser("dispatch-owner");
        Workspace workspace =
                createWorkspace("dispatch-ws", "Dispatch Workspace", "dispatch-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    @Test
    @WithAdminUser
    @DisplayName("GET /agents/jobs reaches the job controller, not /agents/{purpose}")
    void jobsSegmentOutranksThePurposeTemplate() {
        Workspace workspace = setupWorkspace();

        // A paginated envelope can only have come from AgentJobController; the binding controller
        // returns a bare array.
        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content")
                .isArray()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
    }

    @Test
    @WithAdminUser
    @DisplayName("the sibling collection GET /agents is still its own route")
    void agentsCollectionIsNotShadowedByTheJobsRoute() {
        Workspace workspace = setupWorkspace();

        // A bare array is the binding controller's shape.
        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents", workspace.getWorkspaceSlug())
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
    @DisplayName("PUT /agents/{purpose} rejects a value that is not a purpose")
    void aNonPurposeIsRefusedWith400RatherThanFallingThroughTo404() {
        Workspace workspace = setupWorkspace();

        webTestClient
                .put()
                .uri("/workspaces/{slug}/agents/not-a-purpose", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", 1))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }
}
