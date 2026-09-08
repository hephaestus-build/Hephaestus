package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The current-user singleton: identity, linked accounts, and GDPR account deletion.
 * Thin HTTP adapter — all data access lives in {@link AccountService}.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/user")
@Tag(name = "Account", description = "Current user identity, linked accounts, deletion")
@PreAuthorize("isAuthenticated()")
public class AccountWebController {

    private final AccountService accountService;
    private final GitProviderRegistry gitProviderRegistry;

    public AccountWebController(AccountService accountService, GitProviderRegistry gitProviderRegistry) {
        this.accountService = accountService;
        this.gitProviderRegistry = gitProviderRegistry;
    }

    public record CurrentUserViewDTO(
            Long id,
            String displayName,
            @Nullable String primaryEmail,
            String appRole,
            String status,
            boolean impersonating,
            @Nullable Long impersonatorId,
            // Identity fields the SPA's useAuth() surface needs (sourced from the primary IdentityLink).
            @Nullable String username,
            @Nullable String avatarUrl,
            @Nullable String profileUrl,
            @Nullable String identityProvider,
            @Nullable String gitProviderId,
            boolean hasGitLabIdentity,
            // Every SCM instance the user has an active identity on, so the workspace-creation wizard can
            // gate on the *target instance* rather than merely "has any GitLab identity".
            List<LinkedProviderDTO> linkedProviders,
            List<String> roles,
            // Access-token expiry (epoch seconds) so the SPA can schedule a proactive refresh before it
            // lapses, rather than waiting for a 401 (BFF pattern; see CurrentAccount.accessTokenExpiresAt).
            @Nullable Long accessTokenExpiresAt) {}

    /** A provider instance the current user is linked to: its type + server-url origin. */
    public record LinkedProviderDTO(String type, @Nullable String serverUrl) {}

    public record IdentityViewDTO(
            Long id,
            String providerType,
            @Nullable String serverUrl,
            String subject,
            @Nullable String username,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable Instant lastLoginAt) {}

    @GetMapping
    @Operation(summary = "Get the current user", operationId = "getCurrentUser")
    public ResponseEntity<CurrentUserViewDTO> currentUser() {
        Account account = accountService.requireById(CurrentAccount.requireId());
        Long impersonatorId = CurrentAccount.impersonatorId();
        var identities = accountService.activeIdentities(Objects.requireNonNull(account.getId()));
        // Primary identity = most recently used active link (login source for the SPA).
        IdentityLink primary = identities.stream().findFirst().orElse(null);
        List<LinkedProviderDTO> linkedProviders = identities.stream()
                .map(il -> new LinkedProviderDTO(
                        gitProviderRegistry.providerTypeName(il.getProviderId()),
                        gitProviderRegistry.providerServerUrl(il.getProviderId())))
                .distinct()
                .toList();
        boolean hasGitLab = linkedProviders.stream().anyMatch(p -> "GITLAB".equals(p.type()));
        return ResponseEntity.ok(new CurrentUserViewDTO(
                Objects.requireNonNull(account.getId()),
                account.getDisplayName(),
                account.getPrimaryEmail(),
                account.getAppRole().name(),
                account.getStatus().name(),
                impersonatorId != null,
                impersonatorId,
                primary != null ? primary.getUsernameAtSignup() : account.getDisplayName(),
                primary != null ? primary.getAvatarUrl() : null,
                primary != null ? primary.getProfileUrl() : null,
                primary != null ? gitProviderRegistry.providerTypeName(primary.getProviderId()) : null,
                primary != null ? primary.getSubject() : null,
                hasGitLab,
                linkedProviders,
                CurrentAccount.roles(),
                CurrentAccount.accessTokenExpiresAt()));
    }

    @GetMapping("/identities")
    @Operation(summary = "List linked identity providers", operationId = "listLinkedIdentities")
    public ResponseEntity<List<IdentityViewDTO>> identities() {
        List<IdentityViewDTO> views = accountService.activeIdentities(CurrentAccount.requireId()).stream()
                .map(this::toView)
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
        accountService.unlinkIdentity(CurrentAccount.requireId(), id, CurrentAccount.impersonatorId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Delete the current account (GDPR Art. 17)", operationId = "deleteCurrentUser")
    public ResponseEntity<Void> deleteAccount(
            @RequestHeader(value = "X-Confirm-Delete", required = false) @Nullable String confirmHeader) {
        Long accountId = CurrentAccount.requireId();
        if (confirmHeader == null || !confirmHeader.equals(String.valueOf(accountId))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Confirm-Delete header must equal your account id to confirm deletion");
        }
        accountService.softDelete(accountId, CurrentAccount.impersonatorId());
        return ResponseEntity.noContent().build();
    }

    private IdentityViewDTO toView(IdentityLink il) {
        String providerType = gitProviderRegistry.providerTypeName(il.getProviderId());
        return new IdentityViewDTO(
                il.getId(),
                providerType,
                gitProviderRegistry.providerServerUrl(il.getProviderId()),
                il.getSubject(),
                il.getUsernameAtSignup(),
                il.getDisplayName(),
                il.getAvatarUrl(),
                il.getLastLoginAt());
    }
}
