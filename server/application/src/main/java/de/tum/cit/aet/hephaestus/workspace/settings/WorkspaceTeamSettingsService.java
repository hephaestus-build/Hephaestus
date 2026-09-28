package de.tum.cit.aet.hephaestus.workspace.settings;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing workspace-scoped team settings.
 *
 * <p>Provides business logic for:
 * <ul>
 *   <li>Team visibility (hidden) settings per workspace</li>
 *   <li>Repository contribution visibility settings per workspace</li>
 *   <li>Label filters for teams per workspace</li>
 * </ul>
 *
 * <p>This service replaces direct modifications to the deprecated fields on
 * {@link Team#isHidden()}, {@link Team#getLabels()}, and
 * {@link de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission#isHiddenFromContributions()}.
 *
 * @see WorkspaceTeamSettings
 * @see WorkspaceTeamRepositorySettings
 * @see WorkspaceTeamLabelFilter
 */
@Service
public class WorkspaceTeamSettingsService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTeamSettingsService.class);

    private final WorkspaceTeamSettingsRepository teamSettingsRepository;
    private final WorkspaceTeamRepositorySettingsRepository repositorySettingsRepository;
    private final WorkspaceTeamLabelFilterRepository labelFilterRepository;
    private final WorkspaceRepository workspaceRepository;
    private final TeamRepository teamRepository;
    private final RepositoryRepository repositoryRepository;
    private final LabelRepository labelRepository;
    private final WorkspaceTeamScopeResolver workspaceTeamScopeResolver;

    public WorkspaceTeamSettingsService(
            WorkspaceTeamSettingsRepository teamSettingsRepository,
            WorkspaceTeamRepositorySettingsRepository repositorySettingsRepository,
            WorkspaceTeamLabelFilterRepository labelFilterRepository,
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            RepositoryRepository repositoryRepository,
            LabelRepository labelRepository,
            WorkspaceTeamScopeResolver workspaceTeamScopeResolver) {
        this.teamSettingsRepository = teamSettingsRepository;
        this.repositorySettingsRepository = repositorySettingsRepository;
        this.labelFilterRepository = labelFilterRepository;
        this.workspaceRepository = workspaceRepository;
        this.teamRepository = teamRepository;
        this.repositoryRepository = repositoryRepository;
        this.labelRepository = labelRepository;
        this.workspaceTeamScopeResolver = workspaceTeamScopeResolver;
    }

    // Team Visibility (Hidden) Settings

    /**
     * Gets the team settings for a workspace and team.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @return the team settings if they exist
     */
    @Transactional(readOnly = true)
    public Optional<WorkspaceTeamSettings> getTeamSettings(Long workspaceId, Long teamId) {
        return teamSettingsRepository.findByWorkspaceIdAndTeamId(workspaceId, teamId);
    }

    /**
     * Checks if a team is hidden in the given workspace.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @return true if the team is hidden, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isTeamHidden(Long workspaceId, Long teamId) {
        return teamSettingsRepository
                .findByWorkspaceIdAndTeamId(workspaceId, teamId)
                .map(WorkspaceTeamSettings::isHidden)
                .orElse(false);
    }

    /**
     * Gets all hidden team IDs for a workspace.
     *
     * @param workspaceId the workspace ID
     * @return set of team IDs that are hidden in the workspace
     */
    @Transactional(readOnly = true)
    public Set<Long> getHiddenTeamIds(Long workspaceId) {
        return teamSettingsRepository.findHiddenTeamIdsByWorkspace(workspaceId);
    }

    /**
     * Updates the visibility of a team in a workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @param hidden whether to hide the team
     * @return the updated or created settings, or empty if team not found
     */
    @Transactional
    public Optional<WorkspaceTeamSettings> updateTeamVisibility(Workspace workspace, Long teamId, boolean hidden) {
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty() || !belongsToWorkspace(teamOpt.get(), workspace)) {
            log.warn(
                    "Skipped team visibility update: reason=teamNotFoundOrWrongWorkspace, teamId={}, workspaceSlug={}",
                    teamId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return Optional.empty();
        }

        Team team = teamOpt.get();
        WorkspaceTeamSettings settings = teamSettingsRepository
                .findByWorkspaceIdAndTeamId(workspace.getId(), teamId)
                .orElseGet(() -> new WorkspaceTeamSettings(workspace, team));

        settings.setHidden(hidden);
        WorkspaceTeamSettings saved = teamSettingsRepository.save(settings);

        log.info(
                "Updated team visibility: teamId={}, hidden={}, workspaceSlug={}",
                teamId,
                hidden,
                LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));

        return Optional.of(saved);
    }

    // Repository Contribution Visibility Settings

    /**
     * Gets the repository settings for a workspace, team, and repository.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @param repositoryId the repository ID
     * @return the repository settings if they exist
     */
    @Transactional(readOnly = true)
    public Optional<WorkspaceTeamRepositorySettings> getRepositorySettings(
            Long workspaceId, Long teamId, Long repositoryId) {
        return repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                workspaceId, teamId, repositoryId);
    }

    /**
     * Checks if a repository is hidden from contributions for a team in a workspace.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @param repositoryId the repository ID
     * @return true if the repository is hidden from contributions, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isRepositoryHiddenFromContributions(Long workspaceId, Long teamId, Long repositoryId) {
        return repositorySettingsRepository
                .findByWorkspaceIdAndTeamIdAndRepositoryId(workspaceId, teamId, repositoryId)
                .map(WorkspaceTeamRepositorySettings::isHiddenFromContributions)
                .orElse(false);
    }

    /**
     * Gets all repository IDs hidden from contributions in a workspace.
     *
     * @param workspaceId the workspace ID
     * @return set of repository IDs hidden from contributions
     */
    @Transactional(readOnly = true)
    public Set<Long> getHiddenRepositoryIds(Long workspaceId) {
        return repositorySettingsRepository.findHiddenRepositoryIdsByWorkspace(workspaceId);
    }

    /**
     * Gets repository IDs hidden from contributions for specific teams in a workspace.
     *
     * @param workspaceId the workspace ID
     * @param teamIds the team IDs to check
     * @return set of repository IDs hidden from contributions
     */
    @Transactional(readOnly = true)
    public Set<Long> getHiddenRepositoryIdsByTeams(Long workspaceId, Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Set.of();
        }
        return repositorySettingsRepository.findHiddenRepositoryIdsByWorkspaceAndTeams(workspaceId, teamIds);
    }

    /**
     * Updates the contribution visibility of a repository for a team in a workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @param repositoryId the repository ID
     * @param hiddenFromContributions whether to hide contributions from this repository
     * @return the updated or created settings, or empty if team/repository not found
     */
    @Transactional
    public Optional<WorkspaceTeamRepositorySettings> updateRepositoryVisibility(
            Workspace workspace, Long teamId, Long repositoryId, boolean hiddenFromContributions) {
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty() || !belongsToWorkspace(teamOpt.get(), workspace)) {
            log.warn(
                    "Skipped repository visibility update: reason=teamNotFoundOrWrongWorkspace, teamId={}, workspaceSlug={}",
                    teamId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return Optional.empty();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repositoryId);
        if (repoOpt.isEmpty()) {
            log.warn("Skipped repository visibility update: reason=repositoryNotFound, repositoryId={}", repositoryId);
            return Optional.empty();
        }

        Team team = teamOpt.get();
        Repository repository = repoOpt.get();

        WorkspaceTeamRepositorySettings settings = repositorySettingsRepository
                .findByWorkspaceIdAndTeamIdAndRepositoryId(workspace.getId(), teamId, repositoryId)
                .orElseGet(() -> new WorkspaceTeamRepositorySettings(workspace, team, repository));

        settings.setHiddenFromContributions(hiddenFromContributions);
        WorkspaceTeamRepositorySettings saved = repositorySettingsRepository.save(settings);

        log.info(
                "Updated repository visibility: repositoryId={}, teamId={}, hiddenFromContributions={}, workspaceSlug={}",
                repositoryId,
                teamId,
                hiddenFromContributions,
                LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));

        return Optional.of(saved);
    }

    // Label Filter Settings

    /**
     * Gets all labels configured as filters for a team in a workspace.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @return set of labels configured as filters
     */
    @Transactional(readOnly = true)
    public Set<Label> getTeamLabelFilters(Long workspaceId, Long teamId) {
        return labelFilterRepository.findLabelsByWorkspaceAndTeam(workspaceId, teamId);
    }

    /**
     * Gets label filters grouped by team for multiple teams in a workspace.
     *
     * <p>This batch method fetches all label filter associations for the specified teams
     * in a single database query, avoiding N+1 query patterns when processing multiple teams.
     *
     * @param workspaceId the workspace ID
     * @param teamIds the set of team IDs to fetch label filters for
     * @return map of team ID to set of labels configured as filters for that team
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<Label>> getTeamLabelFiltersForTeams(Long workspaceId, Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }

        List<WorkspaceTeamLabelFilter> filters =
                labelFilterRepository.findByWorkspaceIdAndTeamIds(workspaceId, teamIds);

        return filters.stream()
                .collect(Collectors.groupingBy(
                        filter -> filter.getTeam().getId(),
                        Collectors.mapping(WorkspaceTeamLabelFilter::getLabel, Collectors.toSet())));
    }

    /**
     * Gets all team IDs that have label filters in a workspace.
     *
     * @param workspaceId the workspace ID
     * @return set of team IDs with label filters
     */
    @Transactional(readOnly = true)
    public Set<Long> getTeamIdsWithLabelFilters(Long workspaceId) {
        return labelFilterRepository.findTeamIdsWithLabelFilters(workspaceId);
    }

    /**
     * Adds a label as a filter for a team in a workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @param labelId the label ID
     * @return the created label filter, or empty if team/label not found
     */
    @Transactional
    public Optional<WorkspaceTeamLabelFilter> addLabelFilter(Workspace workspace, Long teamId, Long labelId) {
        return addLabelFilterInTransaction(workspace, teamId, labelId);
    }

    private Optional<WorkspaceTeamLabelFilter> addLabelFilterInTransaction(
            Workspace workspace, Long teamId, Long labelId) {
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty() || !belongsToWorkspace(teamOpt.get(), workspace)) {
            log.warn(
                    "Skipped label filter addition: reason=teamNotFoundOrWrongWorkspace, teamId={}, workspaceSlug={}",
                    teamId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return Optional.empty();
        }

        Optional<Label> labelOpt = labelRepository.findById(labelId);
        if (labelOpt.isEmpty()) {
            log.warn("Skipped label filter addition: reason=labelNotFound, labelId={}", labelId);
            return Optional.empty();
        }

        Team team = teamOpt.get();
        Label label = labelOpt.get();

        // Check if filter already exists
        WorkspaceTeamLabelFilter.Id filterId = new WorkspaceTeamLabelFilter.Id(workspace.getId(), teamId, labelId);
        if (labelFilterRepository.existsById(filterId)) {
            log.debug(
                    "Skipped label filter addition: reason=filterAlreadyExists, teamId={}, labelId={}, workspaceSlug={}",
                    teamId,
                    labelId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return labelFilterRepository.findById(filterId);
        }

        WorkspaceTeamLabelFilter filter = new WorkspaceTeamLabelFilter(workspace, team, label);
        WorkspaceTeamLabelFilter saved = labelFilterRepository.save(filter);

        log.info(
                "Added label filter: labelId={}, teamId={}, workspaceSlug={}",
                labelId,
                teamId,
                LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));

        return Optional.of(saved);
    }

    /**
     * Adds a label as a filter for a team in a workspace, looking up by repository and label name.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @param repositoryId the repository ID the label belongs to
     * @param labelName the label name
     * @return the created label filter, or empty if team/label not found
     */
    @Transactional
    public Optional<WorkspaceTeamLabelFilter> addLabelFilterByName(
            Workspace workspace, Long teamId, Long repositoryId, String labelName) {
        Optional<Label> labelOpt = labelRepository.findByRepositoryIdAndName(repositoryId, labelName);
        if (labelOpt.isEmpty()) {
            log.warn(
                    "Skipped label filter addition: reason=labelNotFoundInRepository, labelName={}, repositoryId={}",
                    labelName,
                    repositoryId);
            return Optional.empty();
        }

        return addLabelFilterInTransaction(workspace, teamId, labelOpt.get().getId());
    }

    /**
     * Removes a label filter for a team in a workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     * @param labelId the label ID
     * @return true if the filter was removed, false if it didn't exist
     */
    @Transactional
    public boolean removeLabelFilter(Workspace workspace, Long teamId, Long labelId) {
        WorkspaceTeamLabelFilter.Id filterId = new WorkspaceTeamLabelFilter.Id(workspace.getId(), teamId, labelId);

        if (!labelFilterRepository.existsById(filterId)) {
            log.debug(
                    "Skipped label filter removal: reason=filterNotExists, teamId={}, labelId={}, workspaceSlug={}",
                    teamId,
                    labelId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return false;
        }

        labelFilterRepository.deleteByWorkspaceIdAndTeamIdAndLabelId(workspace.getId(), teamId, labelId);

        log.info(
                "Removed label filter: labelId={}, teamId={}, workspaceSlug={}",
                labelId,
                teamId,
                LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));

        return true;
    }

    /**
     * Removes all label filters for a team in a workspace.
     *
     * @param workspace the workspace
     * @param teamId the team ID
     */
    @Transactional
    public void removeAllLabelFilters(Workspace workspace, Long teamId) {
        labelFilterRepository.deleteAllByWorkspaceIdAndTeamId(workspace.getId(), teamId);
        log.info(
                "Removed all label filters: teamId={}, workspaceSlug={}",
                teamId,
                LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
    }

    // Utility Methods

    private boolean belongsToWorkspace(Team team, Workspace workspace) {
        return workspaceTeamScopeResolver
                .resolve(workspace)
                .map(scope -> scope.contains(team))
                .orElse(false);
    }

    /**
     * Gets or creates team settings for a workspace and team.
     * This is useful for ensuring settings exist before querying.
     *
     * @param workspace the workspace
     * @param team the team
     * @return the existing or new settings
     */
    @Transactional
    public WorkspaceTeamSettings getOrCreateTeamSettings(Workspace workspace, Team team) {
        return teamSettingsRepository
                .findByWorkspaceIdAndTeamId(workspace.getId(), team.getId())
                .orElseGet(() -> {
                    WorkspaceTeamSettings settings = new WorkspaceTeamSettings(workspace, team);
                    return teamSettingsRepository.save(settings);
                });
    }

    /**
     * Gets the workspace entity by ID.
     *
     * @param workspaceId the workspace ID
     * @return the workspace
     * @throws EntityNotFoundException if workspace not found
     */
    @Transactional(readOnly = true)
    public Workspace requireWorkspace(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
    }
}
