package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class ReleaseAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Test
    void shouldRejectAnonymousRequests() {
        client.get()
                .uri("/admin/release")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        // A cookie-less POST is refused by the CSRF filter before authentication is consulted.
        client.post()
                .uri("/admin/release/checks")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithUser
    void shouldRejectWorkspaceMembersWhoAreNotInstanceAdministrators() {
        client.get()
                .uri("/admin/release")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.post()
                .uri("/admin/release/checks")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldReportTheDevelopmentBuildWithoutContactingGitHub() {
        client.get()
                .uri("/admin/release")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.running.channel")
                .isEqualTo("DEVELOPMENT")
                .jsonPath("$.status")
                .isEqualTo("NOT_APPLICABLE")
                .jsonPath("$.lastAttempt")
                .doesNotExist();
        client.post()
                .uri("/admin/release/checks")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("NOT_APPLICABLE")
                .jsonPath("$.lastAttempt")
                .doesNotExist();
    }
}
