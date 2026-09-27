package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * Pins the {@code LOGIN_FAILED} audit emission on the {@code oauth2Login} failure path — a
 * security-relevant signal a bare failure redirect would silently drop. Fails if the emitter is
 * removed, mis-tagged, or starts recording the exception message (potential PII / token leakage)
 * instead of only the exception type.
 */
class HephaestusAuthFailureHandlerTest extends BaseUnitTest {

    @Test
    void auditsLoginFailedWithExceptionTypeOnlyAndRedirectsToErrorPage() throws Exception {
        AuthEventWriter writer = mock(AuthEventWriter.class);
        // Blank webapp url → SPA + API share an origin, so the redirect is a relative path.
        HephaestusAuthFailureHandler handler =
                new HephaestusAuthFailureHandler(new AuthEventLogger(writer), mock(AuthIntentCookie.class), "");
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
