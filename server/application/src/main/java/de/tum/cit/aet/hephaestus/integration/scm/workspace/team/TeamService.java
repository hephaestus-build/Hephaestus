package de.tum.cit.aet.hephaestus.integration.scm.workspace.team;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmMemberRepository;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceTeamScope;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamSettingsService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final WorkspaceTeamScopeResolver workspaceTeamScopeResolver;
    private final WorkspaceTeamSettingsService workspaceTeamSettingsService;
    private final ScmMemberRepository workspaceMembershipService;
    private final RepositoryRepository repositoryRepository;

    public TeamService(
            TeamRepository teamRepository,
            WorkspaceTeamScopeResolver workspaceTeamScopeResolver,
            WorkspaceTeamSettingsService workspaceTeamSettingsService,
            ScmMemberRepository workspaceMembershipService,
            RepositoryRepository repositoryRepository) {
        this.teamRepository = teamRepository;
        this.workspaceTeamScopeResolver = workspaceTeamScopeResolver;
        this.workspaceTeamSettingsService = workspaceTeamSettingsService;
        this.workspaceMembershipService = workspaceMembershipService;
        this.repositoryRepository = repositoryRepository;
    }

    @Transactional(readOnly = true)
    public List<TeamInfoDTO> getAllTeams(Workspace workspace) {
        WorkspaceTeamScope scope = workspaceTeamScopeResolver.resolve(workspace).orElse(null);
        if (scope == null) {
            return List.of();
        }

        Long workspaceId = workspace.getId();
        Set<Long> hiddenTeamIds = workspaceTeamSettingsService.getHiddenTeamIds(workspaceId);
        Set<Long> hiddenRepoIds = workspaceTeamSettingsService.getHiddenRepositoryIds(workspaceId);
        Set<Long> hiddenMemberIds = workspaceMembershipService.findHiddenUserIdsByWorkspaceId(workspaceId);
        Set<Long> monitoredRepoIds = repositoryRepository.findMonitoredRepositoryIds(workspaceId);

        List<Team> teams = teamRepository.findWithRepoPermissionsByOrganizationIgnoreCaseAndProviderId(
                scope.accountLogin(), scope.providerId());
        // Fills the memberships of the teams above in this transaction; without it each team loads them on its own.
        teamRepository.findWithMembershipsByOrganizationIgnoreCaseAndProviderId(
                scope.accountLogin(), scope.providerId());

        Set<Long> teamIds = teams.stream().map(Team::getId).collect(Collectors.toSet());
        Map<Long, Set<Label>> labelFiltersByTeam =
                workspaceTeamSettingsService.getTeamLabelFiltersForTeams(workspaceId, teamIds);

        return teams.stream()
                .map(team -> convertToDTO(
                        team, hiddenTeamIds, hiddenRepoIds, monitoredRepoIds, labelFiltersByTeam, hiddenMemberIds))
                .toList();
    }

    /** One team of the workspace as the team list shows it, or empty when the team is not in the workspace. */
    @Transactional(readOnly = true)
    public Optional<TeamInfoDTO> teamInfo(Workspace workspace, Long teamId) {
        Long workspaceId = workspace.getId();
        return teamRepository
                .findWithRepoPermissionsById(teamId)
                .filter(team -> belongsToWorkspace(team, workspace))
                .map(team -> {
                    // Fills the team's memberships in this transaction, in their own query.
                    teamRepository.findWithMembershipsById(teamId);
                    return convertToDTO(
                            team,
                            workspaceTeamSettingsService.getHiddenTeamIds(workspaceId),
                            workspaceTeamSettingsService.getHiddenRepositoryIds(workspaceId),
                            repositoryRepository.findMonitoredRepositoryIds(workspaceId),
                            Map.of(teamId, workspaceTeamSettingsService.getTeamLabelFilters(workspaceId, teamId)),
                            workspaceMembershipService.findHiddenUserIdsByWorkspaceId(workspaceId));
                });
    }

    @Transactional
    public boolean updateTeamVisibility(Workspace workspace, Long teamId, Boolean hidden) {
        return workspaceTeamSettingsService
                .updateTeamVisibility(workspace, teamId, Boolean.TRUE.equals(hidden))
                .isPresent();
    }

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

    /** Refuses a team that does not belong to the workspace with {@link EntityNotFoundException}. */
    @Transactional(readOnly = true)
    public Team getTeam(Workspace workspace, Long teamId) {
        return teamRepository
                .findById(teamId)
                .filter(team -> belongsToWorkspace(team, workspace))
                .orElseThrow(() -> new EntityNotFoundException("Team", teamId));
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

    private TeamInfoDTO convertToDTO(
            Team team,
            Set<Long> hiddenTeamIds,
            Set<Long> hiddenRepoIds,
            Set<Long> monitoredRepoIds,
            Map<Long, Set<Label>> labelFiltersByTeam,
            Set<Long> hiddenMemberIds) {
        boolean isHidden = hiddenTeamIds.contains(team.getId());
        Set<Label> workspaceLabels = labelFiltersByTeam.getOrDefault(team.getId(), Set.of());

        return TeamInfoDTO.fromTeamWithScopeSettings(
                team, isHidden, workspaceLabels, hiddenRepoIds, monitoredRepoIds, hiddenMemberIds);
    }

    @Transactional(readOnly = true)
    public boolean isTeamHidden(Workspace workspace, Long teamId) {
        return workspaceTeamSettingsService.isTeamHidden(workspace.getId(), teamId);
    }

    @Transactional(readOnly = true)
    public Set<Label> getTeamLabelFilters(Workspace workspace, Long teamId) {
        return workspaceTeamSettingsService.getTeamLabelFilters(workspace.getId(), teamId);
    }
}
