package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies the stateless double-submit CSRF protection on the resource-server chain (ADR 0017).
 * The SPA authenticates by cookie, so SameSite=Lax alone is insufficient — a cookie-style
 * (non-bearer) state-changing request must carry the {@code X-XSRF-TOKEN} header matching the
 * {@code XSRF-TOKEN} cookie. This test FAILS if CSRF is disabled (the no-token POST would no longer
 * be rejected with 403).
 *
 * <p>Distinguishing the two failure modes: a missing/invalid CSRF token is rejected by the CSRF
 * filter with <b>403</b> before authentication; once CSRF passes, the (still unauthenticated)
 * request is rejected by the entry point with <b>401</b>. The 403→401 transition proves CSRF — not
 * auth — was the gate. Token fetch + matching-token replay reuse {@link TestAuthUtils}.
 */
class CsrfProtectionIntegrationTest extends BaseIntegrationTest {

    // Used only for the deliberate-mismatch case below; the happy path uses TestAuthUtils.withCsrf.
    private static final String XSRF_COOKIE = "__Host-XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void csrfCookieIsIssuedOnSafeRequest() {
        assertThat(TestAuthUtils.fetchCsrfToken(webTestClient)).isNotBlank();
    }

    @Test
    void cookieStylePostWithoutCsrfTokenIsRejected() {
        // No Authorization: Bearer header → treated as a cookie-style browser POST, so CSRF applies.
        // No token → 403 from the CSRF filter (before authentication; the SecurityContext has no
        // authentication yet, so AccessDenied resolves to 403, not the entry-point 401).
        webTestClient
                .post()
                .uri("/auth/logout")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void cookieStylePostWithMatchingCsrfTokenPassesCsrf() {
        // Replay a matching token (cookie + header). CSRF passes; the request is unauthenticated so the
        // entry point returns 401 (NOT the CSRF 403).
        String token = TestAuthUtils.fetchCsrfToken(webTestClient);

        webTestClient
                .post()
                .uri("/auth/logout")
                .headers(TestAuthUtils.withCsrf(token))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void cookieStylePostWithMismatchedCsrfTokenIsRejected() {
        String token = TestAuthUtils.fetchCsrfToken(webTestClient);

        webTestClient
                .post()
                .uri("/auth/logout")
                .header(HttpHeaders.COOKIE, XSRF_COOKIE + "=" + token)
                .header(XSRF_HEADER, "totally-different-value")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
