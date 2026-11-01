package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class ProblemRequestIdIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithUser
    @SuppressWarnings("unchecked")
    void shouldNameTheRequestInItsProblemAsInItsHeader() {
        var result = webTestClient
                .post()
                .uri("/web-vitals")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vitals", "not a list"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Map.class)
                .returnResult();

        String header = result.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(header).matches("[0-9a-f]{32}");
        assertThat((Map<String, Object>) result.getResponseBody()).containsEntry("requestId", header);
    }

    @Test
    @WithUser
    @SuppressWarnings("unchecked")
    void shouldNameTheRequestInAProblemThatAFilterWrites() {
        var result = webTestClient
                .get()
                .uri("/workspaces/{slug}/practices", "no-such-workspace")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Map.class)
                .returnResult();

        String header = result.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(header).matches("[0-9a-f]{32}");
        assertThat((Map<String, Object>) result.getResponseBody()).containsEntry("requestId", header);
    }
}
