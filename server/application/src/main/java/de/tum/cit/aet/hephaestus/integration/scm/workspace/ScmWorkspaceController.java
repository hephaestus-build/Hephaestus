package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserTeamsDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@WorkspaceScopedController
@Tag(name = "workspace")
@RequiredArgsConstructor
@Validated
public class ScmWorkspaceController {
    private final WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;
    private final WorkspaceTeamLabelService workspaceTeamLabelService;
    private final GitLabRepositorySelectionService gitLabRepositorySelectionService;
    private final GitLabStoppedProjectService gitLabStoppedProjectService;

    @GetMapping("/repositories")
    @Operation(summary = "List repositories monitored by a workspace")
    @ApiResponse(
            responseCode = "200",
            description = "Repository list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    public ResponseEntity<List<String>> getRepositoriesToMonitor(WorkspaceContext workspaceContext) {
        var repositories = workspaceRepositoryMonitorService.getMonitoredRepositories(workspaceContext).stream()
                .sorted()
                .toList();
        return ResponseEntity.ok(repositories);
    }

    @PostMapping("/repositories")
    @Operation(summary = "Add a repository to a workspace monitor list")
    @ApiResponse(responseCode = "201", description = "Repository added to the monitor list")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason = "which repositories are synced is ingestion scope, not a control that gates"
                    + " delivery; not recorded")
    public ResponseEntity<Void> addRepositoryToMonitor(
            WorkspaceContext workspaceContext, @RequestParam String nameWithOwner) {
        workspaceRepositoryMonitorService.addRepositoryToMonitor(workspaceContext, nameWithOwner);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/repositories")
    @Operation(summary = "Remove a repository from a workspace monitor list")
    @ApiResponse(responseCode = "204", description = "Repository removed from the monitor list")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason = "which repositories are synced is ingestion scope, not a control that gates"
                    + " delivery; not recorded")
    public ResponseEntity<Void> removeRepositoryToMonitor(
            WorkspaceContext workspaceContext, @RequestParam String nameWithOwner) {
        workspaceRepositoryMonitorService.removeRepositoryFromMonitor(workspaceContext, nameWithOwner);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/repositories/stopped")
    @Operation(summary = "List stopped GitLab projects whose synced work the workspace still keeps")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<GitLabStoppedProjectDTO>> getStoppedGitLabProjects(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(gitLabStoppedProjectService.getStoppedProjects(workspaceContext.id()));
    }

    @DeleteMapping("/repositories/stopped/{repositoryItemId}")
    @Operation(summary = "Remove the synced work a workspace keeps from a stopped GitLab project")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONNECTION_AUDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStoppedGitLabProject(
            WorkspaceContext workspaceContext, @PathVariable long repositoryItemId, @RequestParam UUID stopReceipt) {
        gitLabStoppedProjectService.removeStoppedProject(
                workspaceContext.id(), repositoryItemId, stopReceipt, CurrentAccount.requireId());
    }

    @GetMapping("/repositories/selection")
    @Operation(summary = "Read whether a GitLab workspace follows every project of its group or only selected ones")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<GitLabRepositorySelectionDTO> getGitLabRepositorySelection(
            WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(new GitLabRepositorySelectionDTO(
                gitLabRepositorySelectionService.getGitLabRepositorySelection(workspaceContext)));
    }

    @PatchMapping("/repositories/selection")
    @Operation(
            summary = "Choose whether a GitLab workspace follows every project of its group or only the projects"
                    + " it monitors")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONNECTION_AUDIT)
    public ResponseEntity<GitLabRepositorySelectionDTO> updateGitLabRepositorySelection(
            WorkspaceContext workspaceContext, @Valid @RequestBody GitLabRepositorySelectionDTO request) {
        return ResponseEntity.ok(
                new GitLabRepositorySelectionDTO(gitLabRepositorySelectionService.updateGitLabRepositorySelection(
                        workspaceContext, request.repositorySelection(), CurrentAccount.requireId())));
    }

    @GetMapping("/users")
    @Operation(summary = "List workspace users and the teams they belong to")
    public ResponseEntity<List<UserTeamsDTO>> getUsersWithTeams(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(workspaceTeamLabelService.getUsersWithTeams(workspaceContext));
    }

    @PostMapping("/teams/{teamId}/labels/{repositoryId}/{label}")
    @Operation(summary = "Add a repository label to a team")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "team label filter is a reporting view, not configuration that gates delivery")
    public ResponseEntity<TeamInfoDTO> addLabelToTeam(
            WorkspaceContext workspaceContext,
            @PathVariable Long teamId,
            @PathVariable Long repositoryId,
            @PathVariable String label) {
        return workspaceTeamLabelService
                .addLabelToTeam(workspaceContext, teamId, repositoryId, label)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/teams/{teamId}/labels/{labelId}")
    @Operation(summary = "Remove a repository label from a team")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "team label filter is a reporting view, not configuration that gates delivery")
    public ResponseEntity<TeamInfoDTO> removeLabelFromTeam(
            WorkspaceContext workspaceContext, @PathVariable Long teamId, @PathVariable Long labelId) {
        return workspaceTeamLabelService
                .removeLabelFromTeam(workspaceContext, teamId, labelId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
