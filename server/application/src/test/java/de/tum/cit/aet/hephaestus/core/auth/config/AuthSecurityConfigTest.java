package de.tum.cit.aet.hephaestus.core.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Pins the fail-closed contract of {@link AuthSecurityConfig#resolveStateCookieKey}: in production a
 * blank state-cookie key is fatal (an ephemeral per-pod key would silently abandon in-flight logins
 * on every restart and differ per replica), while dev/CI tolerate it with a generated key. Mirrors
 * {@code JwtSigningKeySealer}'s prod fail-fast. Fails if either guard is removed.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthSecurityConfigTest extends BaseUnitTest {

    private static MockEnvironment environment(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static AuthProperties propsWithKey(String key) {
        AuthProperties properties = mock(AuthProperties.class);
        when(properties.stateCookieKey()).thenReturn(key);
        return properties;
    }

    private static String base64Key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldCreateEphemeralArtifactKeysWithoutRuntimeWarnings(String profile, CapturedOutput output) {
        byte[] first = AuthSecurityConfig.resolveStateCookieKey(propsWithKey(""), environment(profile));
        byte[] second = AuthSecurityConfig.resolveStateCookieKey(propsWithKey(""), environment(profile));
        assertThat(first).hasSize(32).isNotEqualTo(second);
        assertThat(output.getAll()).doesNotContain("WARN");
        assertThatThrownBy(
                        () -> AuthSecurityConfig.resolveStateCookieKey(propsWithKey(""), environment(profile, "prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankKeyInProdFailsClosed() {
        assertThatThrownBy(() -> AuthSecurityConfig.resolveStateCookieKey(propsWithKey(""), environment("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required in production");
    }

    @Test
    void blankKeyOutsideProdGeneratesEphemeral32ByteKey() {
        byte[] key = AuthSecurityConfig.resolveStateCookieKey(propsWithKey(""), environment("dev"));

        assertThat(key).hasSize(32);
    }

    @Test
    void configuredKeyIsDecodedAndUsedEvenInProd() {
        byte[] key = AuthSecurityConfig.resolveStateCookieKey(propsWithKey(base64Key(32)), environment("prod"));

        assertThat(key).hasSize(32);
    }

    @Test
    void configuredKeyOfWrongLengthIsRejected() {
        assertThatThrownBy(
                        () -> AuthSecurityConfig.resolveStateCookieKey(propsWithKey(base64Key(16)), environment("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void pkceResolverEmitsCodeChallengeForConfidentialGithubClient() {
        // GitHub is plain OAuth2 (no id_token/nonce), so PKCE + state are THE authorization-code-injection
        // defenses (RFC 9700). Spring auto-enables PKCE only for PUBLIC clients; our github registration is
        // confidential (CLIENT_SECRET_BASIC), so PKCE is supplied solely by withPkce() in pkceResolver().
        // This asserts it is actually emitted — deleting that one line must fail this test.
        ClientRegistration github = ClientRegistration.withRegistrationId("github")
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("read:user", "user:email")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .build();
        OAuth2AuthorizationRequestResolver resolver =
                AuthSecurityConfig.pkceResolver(new InMemoryClientRegistrationRepository(github));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        request.setServletPath("/oauth2/authorization/github");
        OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request);

        assertThat(authorizationRequest).isNotNull();
        assertThat(authorizationRequest.getAdditionalParameters())
                .containsKey("code_challenge")
                .containsEntry("code_challenge_method", "S256");
    }
}
