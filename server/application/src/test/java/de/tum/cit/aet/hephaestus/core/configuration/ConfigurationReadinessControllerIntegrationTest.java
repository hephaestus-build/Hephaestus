package de.tum.cit.aet.hephaestus.core.configuration;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ConfigurationReadinessControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithUser
    void rejectsNonAdmin() {
        webTestClient
                .get()
                .uri("/admin/configuration-readiness")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void returnsStructuredRedactedFactsToAdmin() {
        webTestClient
                .get()
                .uri("/admin/configuration-readiness")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo("runtime.roles")
                .jsonPath("$[0].roles[0]")
                .isEqualTo("SERVER")
                .jsonPath("$[?(@.id == 'auth.login-provider')]")
                .exists();
    }
}
