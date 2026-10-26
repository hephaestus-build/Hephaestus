package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in account's own preferences, as opposed to anything workspace-scoped. */
@ConditionalOnServerRole
@Validated
@RestController
@RequestMapping("/user")
@Tag(name = "Account", description = "User preferences")
@PreAuthorize("isAuthenticated()")
public class AccountController {

    private final AccountPreferencesService preferencesService;

    public AccountController(AccountPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping("/settings")
    @Operation(summary = "Get user settings", operationId = "getUserSettings")
    public ResponseEntity<UserSettingsDTO> getUserSettings() {
        return ResponseEntity.ok(preferencesService.getUserSettings(CurrentAccount.requireId()));
    }

    @PostMapping("/settings")
    @Operation(summary = "Update user settings", operationId = "updateUserSettings")
    public ResponseEntity<UserSettingsDTO> updateUserSettings(@Valid @RequestBody UpdateUserSettingsDTO settings) {
        return ResponseEntity.ok(preferencesService.updateUserSettings(CurrentAccount.requireId(), settings));
    }
}
