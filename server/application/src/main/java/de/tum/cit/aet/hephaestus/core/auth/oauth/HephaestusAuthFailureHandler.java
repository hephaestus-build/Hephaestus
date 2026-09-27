package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInRedirect;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Failure handler for {@code oauth2Login}: audits the failed login ({@code LOGIN_FAILED} — a
 * security-relevant signal a bare redirect would drop) and sends the SPA to its error page, or an
 * installed client to its still-registered callback. The
 * recorded reason is the exception TYPE only, never PII. Extracted from {@code AuthSecurityConfig}
 * (mirroring {@link HephaestusAuthSuccessHandler}) so the chain bean stays under the parameter limit.
 */
@ConditionalOnServerRole
@Component
public class HephaestusAuthFailureHandler implements AuthenticationFailureHandler {

    private final AuthEventLogger authEventLogger;
    private final AuthIntentCookie authIntentCookie;
    private final InstalledClientRegistry installedClients;

    /**
     * SPA origin (no trailing slash). Blank in production (SPA + API share an origin → a relative path
     * is correct); set to e.g. {@code http://localhost:4200} in local dev where they differ.
     */
    private final String appBaseUrl;

    public HephaestusAuthFailureHandler(
            AuthEventLogger authEventLogger,
            AuthIntentCookie authIntentCookie,
            InstalledClientRegistry installedClients,
            @Value("${hephaestus.webapp.url:}") String webappBaseUrl) {
        this.authEventLogger = authEventLogger;
        this.authIntentCookie = authIntentCookie;
        this.installedClients = installedClients;
        this.appBaseUrl = stripTrailingSlash(webappBaseUrl);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        authEventLogger
                .event(AuthEvent.EventType.LOGIN_FAILED, AuthEvent.Result.FAILURE)
                .failureReason(exception.getClass().getSimpleName())
                .record();
        ClientCallback client = ClientCallback.of(authIntentCookie.read(request), installedClients);
        authIntentCookie.clear(response);
        if (client instanceof ClientCallback.Registered registered) {
            // An installed-client sign-in ends at the client's registered callback, with its state.
            response.sendRedirect(ClientSignInRedirect.error(
                    registered.client().redirectUri(),
                    clientErrorCode(exception),
                    registered.request().state()));
            return;
        }
        response.sendRedirect(appBaseUrl + "/auth/error?code=oauth_failure");
    }

    /** {@code access_denied} when the person cancelled at the identity provider, so the client can stay quiet. */
    private static String clientErrorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth
                && "access_denied".equals(oauth.getError().getErrorCode())) {
            return "access_denied";
        }
        return "oauth_failure";
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
