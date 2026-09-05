package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Production success handler for {@code oauth2Login}. Delegates account resolution / JIT /
 * linking to {@link AccountProvisioningService}, then mints a Hephaestus cookie-JWT via
 * {@link HephaestusJwtIssuer} and redirects to the validated {@code returnTo}.
 *
 * <p>Account lookup is always {@code (provider, subject)} — never email (nOAuth defence).
 */
@ConditionalOnServerRole
@Component
public class HephaestusAuthSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(HephaestusAuthSuccessHandler.class);

    private final AccountProvisioningService provisioningService;
    private final HephaestusJwtIssuer jwtIssuer;
    private final JwtPrincipalFactory principalFactory;
    private final AuthIntentCookie authIntentCookie;
    private final AuthProperties authProperties;
    private final AuthEventLogger authEventLogger;
    private final IdentityLinkAuthentication identityLinkAuthentication;
    private final Clock clock;

    /**
     * SPA origin (no trailing slash) prepended to every post-OAuth redirect, so the browser lands on
     * the app and not on the API origin. Blank in production (SPA + API share an origin → a relative
     * path is correct); set to e.g. {@code http://localhost:4200} in local dev where they differ.
     */
    private final String appBaseUrl;

    public HephaestusAuthSuccessHandler(
            AccountProvisioningService provisioningService,
            HephaestusJwtIssuer jwtIssuer,
            JwtPrincipalFactory principalFactory,
            AuthIntentCookie authIntentCookie,
            AuthProperties authProperties,
            AuthEventLogger authEventLogger,
            IdentityLinkAuthentication identityLinkAuthentication,
            Clock clock,
            @Value("${hephaestus.webapp.url:}") String webappBaseUrl) {
        this.provisioningService = provisioningService;
        this.jwtIssuer = jwtIssuer;
        this.principalFactory = principalFactory;
        this.authIntentCookie = authIntentCookie;
        this.authProperties = authProperties;
        this.authEventLogger = authEventLogger;
        this.identityLinkAuthentication = identityLinkAuthentication;
        this.clock = clock;
        this.appBaseUrl = stripTrailingSlash(webappBaseUrl);
        setAlwaysUseDefaultTargetUrl(false);
        setDefaultTargetUrl("/");
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            log.error("auth.success: unexpected authentication type {}", authentication.getClass());
            redirectToApp(request, response, "/auth/error?code=unexpected_auth_type");
            return;
        }
        OAuth2User principal = token.getPrincipal();
        String registrationId = token.getAuthorizedClientRegistrationId();
        String subject = principal.getName();
        if (subject == null || subject.isBlank()) {
            log.error("auth.success: principal has no subject (registrationId={})", registrationId);
            redirectToApp(request, response, "/auth/error?code=no_subject");
            return;
        }

        AuthIntentCookie.Intent intent = authIntentCookie.read(request);
        authIntentCookie.clear(response);

        // The IdP round-trip can outlive — or be replaced by — the session that authorized the linking,
        // so the authority to attach an identity is re-checked here rather than trusted from the cookie
        // that carried the intent out.
        if (intent != null && intent.mode() == AuthIntentCookie.Intent.Mode.LINK) {
            Long linkingAccountId;
            try {
                linkingAccountId = identityLinkAuthentication.resolveAuthenticatedAccountId(request);
            } catch (StepUpRequiredException e) {
                redirectToApp(request, response, "/auth/error?code=step_up_required");
                return;
            }
            if (linkingAccountId == null || !linkingAccountId.equals(intent.linkingAccountId())) {
                authEventLogger
                        .event(AuthEvent.EventType.IDENTITY_LINKED, AuthEvent.Result.FAILURE)
                        .account(intent.linkingAccountId())
                        .actingAccount(linkingAccountId)
                        .failureReason("link_requires_auth")
                        .record();
                redirectToApp(request, response, "/auth/error?code=link_requires_auth");
                return;
            }
        }

        AccountProvisioningService.ProvisionResult provisioned;
        try {
            provisioned = provisioningService.resolveOrProvision(registrationId, subject, principal, intent);
        } catch (LinkOnlyProviderLoginException e) {
            log.warn("auth.success: refused link-only provider login: {}", e.getMessage());
            redirectToApp(request, response, "/auth/error?code=link_requires_auth");
            return;
        } catch (AccountLinkConflictException e) {
            log.warn("auth.success: refused link because the identity is already linked to another account");
            redirectToApp(request, response, "/auth/error?code=identity_already_linked");
            return;
        }
        Account account = provisioned.account();

        // Authoritative account-status gate (ADR 0017). A SUSPENDED / DELETING / DELETED account must
        // never obtain a fresh JWT — otherwise a re-login silently resurrects a deleting account and a
        // suspended account is fully re-enabled. We bail out BEFORE minting the cookie. The decoder /
        // JwtPrincipalFactory enforce the same invariant as defense-in-depth, but this is where the
        // login decision is made, so it must be rejected here too. No cookie is set on this path.
        if (account.getStatus() != Account.Status.ACTIVE) {
            log.warn(
                    "auth.success: rejecting login for non-ACTIVE accountId={} status={}",
                    account.getId(),
                    account.getStatus());
            redirectToApp(request, response, "/auth/error?code=account_inactive");
            return;
        }

        // Attaching an identity to a session that is already signed in is not a sign-in: minting a token
        // here would reset both the absolute session ceiling and auth_time, so linking would become a way
        // to renew the very freshness the step-up gate reads.
        if (intent == null || intent.mode() != AuthIntentCookie.Intent.Mode.LINK) {
            Instant now = clock.instant();
            HephaestusJwtIssuer.Token issued = jwtIssuer.issue(
                    principalFactory.forAccount(account),
                    // Absolute session ceiling, stamped once at login and carried through every refresh,
                    // alongside the auth_time this login establishes.
                    TokenConstraints.session(now.plus(authProperties.sessionMaxLifetime()), now),
                    request);
            setAccessCookie(
                    response,
                    issued.value(),
                    issued.expiresAt().getEpochSecond() - clock.instant().getEpochSecond());
        }

        // Audit the completed authentication, symmetric with AuthSessionService's LOGOUT. IDENTITY_LINKED
        // only when a NEW identity was actually attached to an existing account (per the provisioning
        // result); otherwise LOGIN. Audit writes are best-effort and never break the login — see AuthEventLogger.
        authEventLogger
                .event(
                        provisioned.identityLinked() ? AuthEvent.EventType.IDENTITY_LINKED : AuthEvent.EventType.LOGIN,
                        AuthEvent.Result.SUCCESS)
                .account(account.getId())
                .record();

        String redirectTo = (intent != null) ? ReturnToValidator.safeOrFallback(intent.returnTo()) : "/";
        redirectToApp(request, response, redirectTo);
    }

    /**
     * Redirect to a SPA path, prefixing the app origin when it differs from the API origin (local
     * dev). {@code path} is always a server-validated, same-origin relative path (leading {@code /}),
     * so prefixing a trusted, configured origin cannot widen it into an open redirect.
     */
    private void redirectToApp(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        getRedirectStrategy().sendRedirect(request, response, appBaseUrl + path);
    }

    private void setAccessCookie(HttpServletResponse response, String jwt, long maxAgeSeconds) {
        Cookie cookie = new Cookie(authProperties.cookieName(), jwt);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) Math.max(0, maxAgeSeconds));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
