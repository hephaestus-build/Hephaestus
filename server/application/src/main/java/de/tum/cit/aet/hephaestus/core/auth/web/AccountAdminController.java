package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance-admin account management, guarded by the namespaced {@code app_admin} authority (the
 * granted authority the issuer mints for {@code APP_ADMIN}; see {@code JwtPrincipalFactory}). Thin
 * adapter over {@link AccountService}. Access JWTs are short-lived (~15m) and refresh re-derives
 * roles from the DB, so no legacy-authority grace is carried in code.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/admin/users")
@Tag(name = "Admin", description = "Instance-admin account management")
@PreAuthorize("hasAuthority('app_admin')")
public class AccountAdminController {

    private final AccountService accountService;

    public AccountAdminController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record AdminAccountViewDTO(
            Long id, String displayName, @Nullable String primaryEmail, String appRole, String status) {}

    public record UpdateAccountRequestDTO(@Nullable String appRole) {}

    public record RevokeSessionsResultDTO(int revoked) {}

    @GetMapping
    @Operation(summary = "List accounts (paged)", operationId = "adminListUsers")
    public ResponseEntity<List<AdminAccountViewDTO>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        List<AdminAccountViewDTO> views = accountService.adminList(page, size).stream()
                .map(AccountAdminController::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update an account's app role", operationId = "adminUpdateUser")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "APP_ROLE_CHANGED")
    public ResponseEntity<AdminAccountViewDTO> update(
            @PathVariable Long id, @Valid @RequestBody UpdateAccountRequestDTO body) {
        Account account = accountService.adminSetRole(id, body.appRole(), CurrentAccount.requireId());
        return ResponseEntity.ok(toView(account));
    }

    @DeleteMapping("/{id}/sessions")
    @Operation(
            summary = "Force sign-out: revoke all of an account's active sessions",
            operationId = "adminRevokeUserSessions")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "JWT_REVOKED")
    public ResponseEntity<RevokeSessionsResultDTO> revokeSessions(@PathVariable Long id) {
        int revoked = accountService.adminRevokeAllSessions(id, CurrentAccount.requireId());
        return ResponseEntity.ok(new RevokeSessionsResultDTO(revoked));
    }

    private static AdminAccountViewDTO toView(Account a) {
        return new AdminAccountViewDTO(
                Objects.requireNonNull(a.getId()),
                a.getDisplayName(),
                a.getPrimaryEmail(),
                a.getAppRole().name(),
                a.getStatus().name());
    }
}
