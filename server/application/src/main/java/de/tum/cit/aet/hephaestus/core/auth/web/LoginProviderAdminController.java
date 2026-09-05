package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Instance-admin CRUD for {@code login_provider} rows — the sign-in options offered on the login page
 * (GitHub, GitLab.com, self-hosted GitLab). One row per SCM instance; the {@code (type, baseUrl)}
 * uniqueness is enforced at the DB layer, so a duplicate surfaces as a 409.
 *
 * <p>The client secret is write-only: it is sealed at rest and never returned. Each view carries the
 * {@code redirectUri} the admin must register on the upstream OAuth app — the single most error-prone
 * step when wiring a self-hosted GitLab.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/admin/login-providers")
@Tag(name = "Admin", description = "Instance-admin login provider management")
@PreAuthorize("hasAuthority('app_admin')")
public class LoginProviderAdminController {

    private final LoginProviderService loginProviderService;

    /** Proxy-stripped API prefix re-added to the displayed callback URL — see {@code AuthProperties#apiBasePath}. */
    private final String apiBasePath;

    public LoginProviderAdminController(LoginProviderService loginProviderService, AuthProperties authProperties) {
        this.loginProviderService = loginProviderService;
        this.apiBasePath = authProperties.apiBasePath();
    }

    /**
     * Public callback base the admin registers on the upstream OAuth app: the request origin (scheme +
     * host, restored by native forward-headers) plus the proxy-stripped API prefix. The per-provider
     * {@code /login/oauth2/code/{id}} segment is appended in {@link #toView}.
     */
    private String callbackBase() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + apiBasePath;
    }

    @GetMapping
    @Operation(summary = "List login providers", operationId = "adminListLoginProviders")
    public ResponseEntity<List<LoginProviderViewDTO>> list() {
        String callbackBase = callbackBase();
        return ResponseEntity.ok(loginProviderService.listAll().stream()
                .map(p -> toView(p, callbackBase))
                .toList());
    }

    @PostMapping
    @Operation(summary = "Create a login provider", operationId = "adminCreateLoginProvider")
    @ApiResponse(responseCode = "201", description = "Login provider created; URL in the Location header")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LOGIN_PROVIDER_CREATED")
    public ResponseEntity<LoginProviderViewDTO> create(@Valid @RequestBody CreateLoginProviderRequestDTO body) {
        LoginProvider created = loginProviderService.create(new LoginProviderService.Draft(
                body.registrationId(),
                body.type(),
                body.displayName(),
                body.baseUrl(),
                body.clientId(),
                body.clientSecret(),
                body.scopes()));
        LoginProviderViewDTO view = toView(created, callbackBase());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{registrationId}")
                .buildAndExpand(created.getRegistrationId())
                .toUri();
        return ResponseEntity.created(location).body(view);
    }

    @PatchMapping("/{registrationId}")
    @Operation(summary = "Update a login provider", operationId = "adminUpdateLoginProvider")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LOGIN_PROVIDER_UPDATED")
    public ResponseEntity<LoginProviderViewDTO> update(
            @PathVariable String registrationId, @Valid @RequestBody UpdateLoginProviderRequestDTO body) {
        LoginProvider updated = loginProviderService.update(
                registrationId,
                new LoginProviderService.Patch(
                        body.displayName(),
                        body.baseUrl(),
                        body.clientId(),
                        body.clientSecret(),
                        body.scopes(),
                        body.enabled()));
        return ResponseEntity.ok(toView(updated, callbackBase()));
    }

    @DeleteMapping("/{registrationId}")
    @Operation(summary = "Delete a login provider", operationId = "adminDeleteLoginProvider")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LOGIN_PROVIDER_DELETED")
    public ResponseEntity<Void> delete(@PathVariable String registrationId) {
        loginProviderService.delete(registrationId);
        return ResponseEntity.noContent().build();
    }

    private static LoginProviderViewDTO toView(LoginProvider p, String callbackBase) {
        return new LoginProviderViewDTO(
                p.getRegistrationId(),
                p.getType().name(),
                p.getDisplayName(),
                p.getBaseUrl(),
                p.getScopes(),
                p.isEnabled(),
                p.isSeededFromEnv(),
                callbackBase + "/login/oauth2/code/" + p.getRegistrationId(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }

    /** Admin view of a login provider. The client secret is never included. */
    public record LoginProviderViewDTO(
            @NonNull String registrationId,
            @NonNull String type,
            @NonNull String displayName,
            @NonNull String baseUrl,
            @NonNull String scopes,
            boolean enabled,
            boolean seededFromEnv,

            @NonNull @Schema(description = "Redirect/callback URI to register on the upstream OAuth app")
            String redirectUri,

            @NonNull Instant createdAt,
            @NonNull Instant updatedAt) {}

    public record CreateLoginProviderRequestDTO(
            @NotBlank
            @Schema(
                    description = "Stable id used in the OAuth callback path and identity resolution",
                    example = "gitlab-acme")
            String registrationId,

            @NotNull LoginProvider.ProviderType type,

            @Nullable @Schema(description = "Label shown on the login button; defaults to the registrationId")
            String displayName,

            @Nullable
            @Schema(
                    description = "Instance base URL (GitLab only; GitHub is always github.com)",
                    example = "https://gitlab.example.com")
            String baseUrl,

            @NotBlank String clientId,

            @NotBlank @Schema(description = "OAuth client secret; sealed at rest, never returned")
            String clientSecret,

            @Nullable @Schema(description = "Space-separated scopes; defaulted by provider type if omitted")
            String scopes) {}

    public record UpdateLoginProviderRequestDTO(
            @Nullable String displayName,
            @Nullable String baseUrl,
            @Nullable String clientId,

            @Nullable @Schema(description = "Omit to leave the existing secret unchanged")
            String clientSecret,

            @Nullable String scopes,
            @Nullable Boolean enabled) {}
}
