package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInParameters;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInRedirect;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInStart;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.core.auth.dev.DevLoginService;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
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
    private final InstalledClientRegistry installedClients;

    public DevLoginController(
            DevLoginService devLoginService,
            AuthSessionService authSessionService,
            InstalledClientRegistry installedClients) {
        this.devLoginService = devLoginService;
        this.authSessionService = authSessionService;
        this.installedClients = installedClients;
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
     * An installed client's dev sign-in, opened in the client's auth window exactly like a federated one:
     * the same registered client and callback, the same S256 challenge, the same handoff redirect. Only
     * the identity-provider round trip is replaced by the dev account.
     */
    @GetMapping("/dev-login/client")
    public RedirectView devClientLogin(
            @RequestParam("username") String username,
            @RequestParam(value = "admin", defaultValue = "false") boolean admin,
            ClientSignInParameters parameters) {
        if (!devLoginService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username is required");
        }
        ClientSignInStart start = ClientSignInStart.decide(installedClients, parameters);
        return switch (start) {
            case ClientSignInStart.Unregistered ignored ->
                new RedirectView("/auth/error?code=client_not_registered", false);
            case ClientSignInStart.Refused refused -> new RedirectView(refused.redirect(), false);
            case ClientSignInStart.Accepted accepted -> {
                String callback = accepted.client().redirectUri();
                yield devLoginService
                        .devClientHandoff(username, admin, accepted.client(), accepted.codeChallenge())
                        .map(code ->
                                new RedirectView(ClientSignInRedirect.success(callback, code, accepted.state()), false))
                        .orElseGet(() -> new RedirectView(
                                ClientSignInRedirect.error(callback, "account_inactive", accepted.state()), false));
            }
        };
    }
}
