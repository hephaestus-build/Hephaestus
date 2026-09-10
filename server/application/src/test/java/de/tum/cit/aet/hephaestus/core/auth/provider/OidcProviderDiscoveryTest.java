package de.tum.cit.aet.hephaestus.core.auth.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.tum.cit.aet.hephaestus.core.security.OidcIssuerPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class OidcProviderDiscoveryTest extends BaseUnitTest {

    private static final String ISSUER = "https://identity.example.com/realms/team/";
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(restTemplate).build();
    private final OidcProviderDiscovery discovery =
            new OidcProviderDiscovery(new OidcIssuerPolicy(Set.of(ISSUER)), restTemplate);

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void shouldBuildAStableSubjectRegistrationFromAnApprovedIssuersMetadata() {
        respondWith(metadata());

        var registration = discovery
                .discover(ISSUER)
                .registrationId("organization")
                .clientId("client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://hephaestus.example.com/api/login/oauth2/code/organization")
                .scope("openid", "profile", "email")
                .build();

        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(ISSUER);
        assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                .isEqualTo("sub");
        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("https://identity.example.com/token");
        assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo("https://identity.example.com/keys");
    }

    @Test
    void shouldNotFetchDiscoveryForAnUnapprovedRealm() {
        assertThatThrownBy(() -> discovery.discover("https://identity.example.com/realms/other/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://identity.example.com/realms/other/", "https://identity.example.com/realms/team"})
    void shouldRejectMetadataFromAnotherIssuer(String issuer) {
        var metadata = metadata();
        metadata.put("issuer", issuer);
        respondWith(metadata);

        assertThatThrownBy(() -> discovery.discover(ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization_endpoint", "token_endpoint", "jwks_uri", "userinfo_endpoint"})
    void shouldRejectPrivateEndpointsBeforeBuildingARegistration(String field) {
        var metadata = metadata();
        metadata.put(field, "https://169.254.169.254/credentials");
        respondWith(metadata);

        assertThatThrownBy(() -> discovery.discover(ISSUER)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization_endpoint", "token_endpoint", "jwks_uri"})
    void shouldRejectMissingRequiredEndpoints(String field) {
        var metadata = metadata();
        metadata.remove(field);
        respondWith(metadata);

        assertThatThrownBy(() -> discovery.discover(ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }

    @Test
    void shouldAcceptAnIssuerWithoutAnOptionalUserinfoEndpoint() {
        var metadata = metadata();
        metadata.remove("userinfo_endpoint");
        respondWith(metadata);

        assertThat(discovery.discover(ISSUER)).isNotNull();
    }

    private void respondWith(Map<String, Object> metadata) {
        server.expect(requestTo("https://identity.example.com/realms/team/.well-known/openid-configuration"))
                .andRespond(withSuccess(new ObjectMapper().writeValueAsString(metadata), MediaType.APPLICATION_JSON));
    }

    private static Map<String, Object> metadata() {
        return new LinkedHashMap<>(Map.of(
                "issuer",
                ISSUER,
                "authorization_endpoint",
                "https://identity.example.com/authorize",
                "token_endpoint",
                "https://identity.example.com/token",
                "jwks_uri",
                "https://identity.example.com/keys",
                "userinfo_endpoint",
                "https://identity.example.com/userinfo",
                "response_types_supported",
                List.of("code"),
                "subject_types_supported",
                List.of("public"),
                "id_token_signing_alg_values_supported",
                List.of("RS256")));
    }
}
