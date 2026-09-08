package de.tum.cit.aet.hephaestus.core.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class OidcHttpConfigurationTest extends BaseUnitTest {

    private static final String ISSUER = "https://identity.example.com/realms/team";
    private static final String CLIENT = "organization-client";
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(restTemplate).build();
    private final OidcHttpConfiguration configuration = new OidcHttpConfiguration();

    @Test
    void shouldValidateAnIdTokenWithTheConfiguredIssuerAudienceAndPublicKey() throws JOSEException {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        expectKeys(key);
        String token = token(key, ISSUER, CLIENT, Instant.now().plusSeconds(300));

        var jwt = configuration
                .oidcIdTokenDecoderFactory(restTemplate)
                .createDecoder(registration())
                .decode(token);

        assertThat(jwt.getSubject()).isEqualTo("immutable-subject");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(CLIENT);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-issuer", "other-audience", "expired", "other-key"})
    void shouldRejectInvalidIdTokens(String failure) throws JOSEException {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        expectKeys(key);
        RSAKey signingKey = failure.equals("other-key")
                ? new RSAKeyGenerator(2048).keyID("test-key").generate()
                : key;
        String issuer = failure.equals("other-issuer") ? "https://identity.example.com/realms/other" : ISSUER;
        String audience = failure.equals("other-audience") ? "another-client" : CLIENT;
        Instant expiry = failure.equals("expired")
                ? Instant.now().minusSeconds(300)
                : Instant.now().plusSeconds(300);
        String token = token(signingKey, issuer, audience, expiry);
        var decoder = configuration.oidcIdTokenDecoderFactory(restTemplate).createDecoder(registration());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        server.verify();
    }

    private void expectKeys(RSAKey key) {
        server.expect(requestTo("https://identity.example.com/keys"))
                .andRespond(withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
    }

    private static String token(RSAKey key, String issuer, String audience, Instant expiry) throws JOSEException {
        var token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject("immutable-subject")
                        .audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(600)))
                        .expirationTime(Date.from(expiry))
                        .build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("organization")
                .clientId(CLIENT)
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://hephaestus.example.com/api/login/oauth2/code/organization")
                .scope("openid")
                .issuerUri(ISSUER)
                .authorizationUri("https://identity.example.com/authorize")
                .tokenUri("https://identity.example.com/token")
                .jwkSetUri("https://identity.example.com/keys")
                .providerConfigurationMetadata(Map.of("issuer", ISSUER))
                .build();
    }
}
