package de.tum.cit.aet.hephaestus.integration.scm.workspace.team;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextResolver;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@RequestMapping("/teams")
@Tag(name = "Teams", description = "Team management within workspace")
public class TeamController {

    private static final Logger log = LoggerFactory.getLogger(TeamController.class);

    private final TeamService teamService;
    private final WorkspaceContextResolver workspaceResolver;

    public TeamController(TeamService teamService, WorkspaceContextResolver workspaceResolver) {
        this.teamService = teamService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    @Operation(summary = "List teams", description = "Returns all teams in the workspace organization")
    public ResponseEntity<List<TeamInfoDTO>> getAllTeams(WorkspaceContext workspaceContext) {
        log.info("Listing teams: workspaceSlug={}", workspaceContext.slug());
        Workspace workspace = workspaceResolver.requireWorkspace(workspaceContext);
        List<TeamInfoDTO> teams = teamService.getAllTeams(workspace);
        return ResponseEntity.ok(teams);
    }

    /** Hidden teams are excluded from workspace activity filters. */
    @PatchMapping("/{id}/visibility")
    @RequireAtLeastWorkspaceAdmin
    @Operation(summary = "Update team visibility", description = "Show or hide a team in workspace activity")
    @AuditExempt(reason = "team visibility is a reporting view; teams are SCM-synced, not configured" + " here")
    public ResponseEntity<Void> updateTeamVisibility(
            WorkspaceContext workspaceContext, @PathVariable Long id, @Valid @RequestBody VisibilityUpdateDTO request) {
        log.info("Updating team visibility: teamId={}, workspaceSlug={}", id, workspaceContext.slug());
        Workspace workspace = workspaceResolver.requireWorkspace(workspaceContext);

        boolean updated = teamService.updateTeamVisibility(workspace, id, request.hidden());
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PatchMapping("/{teamId}/repositories/{repositoryId}/visibility")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "repository visibility is a reporting view; grants no access")
    public ResponseEntity<Void> updateRepositoryVisibility(
            WorkspaceContext workspaceContext,
            @PathVariable Long teamId,
            @PathVariable Long repositoryId,
            @Valid @RequestBody VisibilityUpdateDTO request) {
        log.info(
                "Updating repository visibility: repositoryId={}, teamId={}, workspaceSlug={}",
                repositoryId,
                teamId,
                workspaceContext.slug());
        Workspace workspace = workspaceResolver.requireWorkspace(workspaceContext);

        boolean updated = teamService.updateRepositoryVisibility(workspace, teamId, repositoryId, request.hidden());
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
