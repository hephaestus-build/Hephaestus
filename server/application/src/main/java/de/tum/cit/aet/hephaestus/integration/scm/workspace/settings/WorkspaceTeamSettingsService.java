package de.tum.cit.aet.hephaestus.integration.scm.workspace.settings;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ApplicationEventPublisher eventPublisher;

    public WorkspaceTeamSettingsService(
            WorkspaceTeamSettingsRepository teamSettingsRepository,
            WorkspaceTeamRepositorySettingsRepository repositorySettingsRepository,
            WorkspaceTeamLabelFilterRepository labelFilterRepository,
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            RepositoryRepository repositoryRepository,
            LabelRepository labelRepository,
            WorkspaceTeamScopeResolver workspaceTeamScopeResolver,
            ApplicationEventPublisher eventPublisher) {
        this.teamSettingsRepository = teamSettingsRepository;
        this.repositorySettingsRepository = repositorySettingsRepository;
        this.labelFilterRepository = labelFilterRepository;
        this.workspaceRepository = workspaceRepository;
        this.teamRepository = teamRepository;
        this.repositoryRepository = repositoryRepository;
        this.labelRepository = labelRepository;
        this.workspaceTeamScopeResolver = workspaceTeamScopeResolver;
        this.eventPublisher = eventPublisher;
    }

    // Team Visibility (Hidden) Settings

    @Transactional(readOnly = true)
    public boolean isTeamHidden(Long workspaceId, Long teamId) {
        return teamSettingsRepository
                .findByWorkspaceIdAndTeamId(workspaceId, teamId)
                .map(WorkspaceTeamSettings::isHidden)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Set<Long> getHiddenTeamIds(Long workspaceId) {
        return teamSettingsRepository.findHiddenTeamIdsByWorkspace(workspaceId);
    }

    /** Returns empty when the team does not exist in the workspace. */
    @Transactional
    public Optional<WorkspaceTeamSettings> updateTeamVisibility(Workspace workspace, Long teamId, boolean hidden) {
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty() || !belongsToWorkspace(teamOpt.get(), workspace)) {
            log.warn(
                    "Skipped team visibility update: reason=teamNotFoundOrWrongWorkspace,"
                            + " teamId={}, workspaceSlug={}",
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

    @Transactional(readOnly = true)
    public Set<Long> getHiddenRepositoryIds(Long workspaceId) {
        return repositorySettingsRepository.findHiddenRepositoryIdsByWorkspace(workspaceId);
    }

    /** Returns empty when the team or repository does not exist in the workspace. */
    @Transactional
    public Optional<WorkspaceTeamRepositorySettings> updateRepositoryVisibility(
            Workspace workspace, Long teamId, Long repositoryId, boolean hiddenFromContributions) {
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty() || !belongsToWorkspace(teamOpt.get(), workspace)) {
            log.warn(
                    "Skipped repository visibility update: reason=teamNotFoundOrWrongWorkspace,"
                            + " teamId={}, workspaceSlug={}",
                    teamId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return Optional.empty();
        }

        Optional<Repository> repoOpt = repositoryRepository.isMonitored(workspace.getId(), repositoryId)
                ? repositoryRepository.findById(repositoryId)
                : Optional.empty();
        if (repoOpt.isEmpty()) {
            log.warn(
                    "Skipped repository visibility update: reason=repositoryNotMonitored, repositoryId={}",
                    repositoryId);
            return Optional.empty();
        }

        Team team = teamOpt.get();
        Repository repository = repoOpt.get();

        WorkspaceTeamRepositorySettings settings = repositorySettingsRepository
                .findByWorkspaceIdAndTeamIdAndRepositoryId(workspace.getId(), teamId, repositoryId)
                .orElseGet(() -> new WorkspaceTeamRepositorySettings(workspace, team, repository));

        settings.setHiddenFromContributions(hiddenFromContributions);
        WorkspaceTeamRepositorySettings saved = repositorySettingsRepository.save(settings);
        eventPublisher.publishEvent(new AgentContextChangedEvent(workspace.getId()));

        log.info(
                "Updated repository visibility: repositoryId={}, teamId={},"
                        + " hiddenFromContributions={}, workspaceSlug={}",
                repositoryId,
                teamId,
                hiddenFromContributions,
                LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));

        return Optional.of(saved);
    }

    // Label Filter Settings

    /** The repositories that team settings may name: only those the workspace monitors. */
    @Transactional(readOnly = true)
    public Set<Long> getMonitoredRepositoryIds(Long workspaceId) {
        return repositoryRepository.findMonitoredRepositoryIds(workspaceId);
    }

    @Transactional(readOnly = true)
    public Set<Label> getTeamLabelFilters(Long workspaceId, Long teamId) {
        Set<Long> monitored = repositoryRepository.findMonitoredRepositoryIds(workspaceId);
        return labelFilterRepository.findLabelsByWorkspaceAndTeam(workspaceId, teamId).stream()
                .filter(label -> monitored.contains(label.getRepository().getId()))
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Map<Long, Set<Label>> getTeamLabelFiltersForTeams(Long workspaceId, Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }

        List<WorkspaceTeamLabelFilter> filters =
                labelFilterRepository.findByWorkspaceIdAndTeamIds(workspaceId, teamIds);
        Set<Long> monitored = repositoryRepository.findMonitoredRepositoryIds(workspaceId);

        // A filter stored for a repository that the workspace no longer monitors stays out of view.
        return filters.stream()
                .filter(filter ->
                        monitored.contains(filter.getLabel().getRepository().getId()))
                .collect(Collectors.groupingBy(
                        filter -> filter.getTeam().getId(),
                        Collectors.mapping(WorkspaceTeamLabelFilter::getLabel, Collectors.toSet())));
    }

    @Transactional
    public Optional<WorkspaceTeamLabelFilter> addLabelFilter(Workspace workspace, Long teamId, Long labelId) {
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty() || !belongsToWorkspace(teamOpt.get(), workspace)) {
            log.warn(
                    "Skipped label filter addition: reason=teamNotFoundOrWrongWorkspace, teamId={},"
                            + " workspaceSlug={}",
                    teamId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return Optional.empty();
        }

        Optional<Label> labelOpt = labelRepository
                .findById(labelId)
                .filter(found -> repositoryRepository.isMonitored(
                        workspace.getId(), found.getRepository().getId()));
        if (labelOpt.isEmpty()) {
            log.warn("Skipped label filter addition: reason=labelNotInMonitoredRepository, labelId={}", labelId);
            return Optional.empty();
        }

        Team team = teamOpt.get();
        Label label = labelOpt.get();

        WorkspaceTeamLabelFilter.Id filterId = new WorkspaceTeamLabelFilter.Id(workspace.getId(), teamId, labelId);
        if (labelFilterRepository.existsById(filterId)) {
            log.debug(
                    "Skipped label filter addition: reason=filterAlreadyExists, teamId={},"
                            + " labelId={}, workspaceSlug={}",
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

    @Transactional
    public boolean removeLabelFilter(Workspace workspace, Long teamId, Long labelId) {
        WorkspaceTeamLabelFilter.Id filterId = new WorkspaceTeamLabelFilter.Id(workspace.getId(), teamId, labelId);

        if (!labelFilterRepository.existsById(filterId)) {
            log.debug(
                    "Skipped label filter removal: reason=filterNotExists, teamId={}, labelId={},"
                            + " workspaceSlug={}",
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

    @Transactional(readOnly = true)
    public Workspace requireWorkspace(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
    }
}
