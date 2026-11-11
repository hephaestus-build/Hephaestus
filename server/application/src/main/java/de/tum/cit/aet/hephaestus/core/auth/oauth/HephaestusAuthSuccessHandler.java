package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSessionService;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInRedirect;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.core.auth.webauthn.PasskeyRequiredException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

@ConditionalOnServerRole
@Component
public class HephaestusAuthSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(HephaestusAuthSuccessHandler.class);

    private final AccountProvisioningService provisioningService;
    private final HephaestusJwtIssuer jwtIssuer;
    private final AuthIntentCookie authIntentCookie;
    private final AuthProperties authProperties;
    private final AuthEventLogger authEventLogger;
    private final IdentityAuthentication identityLinkAuthentication;
    private final ClientSessionService clientSessionService;
    private final InstalledClientRegistry installedClients;
    private final Clock clock;
    private final CsrfAuthenticationStrategy csrfAuthentication;

    // OAuth redirects target the SPA origin, which can differ from the API origin in development.
    private final String appBaseUrl;

    public HephaestusAuthSuccessHandler(
            AccountProvisioningService provisioningService,
            HephaestusJwtIssuer jwtIssuer,
            AuthIntentCookie authIntentCookie,
            AuthProperties authProperties,
            AuthEventLogger authEventLogger,
            IdentityAuthentication identityLinkAuthentication,
            ClientSessionService clientSessionService,
            InstalledClientRegistry installedClients,
            Clock clock,
            CsrfTokenRepository csrfTokens,
            @Value("${hephaestus.webapp.url:}") String webappBaseUrl) {
        this.provisioningService = provisioningService;
        this.jwtIssuer = jwtIssuer;
        this.authIntentCookie = authIntentCookie;
        this.authProperties = authProperties;
        this.authEventLogger = authEventLogger;
        this.identityLinkAuthentication = identityLinkAuthentication;
        this.clientSessionService = clientSessionService;
        this.installedClients = installedClients;
        this.clock = clock;
        this.csrfAuthentication = new CsrfAuthenticationStrategy(csrfTokens);
        this.appBaseUrl = stripTrailingSlash(webappBaseUrl);
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
        AuthIntentCookie.Intent intent = authIntentCookie.read(request);
        authIntentCookie.clear(response);
        ClientCallback client = ClientCallback.of(intent, installedClients);

        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            log.error("auth.success: unexpected authentication type {}", authentication.getClass());
            refuse(request, response, client, "unexpected_auth_type");
            return;
        }
        OAuth2User principal = token.getPrincipal();
        String registrationId = token.getAuthorizedClientRegistrationId();
        String subject = principal.getName();
        if (subject == null || subject.isBlank()) {
            log.error("auth.success: principal has no subject (registrationId={})", registrationId);
            refuse(request, response, client, "no_subject");
            return;
        }
        if (client instanceof ClientCallback.Unregistered) {
            log.warn("auth.success: installed-client sign-in whose client is no longer registered");
            redirectToApp(request, response, "/auth/error?code=client_not_registered");
            return;
        }

        // The IdP round-trip can outlive — or be replaced by — the session that authorized the
        // linking,
        // so the authority to attach an identity is re-checked here rather than trusted from the
        // cookie
        // that carried the intent out.
        if (intent != null && intent.mode() == AuthIntentCookie.Intent.Mode.LINK) {
            Long linkingAccountId;
            try {
                linkingAccountId = identityLinkAuthentication.resolveAuthenticatedAccountId(request);
            } catch (PasskeyRequiredException e) {
                redirectToApp(request, response, "/settings#passkeys");
                return;
            } catch (StepUpRequiredException e) {
                // The intent is already cleared: recovery signs in again and starts a new, explicit
                // link.
                redirectToApp(request, response, AuthBeginController.stepUpRequiredPath(intent.returnTo()));
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
            log.warn("auth.success: refused link-only provider login: registrationId={}", registrationId);
            refuse(request, response, client, "link_requires_auth");
            return;
        } catch (AccountLinkConflictException e) {
            log.warn("auth.success: refused link because the identity is already linked to another" + " account");
            refuse(request, response, client, "identity_already_linked");
            return;
        }
        Account account = provisioned.account();

        // Authoritative account-status gate (ADR 0017). A BLOCKED / DELETING / DELETED account must
        // never obtain a fresh JWT — otherwise a re-login silently resurrects a deleting account and
        // undoes an admin's block. We bail out BEFORE minting the cookie. The decoder
        // /
        // JwtPrincipalFactory enforce the same invariant as defense-in-depth, but this is where the
        // login decision is made, so it must be rejected here too. No cookie is set on this path.
        if (account.getStatus() != Account.Status.ACTIVE) {
            log.warn(
                    "auth.success: rejecting login for non-ACTIVE accountId={} status={}",
                    account.getId(),
                    account.getStatus());
            refuse(request, response, client, "account_inactive");
            return;
        }

        // An installed-client sign-in ends in a single-use code bound to the client's PKCE
        // challenge,
        // redeemed by the client itself. No cookie is set: the window that ran the sign-in never
        // holds a
        // Hephaestus session.
        if (client instanceof ClientCallback.Registered registered) {
            Instant now = clock.instant();
            Optional<String> code = clientSessionService.createHandoff(
                    Objects.requireNonNull(account.getId()),
                    registered.client(),
                    registered.request().codeChallenge(),
                    now.plus(authProperties.sessionMaxLifetime()),
                    now);
            if (code.isEmpty()) {
                refuse(request, response, client, "account_inactive");
                return;
            }
            authEventLogger
                    .event(
                            provisioned.identityLinked()
                                    ? AuthEvent.EventType.IDENTITY_LINKED
                                    : AuthEvent.EventType.LOGIN,
                            AuthEvent.Result.SUCCESS)
                    .account(account.getId())
                    .record();
            getRedirectStrategy()
                    .sendRedirect(
                            request,
                            response,
                            ClientSignInRedirect.success(
                                    registered.client().redirectUri(),
                                    code.get(),
                                    registered.request().state()));
            return;
        }

        // Attaching an identity to a session that is already signed in is not a sign-in: minting a
        // token
        // here would reset both the absolute session ceiling and auth_time, so linking would become
        // a way
        // to renew the very freshness the step-up gate reads.
        if (intent == null || intent.mode() != AuthIntentCookie.Intent.Mode.LINK) {
            Instant now = clock.instant();
            HephaestusJwtIssuer.Token issued = jwtIssuer.issue(
                    Objects.requireNonNull(account.getId()),
                    // Absolute session ceiling, stamped once at login and carried through
                    // every refresh,
                    // alongside the auth_time this login establishes.
                    TokenConstraints.session(now.plus(authProperties.sessionMaxLifetime()), now),
                    request);
            setAccessCookie(
                    response,
                    issued.value(),
                    issued.expiresAt().getEpochSecond() - clock.instant().getEpochSecond());
            csrfAuthentication.onAuthentication(authentication, request, response);
        }

        // Audit the completed authentication, symmetric with AuthSessionService's LOGOUT.
        // IDENTITY_LINKED
        // only when a NEW identity was actually attached to an existing account (per the
        // provisioning
        // result); otherwise LOGIN. Audit writes are best-effort and never break the login — see
        // AuthEventLogger.
        authEventLogger
                .event(
                        provisioned.identityLinked() ? AuthEvent.EventType.IDENTITY_LINKED : AuthEvent.EventType.LOGIN,
                        AuthEvent.Result.SUCCESS)
                .account(account.getId())
                .record();

        String redirectTo = (intent != null) ? ReturnToValidator.safeOrFallback(intent.returnTo()) : "/";
        redirectToApp(request, response, redirectTo);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, ClientCallback client, String code)
            throws IOException {
        if (client instanceof ClientCallback.Registered registered) {
            getRedirectStrategy()
                    .sendRedirect(
                            request,
                            response,
                            ClientSignInRedirect.error(
                                    registered.client().redirectUri(),
                                    code,
                                    registered.request().state()));
            return;
        }
        redirectToApp(request, response, "/auth/error?code=" + code);
    }

    // Only server-validated relative paths may be prefixed with the trusted app origin.
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
