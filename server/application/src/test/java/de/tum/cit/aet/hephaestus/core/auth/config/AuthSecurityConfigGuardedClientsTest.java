package de.tum.cit.aet.hephaestus.core.auth.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.security.ScmEgressProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.OfflineNetwork;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** A sign-in sends the client secret and a user token, so it follows the same connect-time check as the API clients. */
class AuthSecurityConfigGuardedClientsTest extends BaseUnitTest {
    private static final String REBOUND = "https://signin-rebound.corp.example";

    @BeforeAll
    static void declareRebind() {
        OfflineNetwork.answer("api.github.com", "127.0.0.1");
        OfflineNetwork.answer("signin-rebound.corp.example", "169.254.169.254");
        OfflineNetwork.answer("api.github.com.signin-rebound.corp.example", "169.254.169.254");
    }

    private static AuthSecurityConfig config() {
        return new AuthSecurityConfig(
                mock(LoginProviderRepository.class),
                RestClient.builder(),
                new RestTemplateBuilder(),
                WebClient.builder(),
                new ScmServerEndpointPolicy(
                        new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of())));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("gitlab")
                .clientId("id")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://hephaestus.example/callback")
                .authorizationUri(REBOUND + "/oauth/authorize")
                .tokenUri(REBOUND + "/oauth/token")
                .userInfoUri(REBOUND + "/api/v4/user")
                .userNameAttributeName("id")
                .build();
    }

    @Test
    void shouldRefuseAtConnectTimeAUserInfoCallToAReboundAddress() {
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", Instant.now(), null);

        assertThatThrownBy(() -> config().oauthUserService().loadUser(new OAuth2UserRequest(registration(), token)))
                .hasRootCauseInstanceOf(UnknownHostException.class)
                .rootCause()
                .hasMessageContaining("(SSRF guard)");
    }

    @Test
    void shouldRefuseAtConnectTimeACodeExchangeToAReboundAddress() {
        var registration = registration();
        var request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(REBOUND + "/oauth/authorize")
                .clientId("id")
                .redirectUri("https://hephaestus.example/callback")
                .state("state")
                .build();
        var response = OAuth2AuthorizationResponse.success("code")
                .redirectUri("https://hephaestus.example/callback")
                .state("state")
                .build();

        assertThatThrownBy(() -> config().guardedTokenClient()
                        .getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(
                                registration, new OAuth2AuthorizationExchange(request, response))))
                .hasRootCauseInstanceOf(UnknownHostException.class)
                .rootCause()
                .hasMessageContaining("(SSRF guard)");
    }

    @Test
    void shouldGuardThePrimaryGitHubUserInfoCall() {
        var repository = mock(LoginProviderRepository.class);
        var github = new LoginProvider();
        github.setType(LoginProvider.ProviderType.GITHUB);
        when(repository.findByRegistrationId("github")).thenReturn(Optional.of(github));
        var config = new AuthSecurityConfig(
                repository,
                RestClient.builder(),
                new RestTemplateBuilder(),
                WebClient.builder(),
                new ScmServerEndpointPolicy(
                        new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of())));
        var registration = ClientRegistration.withClientRegistration(registration())
                .registrationId("github")
                .userInfoUri("https://api.github.com/user")
                .build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", Instant.now(), null);

        assertThatThrownBy(() -> config.oauthUserService().loadUser(new OAuth2UserRequest(registration, token)))
                .hasRootCauseInstanceOf(UnknownHostException.class)
                .rootCause()
                .hasMessageContaining("(SSRF guard)");
    }

    /** A GitLab whose URL only begins like GitHub's gets the guarded client, by its stored type. */
    @Test
    void shouldGiveAGitLabThatLooksLikeGitHubTheGuardedClient() {
        var repository = mock(LoginProviderRepository.class);
        var gitLab = new LoginProvider();
        gitLab.setType(LoginProvider.ProviderType.GITLAB);
        when(repository.findByRegistrationId("gitlab")).thenReturn(Optional.of(gitLab));
        var config = new AuthSecurityConfig(
                repository,
                RestClient.builder(),
                new RestTemplateBuilder(),
                WebClient.builder(),
                new ScmServerEndpointPolicy(
                        new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of())));
        var registration = ClientRegistration.withClientRegistration(registration())
                .userInfoUri("https://api.github.com.signin-rebound.corp.example/user")
                .build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", Instant.now(), null);

        assertThatThrownBy(() -> config.oauthUserService().loadUser(new OAuth2UserRequest(registration, token)))
                .hasRootCauseInstanceOf(UnknownHostException.class)
                .rootCause()
                .hasMessageContaining("(SSRF guard)");
    }
}
