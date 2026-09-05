package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Entry point for the OAuth login dance — stamps the {@link AuthIntentCookie} with the
 * caller's intent (workspace + returnTo + mode) and 302s to Spring's standard
 * {@code /oauth2/authorization/{registrationId}} initiation endpoint.
 *
 * <p>This is the SPA's "Sign in with X" target. The intent cookie survives the round-trip
 * to the IdP and is read by the success handler to decide where to land the user post-login.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/auth")
public class AuthBeginController {

    private static final Logger log = LoggerFactory.getLogger(AuthBeginController.class);
    private static final String OAUTH_INIT_PATH = "/oauth2/authorization/";

    private final LoginProviderService loginProviderService;
    private final AuthIntentCookie authIntentCookie;
    private final IdentityLinkAuthentication identityLinkAuthentication;

    /** Proxy-stripped API prefix re-added to the init redirect — see {@code AuthProperties#apiBasePath}. */
    private final String apiBasePath;

    public AuthBeginController(
            LoginProviderService loginProviderService,
            AuthIntentCookie authIntentCookie,
            IdentityLinkAuthentication identityLinkAuthentication,
            AuthProperties authProperties) {
        this.loginProviderService = loginProviderService;
        this.authIntentCookie = authIntentCookie;
        this.identityLinkAuthentication = identityLinkAuthentication;
        this.apiBasePath = authProperties.apiBasePath();
    }

    @GetMapping("/login")
    @PreAuthorize("permitAll()")
    @Hidden
    @Operation(summary = "Begin OAuth login flow against the given registrationId")
    public RedirectView begin(
            @RequestParam("provider") String registrationId,
            @RequestParam(value = "workspace", required = false) @Nullable String workspaceSlug,
            @RequestParam(value = "returnTo", required = false) @Nullable String returnTo,
            @RequestParam(value = "mode", required = false, defaultValue = "login") String mode,
            HttpServletRequest request,
            HttpServletResponse response) {
        // The enabled login_provider row is the authority: it backs the ClientRegistration Spring will
        // resolve at the initiation endpoint, and its TYPE drives the link-only gate below.
        Optional<LoginProvider> provider = loginProviderService.findEnabled(registrationId);
        if (provider.isEmpty()) {
            log.warn("auth.begin: unknown provider={}", registrationId);
            return new RedirectView("/auth/error?code=unknown_provider", false);
        }
        String safeReturnTo = ReturnToValidator.safeOrFallback(returnTo);
        boolean linkMode = "link".equalsIgnoreCase(mode);
        // Link-only providers (Slack, Outline) can never begin a LOGIN — only the authenticated
        // account-linking flow may reach them. Classified by the row's TYPE, not by URL: Outline is
        // self-hosted, so there is no stable host to sniff. AccountProvisioningService re-checks this
        // at the callback (defence in depth).
        if (!linkMode && provider.get().getType().isLinkOnly()) {
            return new RedirectView("/auth/error?code=link_requires_auth", false);
        }
        AuthIntentCookie.Intent intent;
        if (linkMode) {
            // Link mode MUST be initiated by an already-authenticated user (secure account linking:
            // never auto-link to an unauthenticated context — that is the pre-account-takeover bug).
            // The resolved sub is bound into the sealed intent so AccountProvisioningService attaches
            // the new identity to THIS account.
            Long currentAccountId;
            try {
                currentAccountId = identityLinkAuthentication.resolveAuthenticatedAccountId(request);
            } catch (StepUpRequiredException e) {
                // The dance has not started yet, so there is nothing to resume: send the browser to the
                // SPA's confirmation copy instead of an OAuth redirect it would have to unwind.
                authIntentCookie.clear(response);
                return new RedirectView("/auth/error?code=" + StepUpRequiredException.CODE, false);
            }
            if (currentAccountId == null) {
                log.warn("auth.begin: link mode rejected — no valid session");
                return new RedirectView("/auth/error?code=link_requires_auth", false);
            }
            intent = AuthIntentCookie.Intent.link(currentAccountId, safeReturnTo);
        } else {
            intent = AuthIntentCookie.Intent.login(workspaceSlug, safeReturnTo);
        }
        authIntentCookie.write(response, intent);
        // 302 to Spring's standard initiation endpoint; the OAuth2AuthorizationRequestRedirectFilter
        // takes over from here, building the upstream redirect with state + PKCE (see AuthSecurityConfig).
        // apiBasePath re-adds the proxy-stripped prefix so the browser lands on the proxied init endpoint,
        // not the SPA. (The /auth/error targets above are SPA routes at the origin root, so they keep none.)
        String urlEncodedRegistration = URLEncoder.encode(registrationId, StandardCharsets.UTF_8);
        return new RedirectView(apiBasePath + OAUTH_INIT_PATH + urlEncodedRegistration, false);
    }
}
