package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import jakarta.servlet.Filter;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins that {@code core.security.SecurityHeaders} is actually applied on the live security chain.
 * {@code SecurityHeaders.apply} is the single source of truth wired into the resource-server, the
 * oauth2Login and the lockdown chains — but nothing asserted the headers reach the wire, so dropping
 * {@code .apply()} from a chain or a Spring-default change would have been caught only by inspection.
 * Asserted against the public {@code GET /identity-providers} so no auth/CSRF is needed.
 */
class SecurityHeadersIntegrationTest extends RealAuthIntegrationTest {

    /** The policy that browsers already keep, sent by the server for the API and the web app alike. */
    private static final String HSTS = "max-age=2592000 ; includeSubDomains";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WebApplicationContext application;

    @Test
    void publicResponseCarriesTheHardenedSecurityHeaders() {
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
                        value -> MatcherAssert.assertThat(
                                value,
                                allOf(
                                        containsString("default-src 'self'"),
                                        containsString("script-src 'self';"),
                                        containsString("img-src 'self' data: blob: https:"),
                                        containsString("object-src 'none'"),
                                        // A page on a workspace host calls the API on the apex.
                                        containsString("connect-src 'self' https://hephaestus.build;"),
                                        containsString("form-action 'self'"),
                                        containsString("frame-ancestors 'none'"),
                                        containsString("base-uri 'self'"),
                                        // Regression guards: no instance-specific host, and no plaintext-HTTP image
                                        // downgrade
                                        // ('http:' is not a substring of 'https:').
                                        not(containsString("gitlab")),
                                        not(containsString("github")),
                                        not(containsString("http:")))))
                // A future accidental re-introduction of reportOnly() must fail this test.
                .expectHeader()
                .doesNotExist("Content-Security-Policy-Report-Only")
                .expectBody(Void.class);
    }

    /**
     * Spring writes HSTS only on a secure request, and the WebTestClient above drives plain HTTP. MockMvc over the
     * same context and filter chain sends a secure request without a second application context.
     */
    private MockMvc secureMvc() {
        return MockMvcBuilders.webAppContextSetup(application)
                .addFilter(application.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void shouldSendTheServerHstsPolicyWhenAnApiRequestIsSecure() throws Exception {
        secureMvc()
                .perform(get(ApiPath.of("/identity-providers")).secure(true))
                .andExpect(header().string("Strict-Transport-Security", HSTS));
    }

    @Test
    void shouldSendTheSameHstsPolicyWhenAWebhookRequestIsSecure() throws Exception {
        secureMvc()
                .perform(post("/webhooks/github").secure(true))
                .andExpect(header().string("Strict-Transport-Security", HSTS))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
