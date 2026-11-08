package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class LiveSandboxGatewayTest {
    @TempDir
    Path directory;

    @Test
    void shouldRenewOnlyTheRegisteredFixtureSessionAndRefuseItAfterClose() throws Exception {
        try (var gateway = new LiveSandboxGateway()) {
            var client = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + gateway.port())
                    .build();
            UUID id = UUID.randomUUID();
            Path archive = Files.writeString(directory.resolve("workspace.tar"), "fixture bytes");
            try (var session = gateway.sessions().register(id, "fixture-credential", archive, "out")) {
                var response = Objects.requireNonNull(client.post()
                        .uri("/internal/llm/runtime/{id}/lease", session.id())
                        .header("Authorization", "Bearer fixture-credential")
                        .exchange((request, reply) -> {
                            assertThat(reply.getStatusCode().value()).isEqualTo(200);
                            assertThat(reply.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
                            return new JsonMapper().readTree(reply.getBody().readAllBytes());
                        }));
                assertThat(response.path("remainingMillis").asLong()).isEqualTo(60_000);
                assertThat(response.path("renewAfterMillis").asLong()).isEqualTo(20_000);
                assertThat(status(client, id, "other-credential")).isEqualTo(404);
                assertThat(status(client, UUID.randomUUID(), "fixture-credential"))
                        .isEqualTo(404);
            }
            assertThat(status(client, id, "fixture-credential")).isEqualTo(404);
        }
    }

    private static int status(RestClient client, UUID id, String credential) {
        return client.post()
                .uri("/internal/llm/runtime/{id}/lease", id)
                .header("Authorization", "Bearer " + credential)
                .exchange((request, response) -> {
                    response.getBody().transferTo(OutputStream.nullOutputStream());
                    return response.getStatusCode().value();
                });
    }
}
