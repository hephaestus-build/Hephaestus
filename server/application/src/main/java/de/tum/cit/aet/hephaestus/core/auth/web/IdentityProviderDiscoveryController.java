package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.dev.DevLoginService;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider.ProviderType;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityProviderCatalog;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
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

    private final IdentityProviderCatalog identityProviderCatalog;
    private final LoginProviderService loginProviderService;
    private final DevLoginService devLoginService;

    public IdentityProviderDiscoveryController(
            IdentityProviderCatalog identityProviderCatalog,
            LoginProviderService loginProviderService,
            DevLoginService devLoginService) {
        this.identityProviderCatalog = identityProviderCatalog;
        this.loginProviderService = loginProviderService;
        this.devLoginService = devLoginService;
    }

    /**
     * One row per sign-in option. {@code providerType} drives the SPA's icon choice; {@code baseUrl} is
     * the OAuth instance origin (scheme + host[:port]) of the authorization endpoint, so the
     * workspace-creation wizard can match a target instance to its login.
     *
     * <p><b>baseUrl is the OAuth origin, not the SCM API origin.</b> It is only meaningful for GitLab rows,
     * where the OAuth origin and the API origin coincide (e.g. {@code https://gitlab.example.com}). For a
     * GitHub row it is {@code https://github.com} (the OAuth host), NOT {@code https://api.github.com}; the
     * sole consumer (the workspace wizard) matches GitLab self-hosted origins and never relies on the GitHub
     * value, so the discrepancy is harmless. Do not treat this as an SCM API base URL.
     */
    public record IdentityProviderViewDTO(
            String registrationId, String displayName, String providerType, String baseUrl) {}

    @GetMapping("/identity-providers")
    @PreAuthorize("permitAll()")
    @Operation(summary = "List available identity providers", operationId = "listIdentityProviders")
    public ResponseEntity<List<IdentityProviderViewDTO>> list() {
        Map<String, LoginProvider.ProviderType> typesByRegistrationId = loginProviderService.listEnabled().stream()
                .collect(Collectors.toMap(LoginProvider::getRegistrationId, LoginProvider::getType, (a, b) -> a));
        List<IdentityProviderViewDTO> views = new ArrayList<>();
        for (ClientRegistration reg : identityProviderCatalog.listRegistrations()) {
            views.add(new IdentityProviderViewDTO(
                    reg.getRegistrationId(),
                    reg.getClientName() != null ? reg.getClientName() : reg.getRegistrationId(),
                    providerTypeOf(reg, typesByRegistrationId::get),
                    baseUrlOf(reg)));
        }
        // Optional passwordless dev sign-in. Advertised ONLY when enabled (never in prod), so the SPA
        // login page renders a "Dev sign-in" affordance (username field → POST /auth/dev-login) instead
        // of an OAuth redirect. The SPA routes on providerType === "DEV" / registrationId === "dev".
        if (devLoginService.isEnabled()) {
            views.add(new IdentityProviderViewDTO(DEV_REGISTRATION_ID, "Dev sign-in", DEV_PROVIDER_TYPE, ""));
        }
        return ResponseEntity.ok(views);
    }

    /**
     * The provider type drives the SPA's icon choice and link-only filtering. Prefers the
     * {@code login_provider} row's type (authoritative — an OUTLINE registration's authorization host is
     * an arbitrary self-hosted instance, indistinguishable from a GitLab by URL shape); falls back to
     * the legacy host sniff when no row resolves.
     */
    static String providerTypeOf(
            ClientRegistration reg, Function<String, @Nullable ProviderType> typeByRegistrationId) {
        LoginProvider.ProviderType rowType = typeByRegistrationId.apply(reg.getRegistrationId());
        if (rowType != null) {
            return rowType.name();
        }
        return providerTypeOf(reg);
    }

    /** Legacy host-sniff fallback for a registration without a {@code login_provider} row. */
    static String providerTypeOf(ClientRegistration reg) {
        // Match on the parsed HOST, not a substring of the whole URI — "github.com" appearing in a
        // path/query of a GitLab instance (or a look-alike host) must not be misclassified as GitHub.
        String host = hostOf(reg);
        if ("github.com".equals(host)) {
            return "GITHUB";
        }
        if ("slack.com".equals(host)) {
            return "SLACK";
        }
        return "GITLAB";
    }

    /** Host of the authorization endpoint, or {@code null} if absent/malformed. */
    static @Nullable String hostOf(ClientRegistration reg) {
        String authorizationUri = reg.getProviderDetails().getAuthorizationUri();
        if (authorizationUri == null) {
            return null;
        }
        try {
            return new java.net.URI(authorizationUri).getHost();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** The {@link ScmOrigin} of the authorization endpoint, so clients compare instances as plain strings. */
    static String baseUrlOf(ClientRegistration reg) {
        return ScmOrigin.of(reg.getProviderDetails().getAuthorizationUri()).orElse("");
    }
}
