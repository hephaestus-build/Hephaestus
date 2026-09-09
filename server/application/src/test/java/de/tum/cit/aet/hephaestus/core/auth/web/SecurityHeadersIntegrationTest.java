package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pins that {@code core.security.SecurityHeaders} is actually applied on the live security chain.
 * {@code SecurityHeaders.apply} is the single source of truth wired into the resource-server, the
 * oauth2Login and the lockdown chains — but nothing asserted the headers reach the wire, so dropping
 * {@code .apply()} from a chain or a Spring-default change would have been caught only by inspection.
 * Asserted against the public {@code GET /identity-providers} so no auth/CSRF is needed.
 */
class SecurityHeadersIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void publicResponseCarriesTheHardenedSecurityHeaders() {
        // Strict-Transport-Security is intentionally NOT asserted here: Spring's HstsHeaderWriter only
        // emits it over HTTPS (requireSecure), and WebTestClient drives the app over plain HTTP. The
        // remaining hardening headers are protocol-independent and must always be present.
        webTestClient
                .get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Referrer-Policy", "strict-origin-when-cross-origin")
                .expectHeader()
                .valueEquals("Cross-Origin-Opener-Policy", "same-origin")
                .expectHeader()
                .valueEquals("Cross-Origin-Embedder-Policy", "credentialless")
                // CSP is ENFORCED (not report-only) and INSTANCE-AGNOSTIC. Pin the load-bearing directives,
                // the no-host posture, and the absence of a plaintext-HTTP image downgrade. `script-src 'self';`
                // (trailing ';') proves no source was appended (no 'unsafe-inline'/host).
                .expectHeader()
                .value(
                        "Content-Security-Policy",
                        allOf(
                                containsString("default-src 'self'"),
                                containsString("script-src 'self';"),
                                containsString("img-src 'self' data: https:"),
                                containsString("form-action 'self'"),
                                containsString("frame-ancestors 'none'"),
                                containsString("base-uri 'self'"),
                                // Regression guards: no instance-specific host, and no plaintext-HTTP image downgrade
                                // ('http:' is not a substring of 'https:').
                                not(containsString("gitlab")),
                                not(containsString("github")),
                                not(containsString("http:"))))
                // A future accidental re-introduction of reportOnly() must fail this test.
                .expectHeader()
                .doesNotExist("Content-Security-Policy-Report-Only")
                .expectBody(Void.class);
    }
}
