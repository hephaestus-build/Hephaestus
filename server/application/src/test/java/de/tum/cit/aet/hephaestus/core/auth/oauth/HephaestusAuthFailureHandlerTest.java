package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Pins the {@code LOGIN_FAILED} audit emission on the {@code oauth2Login} failure path — a
 * security-relevant signal a bare failure redirect would silently drop. Fails if the emitter is
 * removed, mis-tagged, or starts recording the exception message (potential PII / token leakage)
 * instead of only the exception type.
 */
class HephaestusAuthFailureHandlerTest extends BaseUnitTest {

    private static final String EXTENSION_ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
    private static final String CALLBACK = "https://" + EXTENSION_ID + ".chromiumapp.org/callback";
    private static final byte[] KEY = new byte[32];

    private final AuthIntentCookie intentCookie = new AuthIntentCookie(KEY);

    private static InstalledClientRegistry registry(List<String> ids) {
        return new InstalledClientRegistry(AuthPropertiesFixture.withBrowserExtensionIds(ids));
    }

    private HephaestusAuthFailureHandler handler(List<String> ids, AuthIntentCookie cookie) {
        return new HephaestusAuthFailureHandler(
                new AuthEventLogger(mock(AuthEventWriter.class)), cookie, registry(ids), "");
    }

    /** A request carrying a sealed installed-client intent, as {@code /auth/login?mode=client} writes it. */
    private MockHttpServletRequest requestWithClientIntent() {
        MockHttpServletResponse written = new MockHttpServletResponse();
        intentCookie.write(
                written,
                AuthIntentCookie.Intent.client(new AuthIntentCookie.Intent.ClientRequest(
                        EXTENSION_ID, CALLBACK, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "state-1")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(written.getCookies());
        return request;
    }

    private static void assertIntentCleared(MockHttpServletResponse response) {
        Cookie cleared = response.getCookie(AuthIntentCookie.COOKIE_NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    void shouldEndAtTheRegisteredCallbackWithTheClientsStateWhenAClientSignInFails() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(List.of(EXTENSION_ID), intentCookie)
                .onAuthenticationFailure(requestWithClientIntent(), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "?error=oauth_failure&state=state-1");
        assertIntentCleared(response);
    }

    @Test
    void shouldReportAccessDeniedWhenThePersonCancelledAtTheProvider() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(List.of(EXTENSION_ID), intentCookie)
                .onAuthenticationFailure(
                        requestWithClientIntent(),
                        response,
                        new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "?error=access_denied&state=state-1");
    }

    @Test
    void shouldUseTheServerErrorPageWhenTheCallbacksRegistrationWasRemovedMidFlow() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(List.of(), intentCookie)
                .onAuthenticationFailure(requestWithClientIntent(), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=oauth_failure");
        assertIntentCleared(response);
    }

    @Test
    void shouldUseTheServerErrorPageWhenTheIntentCookieWasTamperedWith() throws Exception {
        MockHttpServletRequest request = requestWithClientIntent();
        Cookie original = Arrays.stream(request.getCookies())
                .filter(c -> c.getName().equals(AuthIntentCookie.COOKIE_NAME))
                .findFirst()
                .orElseThrow();
        char[] value = original.getValue().toCharArray();
        value[value.length - 3] = value[value.length - 3] == 'A' ? 'B' : 'A';
        request.setCookies(new Cookie(AuthIntentCookie.COOKIE_NAME, new String(value)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(List.of(EXTENSION_ID), intentCookie)
                .onAuthenticationFailure(request, response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=oauth_failure");
    }

    @Test
    void shouldUseTheServerErrorPageWhenTheIntentExpiredOrIsMissing() throws Exception {
        AuthIntentCookie later =
                new AuthIntentCookie(KEY, Clock.fixed(Instant.now().plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        MockHttpServletResponse expired = new MockHttpServletResponse();
        handler(List.of(EXTENSION_ID), later)
                .onAuthenticationFailure(requestWithClientIntent(), expired, new BadCredentialsException("x"));
        assertThat(expired.getRedirectedUrl()).isEqualTo("/auth/error?code=oauth_failure");

        MockHttpServletResponse missing = new MockHttpServletResponse();
        handler(List.of(EXTENSION_ID), intentCookie)
                .onAuthenticationFailure(new MockHttpServletRequest(), missing, new BadCredentialsException("x"));
        assertThat(missing.getRedirectedUrl()).isEqualTo("/auth/error?code=oauth_failure");
    }

    @Test
    void auditsLoginFailedWithExceptionTypeOnlyAndRedirectsToErrorPage() throws Exception {
        AuthEventWriter writer = mock(AuthEventWriter.class);
        // Blank webapp url → SPA + API share an origin, so the redirect is a relative path.
        HephaestusAuthFailureHandler handler = new HephaestusAuthFailureHandler(
                new AuthEventLogger(writer), intentCookie, registry(List.of(EXTENSION_ID)), "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new BadCredentialsException("a secret value in the message that must NOT be audited"));

        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(writer).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.LOGIN_FAILED);
        assertThat(event.getValue().result()).isEqualTo(AuthEvent.Result.FAILURE);
        // The reason is the exception TYPE only — never the message (no PII / token leakage).
        assertThat(event.getValue().failureReason()).isEqualTo("BadCredentialsException");
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=oauth_failure");
    }
}
