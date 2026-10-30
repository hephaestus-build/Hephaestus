package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.consent.ConsentService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.webauthn.PasskeyAssurancePolicy;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnServerRole
@RestController
@RequestMapping("/user")
@Tag(name = "Account", description = "Current user identity, linked accounts, deletion")
@PreAuthorize("isAuthenticated()")
public class AccountWebController {

    private final AccountService accountService;
    private final ConsentService consentService;
    private final PasskeyAssurancePolicy passkeys;
    private final GitProviderRegistry gitProviderRegistry;

    public AccountWebController(
            AccountService accountService,
            ConsentService consentService,
            GitProviderRegistry gitProviderRegistry,
            PasskeyAssurancePolicy passkeys) {
        this.accountService = accountService;
        this.consentService = consentService;
        this.passkeys = passkeys;
        this.gitProviderRegistry = gitProviderRegistry;
    }

    public record CurrentUserViewDTO(
            Long id,
            String displayName,
            @Nullable String primaryEmail,
            String appRole,
            String status,
            // Identity fields the SPA's useAuth() surface needs (sourced from the primary
            // Identity).
            @Nullable String username,
            @Nullable String avatarUrl,
            @Nullable String profileUrl,
            @Nullable String identityProvider,
            @Nullable String gitProviderId,
            // Every SCM instance the user has an active identity on, so the workspace-creation
            // wizard can
            // gate on the *target instance* rather than merely "has any GitLab identity".
            List<LinkedProviderDTO> linkedProviders,
            List<String> roles,
            // Access-token expiry (epoch seconds) so the SPA can schedule a proactive refresh
            // before it
            // lapses, rather than waiting for a 401 (BFF pattern; see
            // CurrentAccount.accessTokenExpiresAt).
            @Nullable Long accessTokenExpiresAt,

            @Schema(
                    description =
                            "The account's consent, so that a client decides the consent gate before it sends a read the gate refuses",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            ConsentService.ConsentStatusDTO consent) {}

    /** A provider instance the current user is linked to: its type + server-url origin. */
    public record LinkedProviderDTO(String type, @Nullable String serverUrl) {}

    public record IdentityViewDTO(
            @NonNull Long id,
            @NonNull String providerType,
            @NonNull String subject,
            @Nullable String username,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable Instant lastLoginAt) {}

    @GetMapping
    @Operation(summary = "Get the current user", operationId = "getCurrentUser")
    public ResponseEntity<CurrentUserViewDTO> currentUser() {
        Account account = accountService.requireById(CurrentAccount.requireId());
        var identities = accountService.activeIdentities(Objects.requireNonNull(account.getId()));
        // Primary identity = most recently used active link (login source for the SPA).
        Identity primary = identities.stream().findFirst().orElse(null);
        var profile = primary == null
                ? null
                : gitProviderRegistry.profiles(List.of(key(primary))).get(key(primary));
        List<LinkedProviderDTO> linkedProviders = identities.stream()
                .map(il -> new LinkedProviderDTO(
                        gitProviderRegistry.providerTypeName(il.getProviderId()),
                        gitProviderRegistry.providerServerUrl(il.getProviderId())))
                .distinct()
                .toList();
        return ResponseEntity.ok(new CurrentUserViewDTO(
                Objects.requireNonNull(account.getId()),
                account.getDisplayName(),
                account.getPrimaryEmail(),
                account.getAppRole().name(),
                account.getStatus().name(),
                profile != null ? profile.login() : null,
                profile != null ? profile.avatarUrl() : null,
                profile != null ? profile.profileUrl() : null,
                primary != null ? gitProviderRegistry.providerTypeName(primary.getProviderId()) : null,
                primary != null ? primary.getProviderUserKey() : null,
                linkedProviders,
                CurrentAccount.roles(),
                CurrentAccount.accessTokenExpiresAt(),
                consentService.status(Objects.requireNonNull(account.getId()))));
    }

    @GetMapping("/identities")
    @Operation(summary = "List linked identity providers", operationId = "listLinkedIdentities")
    public ResponseEntity<List<IdentityViewDTO>> identities() {
        var identities = accountService.activeIdentities(CurrentAccount.requireId());
        var profiles = gitProviderRegistry.profiles(
                identities.stream().map(AccountWebController::key).toList());
        List<IdentityViewDTO> views = identities.stream()
                .map(identity -> toView(identity, profiles.get(key(identity))))
                .toList();
        return ResponseEntity.ok(views);
    }

    @DeleteMapping("/identities/{id}")
    @Operation(summary = "Unlink one of the current user's linked identity providers", operationId = "unlinkIdentity")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Identity unlinked"),
        @ApiResponse(responseCode = "404", description = "No such identity on the current account"),
        @ApiResponse(responseCode = "409", description = "Cannot unlink the account's only remaining sign-in method"),
    })
    public ResponseEntity<Void> unlinkIdentity(@PathVariable Long id) {
        passkeys.requirePersonal(SecurityContextHolder.getContext().getAuthentication());
        accountService.unlinkIdentity(CurrentAccount.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Delete the current account (GDPR Art. 17)", operationId = "deleteCurrentUser")
    public ResponseEntity<Void> deleteAccount(
            @RequestHeader(value = "X-Confirm-Delete", required = false) @Nullable String confirmHeader) {
        Long accountId = CurrentAccount.requireId();
        if (confirmHeader == null || !confirmHeader.equals(String.valueOf(accountId))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "To confirm deletion, set the X-Confirm-Delete header to your account ID.");
        }
        accountService.softDelete(accountId);
        return ResponseEntity.noContent().build();
    }

    private static GitProviderRegistry.IdentityKey key(Identity identity) {
        return new GitProviderRegistry.IdentityKey(
                identity.getProviderId(), identity.getProviderUserKey(), identity.getTeamKey());
    }

    private IdentityViewDTO toView(Identity il, GitProviderRegistry.@Nullable IdentityProfile profile) {
        String providerType = gitProviderRegistry.providerTypeName(il.getProviderId());
        return new IdentityViewDTO(
                il.getId(),
                providerType,
                il.getProviderUserKey(),
                profile == null ? null : profile.login(),
                profile == null ? null : profile.displayName(),
                profile == null ? null : profile.avatarUrl(),
                il.getLastLoginAt());
    }
}
