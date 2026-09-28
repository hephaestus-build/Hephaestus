package de.tum.cit.aet.hephaestus.workspace.team;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermissionRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScope;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing teams within a workspace.
 *
 * <p>Handles team-related business logic including:
 * <ul>
 *   <li>Retrieving teams for a workspace</li>
 *   <li>Updating team visibility in workspace activity (via workspace-scoped settings)</li>
 *   <li>Updating repository visibility for teams (via workspace-scoped settings)</li>
 * </ul>
 *
 * <p>All visibility settings are stored in workspace-scoped entities to support
 * multi-workspace scenarios with different settings per workspace.
 *
 * @see WorkspaceTeamSettingsService
 */
@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final WorkspaceTeamScopeResolver workspaceTeamScopeResolver;
    private final TeamRepositoryPermissionRepository permissionRepository;
    private final WorkspaceTeamSettingsService workspaceTeamSettingsService;
    private final WorkspaceMembershipService workspaceMembershipService;

    public TeamService(
            TeamRepository teamRepository,
            WorkspaceTeamScopeResolver workspaceTeamScopeResolver,
            TeamRepositoryPermissionRepository permissionRepository,
            WorkspaceTeamSettingsService workspaceTeamSettingsService,
            WorkspaceMembershipService workspaceMembershipService) {
        this.teamRepository = teamRepository;
        this.workspaceTeamScopeResolver = workspaceTeamScopeResolver;
        this.permissionRepository = permissionRepository;
        this.workspaceTeamSettingsService = workspaceTeamSettingsService;
        this.workspaceMembershipService = workspaceMembershipService;
    }

    /**
     * Gets all teams in a workspace with workspace-scoped settings applied.
     *
     * @param workspace the workspace to get teams for
     * @return list of teams with their members, permissions, and workspace-scoped settings
     */
    @Transactional(readOnly = true)
    public List<TeamInfoDTO> getAllTeams(Workspace workspace) {
        WorkspaceTeamScope scope = workspaceTeamScopeResolver.resolve(workspace).orElse(null);
        if (scope == null) {
            return List.of();
        }

        Long workspaceId = workspace.getId();
        Set<Long> hiddenTeamIds = workspaceTeamSettingsService.getHiddenTeamIds(workspaceId);
        Set<Long> hiddenRepoIds = workspaceTeamSettingsService.getHiddenRepositoryIds(workspaceId);
        Set<Long> hiddenMemberIds = workspaceMembershipService.getHiddenMemberIds(workspaceId);

        List<Team> teams = teamRepository.findWithCollectionsByOrganizationIgnoreCaseAndProviderId(
                scope.accountLogin(), scope.providerId());

        // Batch fetch all label filters in ONE query instead of N queries (N+1 fix)
        Set<Long> teamIds = teams.stream().map(Team::getId).collect(Collectors.toSet());
        Map<Long, Set<Label>> labelFiltersByTeam =
                workspaceTeamSettingsService.getTeamLabelFiltersForTeams(workspaceId, teamIds);

        return teams.stream()
                .map(team -> convertToDTO(team, hiddenTeamIds, hiddenRepoIds, labelFiltersByTeam, hiddenMemberIds))
                .toList();
    }

    /**
     * Updates the visibility of a team in workspace activity.
     * Uses workspace-scoped settings for multi-workspace support.
     *
     * @param workspace the workspace the team belongs to
     * @param teamId the ID of the team to update
     * @param hidden whether to hide the team from workspace activity
     * @return true if the team was updated, false if not found or not in workspace
     */
    @Transactional
    public boolean updateTeamVisibility(Workspace workspace, Long teamId, Boolean hidden) {
        return workspaceTeamSettingsService
                .updateTeamVisibility(workspace, teamId, Boolean.TRUE.equals(hidden))
                .isPresent();
    }

    /**
     * Updates the visibility of a repository for a team's contributions.
     * Uses workspace-scoped settings for multi-workspace support.
     *
     * @param workspace the workspace the team belongs to
     * @param teamId the ID of the team
     * @param repositoryId the ID of the repository
     * @param hiddenFromContributions whether to hide the repository from contributions
     * @return true if the permission was updated, false if not found or not in workspace
     * @throws IllegalArgumentException if hiddenFromContributions is null
     */
    @Transactional
    public boolean updateRepositoryVisibility(
            Workspace workspace, Long teamId, Long repositoryId, Boolean hiddenFromContributions) {
        if (hiddenFromContributions == null) {
            throw new IllegalArgumentException("hiddenFromContributions must not be null");
        }

        return workspaceTeamSettingsService
                .updateRepositoryVisibility(
                        workspace, teamId, repositoryId, Boolean.TRUE.equals(hiddenFromContributions))
                .isPresent();
    }

    /**
     * Gets a team by ID, ensuring it belongs to the specified workspace.
     *
     * @param workspace the workspace the team must belong to
     * @param teamId the ID of the team
     * @return the team entity
     * @throws EntityNotFoundException if the team is not found or not in workspace
     */
    @Transactional(readOnly = true)
    public Team getTeam(Workspace workspace, Long teamId) {
        return teamRepository
                .findById(teamId)
                .filter(team -> belongsToWorkspace(team, workspace))
                .orElseThrow(() -> new EntityNotFoundException("Team", teamId));
    }

    /**
     * Gets a team repository permission, ensuring the team belongs to the specified workspace.
     *
     * @param workspace the workspace the team must belong to
     * @param teamId the ID of the team
     * @param repositoryId the ID of the repository
     * @return the permission entity
     * @throws EntityNotFoundException if the permission is not found or team not in workspace
     */
    @Transactional(readOnly = true)
    public TeamRepositoryPermission getTeamRepositoryPermission(Workspace workspace, Long teamId, Long repositoryId) {
        return permissionRepository
                .findWithTeamByTeam_IdAndRepository_Id(teamId, repositoryId)
                .filter(permission -> belongsToWorkspace(permission.getTeam(), workspace))
                .orElseThrow(
                        () -> new EntityNotFoundException("TeamRepositoryPermission", teamId + "/" + repositoryId));
    }

    private boolean belongsToWorkspace(@Nullable Team team, Workspace workspace) {
        if (team == null) {
            return false;
        }
        return workspaceTeamScopeResolver
                .resolve(workspace)
                .map(scope -> scope.contains(team))
                .orElse(false);
    }

    /**
     * Converts a Team entity to TeamInfoDTO with workspace-scoped settings applied.
     *
     * @param team the team entity
     * @param hiddenTeamIds set of team IDs that are hidden in this workspace
     * @param hiddenRepoIds set of repository IDs hidden from contributions in this workspace
     * @param labelFiltersByTeam pre-fetched label filters for all teams (avoids N+1 queries)
     * @return the DTO with workspace-scoped hidden/label settings
     */
    private TeamInfoDTO convertToDTO(
            Team team,
            Set<Long> hiddenTeamIds,
            Set<Long> hiddenRepoIds,
            Map<Long, Set<Label>> labelFiltersByTeam,
            Set<Long> hiddenMemberIds) {
        boolean isHidden = hiddenTeamIds.contains(team.getId());
        Set<Label> workspaceLabels = labelFiltersByTeam.getOrDefault(team.getId(), Set.of());

        return TeamInfoDTO.fromTeamWithScopeSettings(team, isHidden, workspaceLabels, hiddenRepoIds, hiddenMemberIds);
    }

    /**
     * Checks if a team is hidden in a specific workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @return true if the team is hidden in this workspace
     */
    @Transactional(readOnly = true)
    public boolean isTeamHidden(Workspace workspace, Long teamId) {
        return workspaceTeamSettingsService.isTeamHidden(workspace.getId(), teamId);
    }

    /**
     * Gets the label filters for a team in a workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @return set of labels configured as filters for this team in this workspace
     */
    @Transactional(readOnly = true)
    public Set<Label> getTeamLabelFilters(Workspace workspace, Long teamId) {
        return workspaceTeamSettingsService.getTeamLabelFilters(workspace.getId(), teamId);
    }
}
