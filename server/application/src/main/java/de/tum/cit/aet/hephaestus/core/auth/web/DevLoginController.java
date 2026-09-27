package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.dev.DevLoginService;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.nativesession.NativeClientProperties;
import de.tum.cit.aet.hephaestus.core.auth.nativesession.NativeSignInRedirect;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Passwordless dev/test sign-in. Mints the production cookie-session for an arbitrary local
 * {@link de.tum.cit.aet.hephaestus.core.auth.domain.Account} without an OAuth IdP, so local dev and
 * live (Playwright) E2E can authenticate over real HTTP (which {@code @WithMockUser}/{@code TestAuthUtils}
 * cannot do). {@code @Hidden}: an operator/dev lever, not part of the public client surface.
 *
 * <p>Gated by {@code hephaestus.auth.dev-login-enabled} — disabled by default (404, invisible) and
 * fail-closed in production (see {@link DevLoginService}). Permitted in {@code SecurityConfig} only when
 * the flag is on.
 */
@Hidden
@ConditionalOnServerRole
@RestController
@RequestMapping("/auth")
// Public by design: access is gated by the dev-login flag + SecurityConfig (fail-closed in prod), not by
// roles. The explicit permitAll declaration satisfies the "every endpoint declares its security" arch rule
// (mirrors DevTriggerController).
@PreAuthorize("permitAll()")
public class DevLoginController {

    private final DevLoginService devLoginService;
    private final AuthSessionService authSessionService;
    private final NativeClientProperties nativeClientProperties;

    public DevLoginController(
            DevLoginService devLoginService,
            AuthSessionService authSessionService,
            NativeClientProperties nativeClientProperties) {
        this.devLoginService = devLoginService;
        this.authSessionService = authSessionService;
        this.nativeClientProperties = nativeClientProperties;
    }

    public record DevLoginRequestDTO(
            @NotBlank String username, @Nullable String displayName, boolean admin) {}

    @PostMapping("/dev-login")
    public ResponseEntity<Void> devLogin(
            @Valid @RequestBody DevLoginRequestDTO body, HttpServletRequest request, HttpServletResponse response) {
        HephaestusJwtIssuer.Token token =
                devLoginService.devLogin(body.username(), body.displayName(), body.admin(), request);
        authSessionService.setCookie(response, token);
        return ResponseEntity.noContent().build();
    }

    /**
     * The native app's dev sign-in, opened in the app's sign-in sheet exactly like a federated one: the
     * same redirect allowlist, the same S256 challenge, the same handoff redirect. Only the identity
     * provider round-trip is replaced by the dev account.
     */
    @GetMapping("/dev-login/native")
    public RedirectView devNativeLogin(
            @RequestParam("username") @NotBlank String username,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam("code_challenge_method") String codeChallengeMethod,
            @RequestParam("state") String state,
            @RequestParam("redirect_uri") String redirectUri) {
        if (!devLoginService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!nativeClientProperties.allowsRedirect(redirectUri)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri is not allowlisted");
        }
        if (!"S256".equals(codeChallengeMethod) || !NativeClientProperties.isPkceChallenge(codeChallenge)) {
            return new RedirectView(NativeSignInRedirect.error(redirectUri, "invalid_request", state), false);
        }
        String code = devLoginService.devNativeHandoff(username, codeChallenge);
        return new RedirectView(NativeSignInRedirect.success(redirectUri, code, state), false);
    }
}
