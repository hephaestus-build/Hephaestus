package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Active-session inventory + revocation. Each non-revoked, non-expired browser token and each live
 * installed-client session of the current account is a "session". Thin adapter over
 * {@link AuthSessionService}.
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

    /** What signed in: the web app, or an installed client. */
    public enum SessionClient {
        WEB,
        BROWSER_EXTENSION,
    }

    /**
     * @param expiresAt when the token expires; for an installed client, the session's own deadline
     * @param client    what signed in
     */
    public record SessionViewDTO(
            UUID jti,
            @Nullable Instant issuedAt,
            Instant expiresAt,
            @Nullable String userAgent,
            @Nullable String ip,
            boolean current,
            @NonNull SessionClient client) {}

    @GetMapping
    @Operation(summary = "List active sessions for the current user", operationId = "listSessions")
    public ResponseEntity<List<SessionViewDTO>> list() {
        UUID currentJti = CurrentAccount.requireJti();
        UUID currentSessionId = CurrentAccount.sessionIdOrNull();
        List<SessionViewDTO> views = sessionService.activeSessions(CurrentAccount.requireId()).stream()
                .map(entry -> new SessionViewDTO(
                        entry.jti(),
                        entry.issuedAt(),
                        entry.expiresAt(),
                        entry.userAgent(),
                        entry.ip(),
                        entry.jti().equals(currentJti)
                                || (currentSessionId != null && currentSessionId.equals(entry.sessionId())),
                        client(entry.clientKind())))
                .toList();
        return ResponseEntity.ok(views);
    }

    private static SessionClient client(@Nullable InstalledClientKind kind) {
        if (kind == null) {
            return SessionClient.WEB;
        }
        return switch (kind) {
            case BROWSER_EXTENSION -> SessionClient.BROWSER_EXTENSION;
        };
    }

    @DeleteMapping("/{jti}")
    @Operation(
            summary = "Revoke a single session",
            description = "Any token of an installed-client session, even one it rotated away, ends that session.",
            operationId = "revokeSession")
    public ResponseEntity<Void> revoke(@PathVariable UUID jti) {
        sessionService.revokeSession(CurrentAccount.requireId(), jti);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Revoke all sessions except the current one", operationId = "revokeOtherSessions")
    public ResponseEntity<Void> revokeAllOthers() {
        sessionService.revokeAllExcept(
                CurrentAccount.requireId(), CurrentAccount.requireJti(), CurrentAccount.sessionIdOrNull());
        return ResponseEntity.noContent().build();
    }
}
