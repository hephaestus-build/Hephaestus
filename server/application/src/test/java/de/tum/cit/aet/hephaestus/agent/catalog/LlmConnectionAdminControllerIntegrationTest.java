package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class LlmConnectionAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String MENTOR_TOKEN = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    private LlmConnectionDTO createConnection(String slug) {
        var request = new CreateLlmConnectionRequestDTO(
                slug,
                "Test Connection",
                LlmCatalogTestFixtures.PUBLIC_BASE_URL,
                "openai-completions",
                LlmAuthMode.BEARER,
                "sk-test-secret-1234",
                true);
        return Objects.requireNonNull(webTestClient
                .post()
                .uri("/admin/llm/connections")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(LlmConnectionDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    void appAdminCanCreateGetListUpdateAndDeleteAConnection() {
        LlmConnectionDTO created = createConnection("openai-prod");
        assertThat(created).isNotNull();
        assertThat(created.slug()).isEqualTo("openai-prod");
        assertThat(created.hasApiKey()).isTrue();
        assertThat(created.apiKeyLast4()).isEqualTo("1234");

        webTestClient
                .get()
                .uri("/admin/llm/connections/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("openai-prod");

        webTestClient
                .get()
                .uri("/admin/llm/connections")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1);

        var updateRequest = new UpdateLlmConnectionRequestDTO("Renamed Connection", null, null, null);
        webTestClient
                .patch()
                .uri("/admin/llm/connections/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Renamed Connection")
                // The key was never re-supplied on this update — it must survive untouched.
                .jsonPath("$.hasApiKey")
                .isEqualTo(true)
                .jsonPath("$.apiKeyLast4")
                .isEqualTo("1234");

        webTestClient
                .delete()
                .uri("/admin/llm/connections/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/admin/llm/connections/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void apiKeyIsNeverExposedInAnyResponse() {
        String body = webTestClient
                .post()
                .uri("/admin/llm/connections")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateLlmConnectionRequestDTO(
                        "redaction-test",
                        "Redaction Test",
                        LlmCatalogTestFixtures.PUBLIC_BASE_URL,
                        "openai-completions",
                        LlmAuthMode.BEARER,
                        "sk-super-secret-value",
                        true))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("sk-super-secret-value");
        assertThat(body).doesNotContain("\"apiKey\"");
        assertThat(body).contains("hasApiKey");
    }

    @Test
    void deletingAConnectionWithModelsReturns409() {
        LlmConnectionDTO connection = createConnection("in-use-connection");
        llmModelRepository.save(LlmCatalogTestFixtures.model(
                llmConnectionRepository.findById(connection.id()).orElseThrow(), "gpt-5-eu", "gpt-5"));

        webTestClient
                .delete()
                .uri("/admin/llm/connections/{id}", connection.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }

    @Test
    void nonAdminIsForbidden() {
        webTestClient
                .get()
                .uri("/admin/llm/connections")
                .headers(h -> h.setBearerAuth(MENTOR_TOKEN))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
