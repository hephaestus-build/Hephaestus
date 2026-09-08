package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Verifies the cookie-first precedence contract from ADR 0017. */
class CookieBearerTokenResolverTest extends BaseUnitTest {

    private static final String COOKIE_NAME = AuthProperties.DEFAULT_COOKIE_NAME;
    private static final String COOKIE_TOKEN = "cookie-token";
    private static final String HEADER_TOKEN = "header-token";

    private CookieBearerTokenResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CookieBearerTokenResolver(AuthPropertiesFixture.defaults());
    }

    @Test
    void shouldIgnoreCredentialsOnlyOnPublicDiscoveryGet() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/identity-providers");
        request.setCookies(new Cookie(COOKIE_NAME, COOKIE_TOKEN));
        request.addHeader("Authorization", "Bearer " + HEADER_TOKEN);
        assertThat(resolver.resolve(request)).isNull();

        request.setMethod("POST");
        assertThat(resolver.resolve(request)).isEqualTo(COOKIE_TOKEN);
    }

    @Test
    void resolve_cookieAndHeader_returnsCookieToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, COOKIE_TOKEN));
        request.addHeader("Authorization", "Bearer " + HEADER_TOKEN);

        assertThat(resolver.resolve(request)).isEqualTo(COOKIE_TOKEN);
    }

    @Test
    void resolve_cookieOnly_returnsCookieToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, COOKIE_TOKEN));

        assertThat(resolver.resolve(request)).isEqualTo(COOKIE_TOKEN);
    }

    @Test
    void resolve_headerOnly_returnsHeaderToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + HEADER_TOKEN);

        assertThat(resolver.resolve(request)).isEqualTo(HEADER_TOKEN);
    }

    @Test
    void resolve_neither_returnsNull() {
        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    @Test
    void resolve_blankCookieValue_fallsBackToHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, ""));
        request.addHeader("Authorization", "Bearer " + HEADER_TOKEN);

        assertThat(resolver.resolve(request)).isEqualTo(HEADER_TOKEN);
    }
}
