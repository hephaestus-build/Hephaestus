package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies the {@link de.tum.cit.aet.hephaestus.core.security.ImpersonationGuard} is actually wired
 * onto the resource-server chain (it was once dead code — an unregistered filter — so impersonation
 * granted full write access as the target).
 *
 * <p>Scope is the wiring contract only: that a non-escape write IS blocked when the guard is
 * registered, and that the lifecycle escape path is NOT blocked. The full block/allow decision
 * matrix lives in the fast unit test {@code ImpersonationGuardTest}.
 */
class ImpersonationGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void writeUnderImpersonationTokenIsBlockedWith403ProblemDetail() {
        // POST /user is an authenticated, non-safe, non-escape path. The guard returns its 403 from a
        // servlet filter — before routing — so no controller side effect occurs even if a handler exists.
        webTestClient
                .post()
                .uri("/user")
                .headers(headers -> headers.setBearerAuth(TestSecurityConfig.IMPERSONATION_TOKEN))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(403)
                .jsonPath("$.code")
                .isEqualTo("impersonation_read_only");
    }

    @Test
    void exitImpersonationIsNotBlockedByTheGuard() {
        // The escape hatch: POST /auth/impersonate:exit carries NO allow-writes header, yet must never
        // be turned into the guard's read-only 403 — otherwise the operator is trapped. This path is
        // @PreAuthorize("isAuthenticated()") and the guard is the only thing that 403s it, so asserting
        // "not 403" precisely catches the trap regardless of the downstream exit outcome (204 / error).
        webTestClient
                .post()
                .uri("/auth/impersonate:exit")
                .headers(headers -> headers.setBearerAuth(TestSecurityConfig.IMPERSONATION_TOKEN))
                .exchange()
                .expectStatus()
                .value(status -> Assertions.assertThat(status).isNotEqualTo(403))
                .expectBody(Void.class);
    }

    @Test
    void safeMethodUnderImpersonationTokenIsAllowed() {
        // GET is always allowed even under impersonation ("see what they see"). Any non-403 outcome
        // proves the guard did not block the GET.
        webTestClient
                .get()
                .uri("/auth/error")
                .headers(headers -> headers.setBearerAuth(TestSecurityConfig.IMPERSONATION_TOKEN))
                .exchange()
                .expectStatus()
                .value(status -> Assertions.assertThat(status).isNotEqualTo(403))
                .expectBody(Void.class);
    }
}
