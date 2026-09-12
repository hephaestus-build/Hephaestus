package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnServerRole
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Session lifecycle")
public class AuthLifecycleController {

    private final AuthSessionService sessionService;

    public AuthLifecycleController(AuthSessionService sessionService) {
        this.sessionService = sessionService;
    }

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
    @ApiResponse(responseCode = "204", description = "Session renewal completed")
    @ApiResponse(responseCode = "401", description = "Session has ended")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        boolean sessionContinues = sessionService.refresh(
                CurrentAccount.requireId(),
                CurrentAccount.requireJti(),
                TokenConstraints.session(CurrentAccount.sessionExpiresAt(), CurrentAccount.authTime()),
                request,
                response);
        return sessionContinues
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
