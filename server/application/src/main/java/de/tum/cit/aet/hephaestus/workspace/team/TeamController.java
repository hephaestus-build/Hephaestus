package de.tum.cit.aet.hephaestus.workspace.team;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextResolver;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller for managing teams within a workspace.
 * Teams are synchronized from the GitHub organization and can be configured
 * to filter workspace activity.
 */
@WorkspaceScopedController
@RequestMapping("/team")
@Tag(name = "Teams", description = "Team management within workspace")
public class TeamController {

    private static final Logger log = LoggerFactory.getLogger(TeamController.class);

    private final TeamService teamService;
    private final WorkspaceContextResolver workspaceResolver;

    public TeamController(TeamService teamService, WorkspaceContextResolver workspaceResolver) {
        this.teamService = teamService;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Get all teams in the workspace.
     *
     * @param workspaceContext the resolved workspace context
     * @return list of teams with their members and permissions
     */
    @GetMapping
    @Operation(summary = "List teams", description = "Returns all teams in the workspace organization")
    @SecurityRequirements
    public ResponseEntity<List<TeamInfoDTO>> getAllTeams(WorkspaceContext workspaceContext) {
        log.info("Listing teams: workspaceSlug={}", workspaceContext.slug());
        Workspace workspace = workspaceResolver.requireWorkspace(workspaceContext);
        List<TeamInfoDTO> teams = teamService.getAllTeams(workspace);
        return ResponseEntity.ok(teams);
    }

    /**
     * Update team visibility in workspace activity.
     * Hidden teams are not offered as a filter there.
     *
     * @param workspaceContext the resolved workspace context
     * @param id the team ID
     * @param hidden whether to hide the team (body parameter, preferred)
     * @param hiddenParam whether to hide the team (query parameter, fallback)
     * @return 200 OK on success, 404 if team not found
     */
    @PostMapping("/{id}/visibility")
    @RequireAtLeastWorkspaceAdmin
    @Operation(summary = "Update team visibility", description = "Show or hide a team in workspace activity")
    @AuditExempt(reason = "team visibility is a reporting view; teams are SCM-synced, not configured here")
    public ResponseEntity<Void> updateTeamVisibility(
            WorkspaceContext workspaceContext,
            @PathVariable Long id,
            @RequestBody(required = false) Boolean hidden,
            @RequestParam(name = "hidden", required = false) Boolean hiddenParam) {
        log.info("Updating team visibility: teamId={}, workspaceSlug={}", id, workspaceContext.slug());
        Workspace workspace = workspaceResolver.requireWorkspace(workspaceContext);

        // Accept hidden flag from body (preferred) or from query parameter as fallback
        Boolean resolvedHidden = hidden != null ? hidden : hiddenParam;

        boolean updated = teamService.updateTeamVisibility(workspace, id, resolvedHidden);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{teamId}/repositories/{repositoryId}/visibility")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "repository visibility is a reporting view; grants no access")
    public ResponseEntity<Void> updateRepositoryVisibility(
            WorkspaceContext workspaceContext,
            @PathVariable Long teamId,
            @PathVariable Long repositoryId,
            @RequestBody(required = false) Boolean hiddenFromContributions,
            @RequestParam(name = "hiddenFromContributions", required = false) Boolean hiddenFromContributionsParam) {
        log.info(
                "Updating repository visibility: repositoryId={}, teamId={}, workspaceSlug={}",
                repositoryId,
                teamId,
                workspaceContext.slug());
        Workspace workspace = workspaceResolver.requireWorkspace(workspaceContext);

        Boolean resolvedHidden =
                hiddenFromContributions != null ? hiddenFromContributions : hiddenFromContributionsParam;
        if (resolvedHidden == null) {
            return ResponseEntity.badRequest().build();
        }

        boolean updated = teamService.updateRepositoryVisibility(workspace, teamId, repositoryId, resolvedHidden);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
