package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/**
 * Confidentiality + integrity regression suite for the AES-GCM-sealed OAuth state cookie. A valid
 * request must round-trip; any tampered, truncated, wrong-key, or non-decryptable cookie must be
 * rejected (returns {@code null}, never a corrupt request).
 */
class CookieOAuth2AuthorizationRequestRepositoryTest extends BaseUnitTest {

    private byte[] key;
    private CookieOAuth2AuthorizationRequestRepository repo;

    @BeforeEach
    void setUp() {
        key = new byte[32];
        new SecureRandom().nextBytes(key);
        repo = new CookieOAuth2AuthorizationRequestRepository(key);
    }

    private static OAuth2AuthorizationRequest sampleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://idp.example.test/authorize")
                .clientId("client-123")
                .redirectUri("https://app.example.test/login/oauth2/code/github")
                .scopes(Set.of("read:user"))
                .state("state-xyz")
                .attributes(attrs -> {
                    attrs.put(OAuth2ParameterNames.REGISTRATION_ID, "github");
                    attrs.put(PkceParameterNames.CODE_VERIFIER, "verifier-abc-123");
                })
                .additionalParameters(params -> params.put("prompt", "consent"))
                .authorizationRequestUri("https://idp.example.test/authorize?response_type=code&client_id=client-123")
                .build();
    }

    private Cookie saveAndExtractCookie(CookieOAuth2AuthorizationRequestRepository repository) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest(), req, res);
        Cookie cookie = res.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME);
        assertThat(cookie).as("a state cookie must be written").isNotNull();
        return cookie;
    }

    @Test
    void roundTripsAValidRequest() {
        Cookie cookie = saveAndExtractCookie(repo);

        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isPositive();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");

        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setCookies(cookie);
        OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(load);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getClientId()).isEqualTo("client-123");
        assertThat(loaded.getState()).isEqualTo("state-xyz");
        assertThat(loaded.getRedirectUri()).isEqualTo("https://app.example.test/login/oauth2/code/github");
    }

    @Test
    void pkceCodeVerifierAndRegistrationIdSurviveJsonRoundTrip() {
        Cookie cookie = saveAndExtractCookie(repo);

        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setCookies(cookie);
        OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(load);

        assertThat(loaded).isNotNull();
        // The token-exchange leg reads the verifier from getAttributes(); losing it breaks login.
        assertThat(loaded.getAttributes().get(PkceParameterNames.CODE_VERIFIER)).isEqualTo("verifier-abc-123");
        assertThat(loaded.getAttributes().get(OAuth2ParameterNames.REGISTRATION_ID))
                .isEqualTo("github");
        assertThat(loaded.getAdditionalParameters()).containsEntry("prompt", "consent");
        assertThat(loaded.getGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(loaded.getScopes()).containsExactly("read:user");
    }

    @Test
    void rejectsTamperedCiphertext() {
        Cookie cookie = saveAndExtractCookie(repo);
        // Flip a character in the (base64url) sealed value → GCM tag mismatch.
        String value = cookie.getValue();
        char first = value.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + value.substring(1);

        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setCookies(new Cookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, tampered));

        assertThat(repo.loadAuthorizationRequest(load)).isNull();
    }

    @Test
    void rejectsCookieSealedWithDifferentKey() {
        // Seal with one key, attempt to load with another → GCM auth failure.
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        CookieOAuth2AuthorizationRequestRepository otherRepo = new CookieOAuth2AuthorizationRequestRepository(otherKey);
        Cookie cookie = saveAndExtractCookie(otherRepo);

        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setCookies(cookie);

        assertThat(repo.loadAuthorizationRequest(load)).isNull();
    }

    @Test
    void rejectsGarbageCookieValue() {
        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setCookies(
                new Cookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, "not-base64-or-encrypted-$$$"));

        assertThat(repo.loadAuthorizationRequest(load)).isNull();
    }

    @Test
    void rejectsTruncatedCookie() {
        MockHttpServletRequest load = new MockHttpServletRequest();
        // Valid base64url but far too short to hold nonce + GCM tag.
        load.setCookies(new Cookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, "AAAA"));

        assertThat(repo.loadAuthorizationRequest(load)).isNull();
    }

    @Test
    void returnsNullWhenNoCookiePresent() {
        assertThat(repo.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
    }

    @Test
    void removeClearsCookieAndReturnsLoadedRequest() {
        Cookie cookie = saveAndExtractCookie(repo);
        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setCookies(cookie);
        MockHttpServletResponse res = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repo.removeAuthorizationRequest(load, res);

        assertThat(removed).isNotNull();
        Cookie cleared = res.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(cleared.getSecure()).isTrue();
        assertThat(cleared.getPath()).isEqualTo("/");
        assertThat(cleared.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void usesFreshCiphertextForEachAuthorizationRequest() {
        assertThat(saveAndExtractCookie(repo).getValue())
                .isNotEqualTo(saveAndExtractCookie(repo).getValue());
    }

    @Test
    void savingNullClearsTheAuthorizationCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        repo.saveAuthorizationRequest(null, new MockHttpServletRequest(), response);

        Cookie cookie = response.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isZero();
    }

    @Test
    void rejectsNonThirtyTwoByteKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CookieOAuth2AuthorizationRequestRepository(new byte[16]));
    }
}
