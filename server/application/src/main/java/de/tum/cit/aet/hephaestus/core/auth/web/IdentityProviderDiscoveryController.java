package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.dev.DevLoginService;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public discovery of the identity providers a user can sign in with. The SPA login
 * page renders one button per entry; each button targets
 * {@code /auth/login?provider={registrationId}}.
 *
 * <p>Lists every enabled instance-scoped {@code login_provider} (GitHub, GitLab.com, self-hosted
 * GitLab, and link-only Slack) — one shared registration per provider, reused across all workspaces.
 */
@ConditionalOnServerRole
@RestController
@Tag(name = "Auth discovery", description = "Identity provider discovery (public)")
public class IdentityProviderDiscoveryController {

    /** Synthetic registration id + provider type for the optional passwordless dev sign-in row. */
    private static final String DEV_REGISTRATION_ID = "dev";

    private static final String DEV_PROVIDER_TYPE = "DEV";

    private final LoginProviderService loginProviderService;
    private final DevLoginService devLoginService;

    public IdentityProviderDiscoveryController(
            LoginProviderService loginProviderService, DevLoginService devLoginService) {
        this.loginProviderService = loginProviderService;
        this.devLoginService = devLoginService;
    }

    /** A configured sign-in/linking option; baseUrl is the exact issuer for OIDC and the provider origin otherwise. */
    public record IdentityProviderViewDTO(
            String registrationId, String displayName, String providerType, String baseUrl) {}

    @GetMapping("/identity-providers")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List available identity providers", operationId = "listIdentityProviders")
    public ResponseEntity<List<IdentityProviderViewDTO>> list() {
        // Listing sign-in options must not fetch remote OIDC discovery. An unavailable provider must
        // not take the public picker (or the account-linking page) down with it.
        List<IdentityProviderViewDTO> views = new ArrayList<>();
        for (LoginProvider provider : loginProviderService.listEnabled()) {
            views.add(new IdentityProviderViewDTO(
                    provider.getRegistrationId(),
                    provider.getDisplayName(),
                    provider.getType().name(),
                    provider.getBaseUrl()));
        }
        // Optional passwordless dev sign-in. Advertised ONLY when enabled (never in prod), so the SPA
        // login page renders a "Dev sign-in" affordance (username field → POST /auth/dev-login) instead
        // of an OAuth redirect. The SPA routes on providerType === "DEV" / registrationId === "dev".
        if (devLoginService.isEnabled()) {
            views.add(new IdentityProviderViewDTO(DEV_REGISTRATION_ID, "Dev sign-in", DEV_PROVIDER_TYPE, ""));
        }
        return ResponseEntity.ok(views);
    }
}
