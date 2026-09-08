package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncJobDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/github-access")
@RequiredArgsConstructor
public class GitHubAccessController {
    private final GitHubAccessPolicyService policies;
    private final GitHubAccessViewService views;
    private final GitHubAccessApprovalService approvals;
    private final GitHubAccessJobService jobs;
    private final GitHubAccessClient github;

    public record GitHubAccessStateDTO(
            @NonNull Boolean configured,
            @Nullable String installationUrl,
            @NonNull List<GitHubAccessTargetDTO> targets) {}

    public record GitHubAccessConfigurationDTO(
            @NotBlank @Size(max = 100) String organization,
            @Nullable @Size(max = 100) String team,
            @NotNull @Positive Long installationId,
            @NotNull @Size(min = 1, max = 100) Set<@NotBlank @Size(max = 255) String> groupIds) {}

    public record GitHubAccessHandoffDTO(
            @NonNull GitHubAccessTargetDTO target, @NonNull String token) {
        @Override
        public String toString() {
            return "GitHubAccessHandoffDTO[token=***]";
        }
    }

    public record GitHubAccessApprovalDTO(
            @NotNull @Positive Long configurationVersion,
            @NotNull Instant previewCapturedAt) {}

    public record GitHubAccessPauseDTO(@NotNull Boolean paused) {}

    public record GitHubAccessRenewalDTO(@NotNull @Positive Long installationId) {}

    public record GitHubAccessDecisionDTO(
            @NotNull GitHubAccessReconciliation.Decision decision,
            @Nullable @Size(max = 512) String reason) {}

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            operationId = "getGitHubAccessTargets",
            summary = "Inspect independent GitHub organization and team access targets")
    public GitHubAccessStateDTO get(WorkspaceContext context) {
        return new GitHubAccessStateDTO(
                github.configured(), github.installationUrl().orElse(null), views.list(context.id()));
    }

    @PostMapping
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_POLICY")
    @Operation(
            operationId = "createGitHubAccessTarget",
            summary = "Draft a target and issue a one-use organization-owner handoff")
    public GitHubAccessHandoffDTO create(
            WorkspaceContext context, @Valid @RequestBody GitHubAccessConfigurationDTO input) {
        return handoff(context, policies.configure(context.id(), null, configuration(input)));
    }

    @PutMapping("/{targetId}")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_POLICY")
    @Operation(
            operationId = "configureGitHubAccessTarget",
            summary = "Revise the target policy and require fresh organization-owner authorization")
    public GitHubAccessHandoffDTO configure(
            WorkspaceContext context,
            @PathVariable Long targetId,
            @Valid @RequestBody GitHubAccessConfigurationDTO input) {
        return handoff(context, policies.configure(context.id(), targetId, configuration(input)));
    }

    @PostMapping("/{targetId}/handoffs")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_POLICY")
    @Operation(
            operationId = "renewGitHubAccessHandoff",
            summary = "Restore owner authorization or a reinstalled Access App without dropping pending removals")
    public GitHubAccessHandoffDTO renew(
            WorkspaceContext context, @PathVariable Long targetId, @Valid @RequestBody GitHubAccessRenewalDTO input) {
        return handoff(context, policies.renewApproval(context.id(), targetId, input.installationId()));
    }

    @PostMapping("/{targetId}/previews")
    @RequireWorkspaceOwner
    @AuditExempt(
            reason =
                    "Read-only preview uses the existing sync job ledger; explicit policy approval is audited separately")
    @Operation(
            operationId = "previewGitHubAccessTarget",
            summary = "Inventory existing access and current eligibility without provider writes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SyncJobDTO preview(WorkspaceContext context, @PathVariable Long targetId) {
        return jobs.request(context.id(), targetId, true);
    }

    @PostMapping("/{targetId}/reconciliations")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason =
                    "Reconciliation and retries use the sync job ledger; resulting changes have durable action and configuration audits")
    @Operation(
            operationId = "reconcileGitHubAccessTarget",
            summary = "Reconcile approved access and read back pending actions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SyncJobDTO reconcile(WorkspaceContext context, @PathVariable Long targetId) {
        return jobs.request(context.id(), targetId, false);
    }

    @PostMapping("/{targetId}/approvals")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_POLICY")
    @Operation(
            operationId = "approveGitHubAccessTarget",
            summary = "Approve the exact fresh preview under current workspace ownership")
    public GitHubAccessTargetDTO approve(
            WorkspaceContext context, @PathVariable Long targetId, @Valid @RequestBody GitHubAccessApprovalDTO input) {
        policies.approve(context.id(), targetId, input.configurationVersion(), input.previewCapturedAt());
        return views.get(context.id(), targetId);
    }

    @PatchMapping("/{targetId}/pause")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_POLICY")
    @Operation(
            operationId = "pauseGitHubAccessTarget",
            summary = "Pause or resume writes without claiming access was revoked")
    public GitHubAccessTargetDTO pause(
            WorkspaceContext context, @PathVariable Long targetId, @Valid @RequestBody GitHubAccessPauseDTO input) {
        policies.pause(context.id(), targetId, input.paused());
        return views.get(context.id(), targetId);
    }

    @DeleteMapping("/{targetId}")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_POLICY")
    @Operation(
            operationId = "endGitHubAccessTarget",
            summary = "End management while retaining authority and credentials until removals are confirmed")
    public GitHubAccessTargetDTO end(WorkspaceContext context, @PathVariable Long targetId) {
        policies.end(context.id(), targetId);
        return views.get(context.id(), targetId);
    }

    @PutMapping("/{targetId}/members/{githubUserId}/decision")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_MEMBERSHIP")
    @Operation(
            operationId = "decideGitHubAccessMembership",
            summary = "Adopt freshly inspected access, retain a manual exception or reset an exception")
    public GitHubAccessTargetDTO decide(
            WorkspaceContext context,
            @PathVariable Long targetId,
            @PathVariable Long githubUserId,
            @Valid @RequestBody GitHubAccessDecisionDTO input) {
        approvals.decide(context.id(), targetId, githubUserId, input.decision(), input.reason());
        return views.get(context.id(), targetId);
    }

    private GitHubAccessHandoffDTO handoff(WorkspaceContext context, GitHubAccessPolicyService.Handoff handoff) {
        return new GitHubAccessHandoffDTO(
                views.get(context.id(), handoff.target().getId()), handoff.token());
    }

    private static GitHubAccessPolicyService.Configuration configuration(GitHubAccessConfigurationDTO input) {
        return new GitHubAccessPolicyService.Configuration(
                input.organization(), input.team(), input.installationId(), input.groupIds());
    }
}
