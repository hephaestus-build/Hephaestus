package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.nativesession.NativeSignInRedirect;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Failure handler for {@code oauth2Login}: audits the failed login ({@code LOGIN_FAILED} — a
 * security-relevant signal a bare redirect would drop) and sends the SPA to its error page. The
 * recorded reason is the exception TYPE only, never PII. Extracted from {@code AuthSecurityConfig}
 * (mirroring {@link HephaestusAuthSuccessHandler}) so the chain bean stays under the parameter limit.
 */
@ConditionalOnServerRole
@Component
public class HephaestusAuthFailureHandler implements AuthenticationFailureHandler {

    private final AuthEventLogger authEventLogger;
    private final AuthIntentCookie authIntentCookie;

    /**
     * SPA origin (no trailing slash). Blank in production (SPA + API share an origin → a relative path
     * is correct); set to e.g. {@code http://localhost:4200} in local dev where they differ.
     */
    private final String appBaseUrl;

    public HephaestusAuthFailureHandler(
            AuthEventLogger authEventLogger,
            AuthIntentCookie authIntentCookie,
            @Value("${hephaestus.webapp.url:}") String webappBaseUrl) {
        this.authEventLogger = authEventLogger;
        this.authIntentCookie = authIntentCookie;
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
        AuthIntentCookie.Intent intent = authIntentCookie.read(request);
        authIntentCookie.clear(response);
        AuthIntentCookie.Intent.@Nullable NativeRequest nativeRequest =
                intent != null && intent.mode() == AuthIntentCookie.Intent.Mode.NATIVE ? intent.nativeRequest() : null;
        if (nativeRequest != null) {
            // A native sign-in started in the app's sign-in sheet; end it there, with the app's state.
            response.sendRedirect(NativeSignInRedirect.error(
                    nativeRequest.redirectUri(), nativeErrorCode(exception), nativeRequest.state()));
            return;
        }
        response.sendRedirect(appBaseUrl + "/auth/error?code=oauth_failure");
    }

    /** {@code access_denied} when the person cancelled at the identity provider, so the app can stay quiet. */
    private static String nativeErrorCode(AuthenticationException exception) {
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
