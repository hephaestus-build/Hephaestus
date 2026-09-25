package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The browser asks before it sends a request header cross-origin; the SPA's conditional writes need If-Match. */
class CorsPreflightIntegrationTest extends BaseIntegrationTest {

    /** The origin application-test.yml allows. */
    private static final String ALLOWED_ORIGIN = "http://localhost:4200";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldAllowIfMatchWhenAnAllowedOriginAsksBeforeAConditionalWrite() {
        webTestClient
                .options()
                .uri("/workspaces/any/practices/any")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.IF_MATCH)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader()
                .valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "(?i).*\\bIf-Match\\b.*")
                .expectBody(Void.class);
    }
}
