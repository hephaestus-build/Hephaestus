package de.tum.cit.aet.hephaestus.core.auth.dev;

import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The default (disabled) contract: the dev sign-in is not usable — with the flag off, neither the
 * permit rule nor the CSRF carve-out is registered, so the cookieless state-changing
 * {@code POST /auth/dev-login} is rejected 403 by CSRF before it can reach the controller, and sets no
 * cookie; discovery omits the {@code dev} row. This also proves the carve-out is flag-gated (it would
 * be 204 if the skip leaked). No {@code dev-login-enabled} property is set, so default {@code false} applies.
 */
class DevLoginDisabledIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void devLoginIsRejectedAndSetsNoCookieWhenDisabled() {
        webTestClient
                .post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"admin\":true}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectCookie()
                .doesNotExist("__Host-HEPHAESTUS_AT")
                .expectBody(Void.class);
    }

    @Test
    void discoveryOmitsTheDevRowWhenDisabled() {
        webTestClient
                .get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.registrationId == 'dev')]")
                .doesNotExist();
    }
}
