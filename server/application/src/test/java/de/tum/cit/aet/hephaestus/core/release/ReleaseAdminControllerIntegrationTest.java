package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
@TestPropertySource(properties = "hephaestus.release.check-enabled=false")
class ReleaseAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Test
    void shouldRejectAnonymousRequests() {
        client.get().uri("/admin/release").exchange().expectStatus().isUnauthorized();
        client.post().uri("/admin/release/checks").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @WithUser
    void shouldRejectNonInstanceAdministrators() {
        client.get()
                .uri("/admin/release")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/admin/release/checks")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @WithAdminUser
    void shouldReturnDisabledStateWithoutAllowingManualChecksToBypassOptOut() {
        client.get()
                .uri("/admin/release")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("DISABLED")
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
                .isEqualTo("DISABLED")
                .jsonPath("$.lastAttempt")
                .doesNotExist();
    }
}
