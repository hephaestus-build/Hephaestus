package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionAdminService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Switches a GitLab connection's group webhook between a signing token and the legacy secret token.
 *
 * <p>Instance admins only, although the route is workspace-scoped: both modes authenticate with the one
 * deployment-wide {@code WEBHOOK_SECRET}, which also verifies GitHub webhooks, so whether it may serve as
 * a GitLab signing token is a deployment decision rather than a workspace's.
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/connections")
@PreAuthorize("hasAuthority('app_admin')")
@RecentSignInExempt(
        reason = "sends GitLab only the deployment's WEBHOOK_SECRET, which every GitLab hook Hephaestus registers"
                + " already holds, and switches which of the hook's two token fields holds it; creates no credential")
@Tag(name = "Connections", description = "Workspace integration connection management")
public class GitLabSigningModeController {

    private final GitLabWebhookService webhookService;
    private final ConnectionAdminService admin;

    public GitLabSigningModeController(GitLabWebhookService webhookService, ConnectionAdminService admin) {
        this.webhookService = webhookService;
        this.admin = admin;
    }

    /**
     * Puts the connection's group webhook into the requested mode, then stores it with an audit row.
     * Answers 409 with GitLab's or the configuration's reason when the webhook could not be confirmed in
     * that mode, in which case the stored mode is unchanged. Repeating a request reconciles the webhook
     * again.
     */
    @PutMapping("/{connectionId}/gitlab-signing-mode")
    @Operation(operationId = "updateGitLabSigningMode")
    @Audited(ledger = AuditLedger.CONNECTION_AUDIT)
    public ResponseEntity<GitLabSigningModeDTO> update(
            WorkspaceContext workspace,
            @PathVariable Long connectionId,
            @RequestBody @Valid @NotNull GitLabSigningModeRequestDTO body,
            @Nullable Authentication authentication) {
        WebhookSetupResult result = webhookService.applySigningMode(workspace.id(), connectionId, body.signingMode());
        if (!result.registered()) {
            throw new GitLabSigningModeConflictException(
                    Objects.requireNonNullElse(result.failureReason(), "Webhook registration was skipped"));
        }
        admin.changeGitLabSigningMode(
                workspace.id(),
                connectionId,
                body.signingMode(),
                authentication == null ? "anonymous" : authentication.getName());
        return ResponseEntity.ok(
                new GitLabSigningModeDTO(body.signingMode(), Objects.requireNonNull(result.webhookId())));
    }

    /** Missing and other-workspace connections alike, so the response does not reveal which. */
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail handleNotFound(NoSuchElementException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Resource not found");
        return problem;
    }

    @ExceptionHandler(GitLabSigningModeConflictException.class)
    ProblemDetail handleConflict(GitLabSigningModeConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Webhook signing mode not changed");
        return problem;
    }
}
