package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.integration.core.connection.identity.AuthenticatedGitProviderUserService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in account's own preferences, as opposed to anything workspace-scoped. */
@Validated
@RestController
@RequestMapping("/user")
@Tag(name = "Account", description = "User preferences")
@PreAuthorize("isAuthenticated()")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountPreferencesService preferencesService;
    private final AuthenticatedGitProviderUserService authenticatedGitProviderUserService;

    public AccountController(
            AccountPreferencesService preferencesService,
            AuthenticatedGitProviderUserService authenticatedGitProviderUserService) {
        this.preferencesService = preferencesService;
        this.authenticatedGitProviderUserService = authenticatedGitProviderUserService;
    }

    @GetMapping("/settings")
    @Operation(summary = "Get user settings", operationId = "getUserSettings")
    public ResponseEntity<UserSettingsDTO> getUserSettings(@AuthenticationPrincipal JwtAuthenticationToken auth) {
        return resolveOrProvisionCurrentUser(auth)
                .map(value -> ResponseEntity.ok(preferencesService.getUserSettings(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/settings")
    @Operation(summary = "Update user settings", operationId = "updateUserSettings")
    public ResponseEntity<UserSettingsDTO> updateUserSettings(
            @AuthenticationPrincipal JwtAuthenticationToken auth, @Valid @RequestBody UserSettingsDTO userSettings) {
        var user = resolveOrProvisionCurrentUser(auth);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(preferencesService.updateUserSettings(user.get(), userSettings));
    }

    private @Nullable JwtAuthenticationToken resolveAuthentication(@Nullable JwtAuthenticationToken injectedToken) {
        if (injectedToken != null) {
            return injectedToken;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwt ? jwt : null;
    }

    private Optional<User> resolveOrProvisionCurrentUser(JwtAuthenticationToken auth) {
        if (resolveAuthentication(auth) == null) {
            return Optional.empty();
        }
        return authenticatedGitProviderUserService.resolveOrProvisionCurrentUser();
    }
}
