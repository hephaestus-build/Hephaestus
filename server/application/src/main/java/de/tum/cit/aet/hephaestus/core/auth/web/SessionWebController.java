package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Active-session inventory + revocation. Each non-revoked, non-expired issued JWT for the
 * current account is a "session". Thin adapter over {@link AuthSessionService}.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/user/sessions")
@Tag(name = "Account", description = "Active sessions")
@PreAuthorize("isAuthenticated()")
public class SessionWebController {

    private final AuthSessionService sessionService;

    public SessionWebController(AuthSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public record SessionViewDTO(
            UUID jti,
            @Nullable Instant issuedAt,
            Instant expiresAt,
            @Nullable String userAgent,
            @Nullable String ip,
            boolean current,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The native app, whose session outlives this token until expiresAt")
            boolean nativeApp) {}

    @GetMapping
    @Operation(summary = "List active sessions for the current user", operationId = "listSessions")
    public ResponseEntity<List<SessionViewDTO>> list() {
        UUID currentJti = CurrentAccount.requireJti();
        List<SessionViewDTO> views = sessionService.activeSessions(CurrentAccount.requireId()).stream()
                .map(session -> new SessionViewDTO(
                        session.token().getJti(),
                        session.token().getIssuedAt(),
                        session.expiresAt(),
                        session.token().getUserAgent(),
                        session.token().getIpInet(),
                        session.token().getJti().equals(currentJti),
                        session.nativeApp()))
                .toList();
        return ResponseEntity.ok(views);
    }

    @DeleteMapping("/{jti}")
    @Operation(summary = "Revoke a single session", operationId = "revokeSession")
    public ResponseEntity<Void> revoke(@PathVariable UUID jti) {
        sessionService.revokeSession(CurrentAccount.requireId(), jti);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Revoke all sessions except the current one", operationId = "revokeOtherSessions")
    public ResponseEntity<Void> revokeAllOthers() {
        sessionService.revokeAllExcept(CurrentAccount.requireId(), CurrentAccount.requireJti());
        return ResponseEntity.noContent().build();
    }
}
