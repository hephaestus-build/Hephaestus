package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceEchoControllers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceScopedControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithAdminUser
    void scopedControllerRequiresWorkspaceSlugAndInjectsContext() {
        User owner = persistUser("admin");
        Workspace workspace = createWorkspace("scoped-alpha", "Scoped Alpha", "alpha", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        WorkspaceEchoControllers.ScopedEcho response = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/scoped-test/echo", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceEchoControllers.ScopedEcho.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.requestPath())
                .endsWith("/workspaces/" + workspace.getWorkspaceSlug() + "/scoped-test/echo");
        assertThat(response.contextSlug()).isEqualTo(workspace.getWorkspaceSlug());
        assertThat(response.contextId()).isEqualTo(workspace.getId());
        assertThat(response.roles()).containsExactly("OWNER");
    }

    @Test
    @WithAdminUser
    void scopedControllerIsNotReachableWithoutWorkspaceSlug() {
        webTestClient
                .get()
                .uri("/scoped-test/echo")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void unknownWorkspaceSlugReturnsNotFoundBeforeControllerExecutes() {
        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/scoped-test/echo", "missing")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }
}
