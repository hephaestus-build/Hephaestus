package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What a client ID metadata document may declare (CIMD § 4, OAuth 2.1 § 2.3.1). */
class ClientMetadataResolverTest extends BaseUnitTest {

    private static final URI CLIENT = URI.create("https://client.example/metadata");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Optional<ClientMetadataResolver.ClientMetadata> resolve(String redirect, String name) {
        String document = JSON.writeValueAsString(Map.of(
                "client_id",
                CLIENT.toString(),
                "client_name",
                name,
                "token_endpoint_auth_method",
                "none",
                "redirect_uris",
                List.of(redirect)));
        return new ClientMetadataResolver(clientId -> Optional.of(document), JSON).resolve(CLIENT);
    }

    @Test
    void shouldAcceptAnHttpsOrLoopbackRedirect() {
        assertThat(resolve("https://client.example/callback", "Client")).isPresent();
        assertThat(resolve("http://127.0.0.1/callback", "Client")).isPresent();
        assertThat(resolve("http://localhost/callback", "Client")).isPresent();
    }

    @Test
    void shouldRefuseADocumentWhoseRedirectIsNeitherHttpsNorLoopback() {
        assertThat(resolve("http://client.example/callback", "Client")).isEmpty();
        assertThat(resolve("javascript://client.example/%0Aalert(1)", "Client")).isEmpty();
        assertThat(resolve("com.client.app://client.example/callback", "Client"))
                .isEmpty();
    }

    @Test
    void shouldStripControlAndBidiCharactersFromTheClientName() {
        String name = "Good" + Character.toString(0x202E) + "evil" + Character.toString(0x07) + " Client";
        assertThat(resolve("https://client.example/callback", name))
                .map(ClientMetadataResolver.ClientMetadata::clientName)
                .contains("Goodevil Client");
    }
}
