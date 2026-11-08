package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;

/**
 * Pins the GitHub email-verification policy: only the {@code primary && verified} address is trusted.
 */
class GitHubEmailOAuth2UserServiceTest extends BaseUnitTest {

    @Test
    void shouldKeepProviderIdentityWhenVerifiedEmailIsAdded() {
        var user = load(HttpStatus.OK, "[{\"email\":\"verified@example.com\",\"primary\":true,\"verified\":true}]");

        assertThat(user.getName()).isEqualTo("subject-1");
        assertThat(user.getAttributes())
                .containsEntry("email", "verified@example.com")
                .containsEntry("email_verified", true);
    }

    @Test
    void shouldKeepEmailUnverifiedWhenLookupFails() {
        var user = load(HttpStatus.SERVICE_UNAVAILABLE, "{}");

        assertThat(user.getName()).isEqualTo("subject-1");
        assertThat(user.getAttributes())
                .containsEntry("email", "unverified@example.com")
                .containsEntry("email_verified", false);
    }

    private OAuth2User load(HttpStatus status, String body) {
        var registration = ClientRegistration.withRegistrationId("github")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example/callback")
                .authorizationUri("https://github.example/authorize")
                .tokenUri("https://github.example/token")
                .userInfoUri("https://github.example/user")
                .userNameAttributeName("id")
                .build();
        var request = new OAuth2UserRequest(
                registration,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-token", Instant.now(), null));
        var delegate = mock(DefaultOAuth2UserService.class);
        when(delegate.loadUser(request))
                .thenReturn(new DefaultOAuth2User(
                        Set.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                        Map.of("id", "subject-1", "email", "unverified@example.com", "email_verified", false),
                        "id"));
        var response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var clients = RestClient.builder().requestInterceptor((outbound, bytes, execution) -> response);
        return new GitHubEmailOAuth2UserService(clients, delegate).loadUser(request);
    }

    @Test
    void primaryVerifiedEmail_isSelected() {
        List<Map<String, Object>> emails = List.of(
                Map.of("email", "secondary@x.de", "primary", false, "verified", true),
                Map.of("email", "p@x.de", "primary", true, "verified", true));

        assertThat(GitHubEmailOAuth2UserService.selectPrimaryVerifiedEmail(emails))
                .contains("p@x.de");
    }

    @Test
    void primaryButUnverified_isNotSelected() {
        List<Map<String, Object>> emails = List.of(Map.of("email", "p@x.de", "primary", true, "verified", false));

        assertThat(GitHubEmailOAuth2UserService.selectPrimaryVerifiedEmail(emails))
                .isEmpty();
    }

    @Test
    void verifiedButNotPrimary_isNotSelected() {
        List<Map<String, Object>> emails = List.of(Map.of("email", "v@x.de", "primary", false, "verified", true));

        assertThat(GitHubEmailOAuth2UserService.selectPrimaryVerifiedEmail(emails))
                .isEmpty();
    }

    @Test
    void nullOrEmpty_isEmpty() {
        assertThat(GitHubEmailOAuth2UserService.selectPrimaryVerifiedEmail(null))
                .isEmpty();
        assertThat(GitHubEmailOAuth2UserService.selectPrimaryVerifiedEmail(List.of()))
                .isEmpty();
    }
}
