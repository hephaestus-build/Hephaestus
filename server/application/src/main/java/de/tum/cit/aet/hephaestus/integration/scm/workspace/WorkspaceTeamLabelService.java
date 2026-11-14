package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserTeamsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamSettingsService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.team.TeamService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@WorkspaceAgnostic(
        "Manages team labels across workspaces - operates on workspace context, not within" + " workspace scope")
public class WorkspaceTeamLabelService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTeamLabelService.class);

    private final WorkspaceRepository workspaceRepository;
    private final TeamRepository teamRepository;
    private final ScmMemberRepository workspaceMembershipRepository;
    private final WorkspaceTeamSettingsService workspaceTeamSettingsService;
    private final WorkspaceTeamScopeResolver workspaceTeamScopeResolver;
    private final TeamService teamService;

    public WorkspaceTeamLabelService(
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            ScmMemberRepository workspaceMembershipRepository,
            WorkspaceTeamSettingsService workspaceTeamSettingsService,
            WorkspaceTeamScopeResolver workspaceTeamScopeResolver,
            TeamService teamService) {
        this.workspaceRepository = workspaceRepository;
        this.teamRepository = teamRepository;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.workspaceTeamSettingsService = workspaceTeamSettingsService;
        this.workspaceTeamScopeResolver = workspaceTeamScopeResolver;
        this.teamService = teamService;
    }

    private Predicate<Team> inScope(Workspace workspace) {
        WorkspaceTeamScope scope = workspaceTeamScopeResolver.resolve(workspace).orElse(null);
        return scope == null ? team -> false : scope::contains;
    }

    // User/Team Queries

    public List<UserTeamsDTO> getUsersWithTeams(String slug) {
        Workspace workspace = requireWorkspace(slug);
        log.debug("Retrieved users with teams: workspaceId={}", workspace.getId());
        List<User> users = workspaceMembershipRepository.findHumanUsersWithTeamsByWorkspaceId(workspace.getId());
        Set<Long> hiddenTeamIds = workspaceTeamSettingsService.getHiddenTeamIds(workspace.getId());
        Set<Long> hiddenMemberIds = workspaceMembershipRepository.findHiddenUserIdsByWorkspaceId(workspace.getId());
        // User memberships span tenants; filter teams to this workspace independently of the roster.
        Predicate<Team> inScope = inScope(workspace);
        return users.stream()
                .map(user -> UserTeamsDTO.fromUserWithScopeSettings(
                        user, hiddenTeamIds, hiddenMemberIds.contains(user.getId()), inScope))
                .toList();
    }

    public List<UserTeamsDTO> getUsersWithTeams(WorkspaceContext workspaceContext) {
        return getUsersWithTeams(requireSlug(workspaceContext));
    }

    // Label Management

    public Optional<TeamInfoDTO> addLabelToTeam(String slug, Long teamId, Long labelId) {
        Workspace workspace = requireWorkspace(slug);
        if (workspaceTeamSettingsService
                .addLabelFilter(workspace, teamId, labelId)
                .isEmpty()) {
            return Optional.empty();
        }
        return teamService.teamInfo(workspace, teamId);
    }

    public Optional<TeamInfoDTO> addLabelToTeam(WorkspaceContext workspaceContext, Long teamId, Long labelId) {
        return addLabelToTeam(requireSlug(workspaceContext), teamId, labelId);
    }

    public Optional<TeamInfoDTO> removeLabelFromTeam(String slug, Long teamId, Long labelId) {
        Workspace workspace = requireWorkspace(slug);
        log.debug(
                "Removing label from team: labelId={}, teamId={}, workspaceId={}", labelId, teamId, workspace.getId());

        if (teamRepository.findById(teamId).filter(inScope(workspace)).isEmpty()) {
            return Optional.empty();
        }
        workspaceTeamSettingsService.removeLabelFilter(workspace, teamId, labelId);
        return teamService.teamInfo(workspace, teamId);
    }

    public Optional<TeamInfoDTO> removeLabelFromTeam(WorkspaceContext workspaceContext, Long teamId, Long labelId) {
        return removeLabelFromTeam(requireSlug(workspaceContext), teamId, labelId);
    }

    // Helper Methods

    private Workspace requireWorkspace(String slug) {
        if (isBlank(slug)) {
            throw new IllegalArgumentException("Workspace slug must not be blank.");
        }
        return workspaceRepository
                .findByWorkspaceSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", slug));
    }

    private String requireSlug(WorkspaceContext workspaceContext) {
        Objects.requireNonNull(workspaceContext, "WorkspaceContext must not be null");
        String slug = workspaceContext.slug();
        if (isBlank(slug)) {
            throw new IllegalArgumentException("Workspace context slug must not be blank.");
        }
        return slug;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
