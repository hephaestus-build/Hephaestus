package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.impersonation.ImpersonationService;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Session-lifecycle verbs: logout, refresh, impersonate, impersonate:exit. Strictly
 * session control — identity reads live on {@code /user}. Cookie + JWT mechanics are
 * delegated to {@link AuthSessionService} so this controller stays thin.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Session lifecycle")
public class AuthLifecycleController {

    private final AuthSessionService sessionService;
    private final ImpersonationService impersonationService;

    public AuthLifecycleController(AuthSessionService sessionService, ImpersonationService impersonationService) {
        this.sessionService = sessionService;
        this.impersonationService = impersonationService;
    }

    public record ImpersonateRequestDTO(
            @NotNull Long targetAccountId, @NotBlank String reason) {}

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Log out — revoke the current token + clear the cookie", operationId = "logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        sessionService.logout(CurrentAccount.requireId(), CurrentAccount.requireJti(), response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Rotate the access token (new jti, old revoked)", operationId = "refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        sessionService.refresh(
                CurrentAccount.requireId(),
                CurrentAccount.requireJti(),
                new TokenConstraints(
                        CurrentAccount.impersonatorId(),
                        CurrentAccount.impersonationExpiresAt(),
                        CurrentAccount.sessionExpiresAt(),
                        CurrentAccount.authTime()),
                request,
                response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/impersonate")
    @PreAuthorize("hasAuthority('app_admin')")
    @Operation(summary = "Begin impersonating another account", operationId = "impersonate")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "IMPERSONATION_BEGIN")
    public ResponseEntity<Void> impersonate(
            @Valid @RequestBody ImpersonateRequestDTO body, HttpServletRequest request, HttpServletResponse response) {
        ImpersonationService.Result result = impersonationService.begin(
                CurrentAccount.requireId(), body.targetAccountId(), body.reason(), CurrentAccount.authTime(), request);
        sessionService.setCookie(response, result.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/impersonate:exit")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Exit impersonation, restore operator session", operationId = "exitImpersonation")
    public ResponseEntity<Void> exitImpersonation(HttpServletRequest request, HttpServletResponse response) {
        Long impersonatorId = CurrentAccount.impersonatorId();
        if (impersonatorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not currently impersonating");
        }
        ImpersonationService.Result result = impersonationService.exit(
                impersonatorId,
                CurrentAccount.requireId(),
                CurrentAccount.requireJti(),
                CurrentAccount.authTime(),
                request);
        sessionService.setCookie(response, result.token());
        return ResponseEntity.noContent().build();
    }
}
