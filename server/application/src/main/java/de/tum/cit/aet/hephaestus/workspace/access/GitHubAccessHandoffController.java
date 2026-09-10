package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.access.GitHubAccessViewService.GitHubAccessHandoffPreviewDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Current-account approval: GitHub owners do not need workspace membership or ownership. */
@WorkspaceAgnostic(
        "Current-account consent selects a workspace only through an expiring target-bound capability; the recipient need not belong to that workspace")
@RestController
@ConditionalOnServerRole
@RequestMapping("/user/github-access/approval")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class GitHubAccessHandoffController {
    private final GitHubAccessAuthorizationService authorization;
    private final GitHubAccessViewService views;
    private final GitHubAccessApprovalService approvals;

    public record GitHubAccessCapabilityDTO(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override
        public String toString() {
            return "GitHubAccessCapabilityDTO[token=***]";
        }
    }

    @PostMapping("/preview")
    @AuditExempt(
            reason =
                    "Read-only setup intent, bound to an expiring capability; no private GitHub inventory is requested")
    @Operation(
            operationId = "previewGitHubAccessHandoff",
            summary = "Inspect the workspace and target requesting your organization-owner approval")
    public ResponseEntity<GitHubAccessHandoffPreviewDTO> preview(@Valid @RequestBody GitHubAccessCapabilityDTO input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(views.approval(authorization.prepareApproval(input.token())));
    }

    @PostMapping
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "GITHUB_ACCESS_AUTHORIZED")
    @Operation(
            operationId = "authorizeGitHubAccessHandoff",
            summary = "Authorize a bound GitHub scope using your verified organization-owner identity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> authorize(@Valid @RequestBody GitHubAccessCapabilityDTO input) {
        approvals.authorize(input.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
