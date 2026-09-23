package de.tum.cit.aet.hephaestus.workspace.adapter;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.SyncTargetFactory;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter that bridges workspace entities to the sync engine SPI.
 * <p>
 * This adapter maps workspace domain concepts to the generic SPI abstractions:
 * <ul>
 *   <li>Workspace → Scope (identified by scopeId)</li>
 *   <li>RepositoryToMonitor → SyncTarget (identified by syncTargetId)</li>
 * </ul>
 * <p>
 * Implements the full sync provider interface hierarchy:
 * <ul>
 *   <li>{@link SyncTargetProvider} - Core sync target operations</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.integration.core.spi.SyncTimestampProvider SyncTimestampProvider} - Sync timestamp operations</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider BackfillStateProvider} - Backfill state management</li>
 * </ul>
 */
@Component
public class WorkspaceSyncTargetProvider implements SyncTargetProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncTargetProvider.class);

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final ConnectionService connectionService;
    private final NatsConnectionProperties natsProperties;

    // Absent under the webhook runtime role — reconcile then skips the consumer refresh.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    public WorkspaceSyncTargetProvider(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            NatsConnectionProperties natsProperties,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService) {
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.natsProperties = natsProperties;
        this.natsConsumerService = natsConsumerService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncTarget> getActiveSyncTargets() {
        return workspaceRepository.findAll().stream()
                .filter(ws -> ws.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .filter(ws -> hasActiveProvider(ws, IntegrationKind.GITHUB))
                .flatMap(ws -> ws.getRepositoriesToMonitor().stream()
                        // Apply repository filter to respect monitoring configuration (e.g., dev environment limits)
                        .filter(workspaceScopeFilter::isRepositoryAllowed)
                        .map(rtm -> SyncTargetFactory.create(ws, rtm, connectionService)))
                .toList();
    }

    private boolean hasActiveProvider(Workspace workspace, IntegrationKind kind) {
        try {
            return connectionService
                    .findActiveProviderKind(workspace.getId())
                    .map(k -> k == kind)
                    .orElse(false);
        } catch (IllegalStateException e) {
            // A single corrupt workspace with ACTIVE Connections for BOTH GitHub and GitLab makes
            // findActiveProviderKind fail loud. This filter runs while streaming EVERY workspace at
            // the top of the daily sync cron (getSyncSessions/getSyncStatistics/getActiveSyncTargets);
            // letting the throw escape would abort the whole enumeration and silently skip the sync
            // for every other tenant until the bad row is fixed out-of-band. Drop just this workspace.
            log.error(
                    "Skipping workspace from sync enumeration: reason=dualActiveProvider, workspaceId={}, error={}",
                    workspace.getId(),
                    e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncTarget> getSyncTargetsForScope(Long scopeId) {
        return workspaceRepository
                .findById(scopeId)
                .map(ws -> ws.getRepositoriesToMonitor().stream()
                        // Apply repository filter to respect monitoring configuration (e.g., dev environment limits)
                        .filter(workspaceScopeFilter::isRepositoryAllowed)
                        .map(rtm -> SyncTargetFactory.create(ws, rtm, connectionService))
                        .toList())
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isScopeActiveForSync(Long scopeId) {
        return workspaceRepository
                .findById(scopeId)
                .map(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE)
                .orElse(false);
    }

    @Override
    @Transactional
    public void updateSyncTimestamp(Long syncTargetId, SyncType syncType, Instant syncedAt) {
        repositoryToMonitorRepository
                .findById(syncTargetId)
                .ifPresentOrElse(
                        rtm -> {
                            switch (syncType) {
                                case LABELS -> rtm.setLabelsSyncedAt(syncedAt);
                                case MILESTONES -> rtm.setMilestonesSyncedAt(syncedAt);
                                case ISSUES -> rtm.setIssuesSyncedAt(syncedAt);
                                case PULL_REQUESTS -> rtm.setPullRequestsSyncedAt(syncedAt);
                                case DISCUSSIONS -> rtm.setDiscussionsSyncedAt(syncedAt);
                                case COLLABORATORS -> rtm.setCollaboratorsSyncedAt(syncedAt);
                                case FULL_REPOSITORY -> rtm.setRepositorySyncedAt(syncedAt);
                                default -> {}
                            }
                            repositoryToMonitorRepository.save(rtm);
                        },
                        () -> log.debug(
                                "Skipped sync timestamp update: reason=syncTargetNotFound, syncTargetId={}",
                                syncTargetId));
    }

    @Override
    public void updateSyncError(Long syncTargetId, @Nullable String error) {
        repositoryToMonitorRepository.updateLastSyncError(syncTargetId, error);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncMetadata> getSyncMetadata(Long scopeId) {
        return workspaceRepository.findById(scopeId).map(this::toSyncMetadata);
    }

    @Override
    @Transactional
    public void updateScopeSyncTimestamp(Long scopeId, SyncType syncType, Instant syncedAt) {
        workspaceRepository
                .findById(scopeId)
                .ifPresentOrElse(
                        ws -> {
                            switch (syncType) {
                                case ISSUE_TYPES -> ws.setIssueTypesSyncedAt(syncedAt);
                                case ISSUE_DEPENDENCIES -> ws.setIssueDependenciesSyncedAt(syncedAt);
                                case SUB_ISSUES -> ws.setSubIssuesSyncedAt(syncedAt);
                                default -> {}
                            }
                            workspaceRepository.save(ws);
                        },
                        () -> log.warn(
                                "Failed to update scope sync timestamp: reason=workspaceNotFound, workspaceId={}",
                                scopeId));
    }

    private SyncMetadata toSyncMetadata(Workspace workspace) {
        // Derive organization login: prefer the linked organization, fall back to accountLogin.
        // The accountLogin is always the GitHub account (org or user) that owns the repositories,
        // so it's safe to use for organization-level operations like team sync.
        // For user accounts, the team sync will simply find no teams (which is expected).
        var organization = workspace.getOrganization();
        String orgLogin = organization != null ? organization.getLogin() : workspace.getAccountLogin();

        return new SyncMetadata(
                workspace.getId(),
                workspace.getDisplayName(),
                orgLogin,
                organization != null ? organization.getId() : null,
                workspace.getIssueTypesSyncedAt(),
                workspace.getIssueDependenciesSyncedAt(),
                workspace.getSubIssuesSyncedAt());
    }

    // USER AND TEAM SYNC STATE

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSyncState> getUserSyncState(Long scopeId) {
        return workspaceRepository.findById(scopeId).map(ws -> new UserSyncState(ws.getId(), ws.getUsersSyncedAt()));
    }

    @Override
    @Transactional
    public void updateUsersSyncTimestamp(Long scopeId, Instant syncedAt) {
        workspaceRepository
                .findById(scopeId)
                .ifPresentOrElse(
                        ws -> {
                            ws.setUsersSyncedAt(syncedAt);
                            workspaceRepository.save(ws);
                        },
                        () -> log.warn(
                                "Failed to update users sync timestamp: reason=workspaceNotFound, workspaceId={}",
                                scopeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamSyncState> getTeamSyncState(Long scopeId) {
        return workspaceRepository.findById(scopeId).map(ws -> {
            var orgNames = ws.getRepositoriesToMonitor().stream()
                    // Apply repository filter to derive org names only from allowed repositories
                    .filter(workspaceScopeFilter::isRepositoryAllowed)
                    .map(RepositoryToMonitor::getNameWithOwner)
                    .map(s -> s.split("/")[0])
                    .distinct()
                    .toList();
            return new TeamSyncState(ws.getId(), ws.getTeamsSyncedAt(), orgNames);
        });
    }

    @Override
    @Transactional
    public void updateTeamsSyncTimestamp(Long scopeId, Instant syncedAt) {
        workspaceRepository
                .findById(scopeId)
                .ifPresentOrElse(
                        ws -> {
                            ws.setTeamsSyncedAt(syncedAt);
                            workspaceRepository.save(ws);
                        },
                        () -> log.warn(
                                "Failed to update teams sync timestamp: reason=workspaceNotFound, workspaceId={}",
                                scopeId));
    }

    // SYNC TARGET OPERATIONS

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncTarget> findSyncTargetById(Long syncTargetId) {
        return repositoryToMonitorRepository.findById(syncTargetId).flatMap(rtm -> {
            var workspace = rtm.getWorkspace();
            return workspace == null
                    ? Optional.empty()
                    : Optional.of(SyncTargetFactory.create(workspace, rtm, connectionService));
        });
    }

    @Override
    @Transactional
    public void updateIssueBackfillState(
            Long syncTargetId,
            @Nullable Integer highWaterMark,
            @Nullable Integer checkpoint,
            @Nullable Instant lastRunAt) {
        repositoryToMonitorRepository
                .findById(syncTargetId)
                .ifPresentOrElse(
                        rtm -> {
                            if (highWaterMark != null) {
                                rtm.setIssueBackfillHighWaterMark(highWaterMark);
                            }
                            if (checkpoint != null) {
                                rtm.setIssueBackfillCheckpoint(checkpoint);
                            }
                            if (lastRunAt != null) {
                                rtm.setBackfillLastRunAt(lastRunAt);
                            }
                            repositoryToMonitorRepository.save(rtm);
                        },
                        () ->
                                // DEBUG: Expected during workspace reconfiguration when repository is removed mid-sync
                                log.debug(
                                        "Skipped issue backfill state update: reason=syncTargetNotFound, syncTargetId={}",
                                        syncTargetId));
    }

    @Override
    @Transactional
    public void updatePullRequestBackfillState(
            Long syncTargetId,
            @Nullable Integer highWaterMark,
            @Nullable Integer checkpoint,
            @Nullable Instant lastRunAt) {
        repositoryToMonitorRepository
                .findById(syncTargetId)
                .ifPresentOrElse(
                        rtm -> {
                            if (highWaterMark != null) {
                                rtm.setPullRequestBackfillHighWaterMark(highWaterMark);
                            }
                            if (checkpoint != null) {
                                rtm.setPullRequestBackfillCheckpoint(checkpoint);
                            }
                            if (lastRunAt != null) {
                                rtm.setBackfillLastRunAt(lastRunAt);
                            }
                            repositoryToMonitorRepository.save(rtm);
                        },
                        () ->
                                // DEBUG: Expected during workspace reconfiguration when repository is removed mid-sync
                                log.debug(
                                        "Skipped pull request backfill state update: reason=syncTargetNotFound, syncTargetId={}",
                                        syncTargetId));
    }

    @Override
    @Transactional
    public void removeSyncTarget(Long syncTargetId) {
        repositoryToMonitorRepository.deleteById(syncTargetId);
    }

    @Override
    @Transactional
    public void reconcileSyncTargetIdentity(
            Long syncTargetId, @Nullable Long resolvedNativeId, @Nullable String resolvedNameWithOwner) {
        reconcileSyncTargetInTransaction(syncTargetId, resolvedNativeId, resolvedNameWithOwner);
    }

    private void reconcileSyncTargetInTransaction(
            Long syncTargetId, @Nullable Long resolvedNativeId, @Nullable String resolvedNameWithOwner) {
        if (syncTargetId == null) {
            return;
        }
        repositoryToMonitorRepository.findById(syncTargetId).ifPresent(rtm -> {
            boolean dirty = false;

            // Capture the stable id the first time we resolve it (legacy / PAT rows start null).
            if (rtm.getNativeId() == null && resolvedNativeId != null) {
                rtm.setNativeId(resolvedNativeId);
                dirty = true;
            }

            boolean nameChanged = false;
            boolean nameDiffers = resolvedNameWithOwner != null
                    && !resolvedNameWithOwner.isBlank()
                    && !resolvedNameWithOwner.equals(rtm.getNameWithOwner());
            // Re-key only when the stable id agrees (or was just captured) — never rename by name
            // alone, which could clobber a legitimately reassigned row.
            boolean idAgrees = rtm.getNativeId() != null
                    && (resolvedNativeId == null || rtm.getNativeId().equals(resolvedNativeId));
            if (nameDiffers && idAgrees) {
                log.info(
                        "Re-keying repository monitor after upstream rename/transfer: syncTargetId={}, oldName={}, newName={}, nativeId={}",
                        syncTargetId,
                        sanitizeForLog(rtm.getNameWithOwner()),
                        sanitizeForLog(resolvedNameWithOwner),
                        rtm.getNativeId());
                rtm.setNameWithOwner(Objects.requireNonNull(resolvedNameWithOwner));
                dirty = true;
                nameChanged = true;
            }

            if (dirty) {
                repositoryToMonitorRepository.save(rtm);
            }

            // A name change moves the repo's NATS subject; rebuild the workspace consumer filters so
            // live events under the new name are delivered instead of silently ACK-dropped.
            if (nameChanged && natsProperties.enabled() && rtm.getWorkspace() != null) {
                Long workspaceId = rtm.getWorkspace().getId();
                natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspaceId));
            }
        });
    }

    @Override
    @Transactional
    public void reconcileSyncTargetsForRepository(@Nullable Long nativeId, @Nullable String newNameWithOwner) {
        if (nativeId == null || newNameWithOwner == null || newNameWithOwner.isBlank()) {
            return;
        }
        // Shared repositories mean N monitors for one nativeId — re-key each, reusing the single
        // re-key/consumer-refresh body so webhook healing and reconcile healing cannot diverge.
        for (RepositoryToMonitor rtm : repositoryToMonitorRepository.findByNativeId(nativeId)) {
            reconcileSyncTargetInTransaction(rtm.getId(), nativeId, newNameWithOwner);
        }
    }

    /**
     * Persists the pagination cursor for the given kind onto the RepositoryToMonitor
     * ({@code syncTargetId}-keyed) row.
     */
    @Override
    @Transactional
    public void updateSyncCursor(Long syncTargetId, SyncCursorKind kind, @Nullable String cursor) {
        repositoryToMonitorRepository
                .findById(syncTargetId)
                .ifPresentOrElse(
                        rtm -> {
                            switch (kind) {
                                case ISSUE -> rtm.setIssueSyncCursor(cursor);
                                case PULL_REQUEST -> rtm.setPullRequestSyncCursor(cursor);
                                case DISCUSSION -> rtm.setDiscussionSyncCursor(cursor);
                            }
                            repositoryToMonitorRepository.save(rtm);
                        },
                        () ->
                                // DEBUG: Expected during workspace reconfiguration when repository is removed mid-sync
                                log.debug(
                                        "Skipped sync cursor update: reason=syncTargetNotFound, kind={}, syncTargetId={}",
                                        kind,
                                        syncTargetId));
    }

    // SYNC SESSIONS

    @Override
    @Transactional(readOnly = true)
    public List<SyncSession> getSyncSessions(IntegrationKind kind) {
        List<Workspace> allWorkspaces = workspaceRepository.findAll();

        return allWorkspaces.stream()
                .filter(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE)
                .filter(ws -> hasActiveProvider(ws, kind))
                .filter(workspaceScopeFilter::isWorkspaceAllowed)
                .map(this::toSyncSession)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SyncStatistics getSyncStatistics() {
        List<Workspace> allWorkspaces = workspaceRepository.findAll();

        int total = allWorkspaces.size();
        int skippedByStatus = (int) allWorkspaces.stream()
                .filter(ws -> ws.getStatus() != WorkspaceStatus.ACTIVE)
                .count();

        List<Workspace> activeGitHubWorkspaces = allWorkspaces.stream()
                .filter(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE)
                .filter(ws -> hasActiveProvider(ws, IntegrationKind.GITHUB))
                .toList();

        int skippedByFilter = (int) activeGitHubWorkspaces.stream()
                .filter(ws -> !workspaceScopeFilter.isWorkspaceAllowed(ws))
                .count();

        int activeAndAllowed = activeGitHubWorkspaces.size() - skippedByFilter;

        return new SyncStatistics(
                total, skippedByStatus, skippedByFilter, activeAndAllowed, workspaceScopeFilter.isActive());
    }

    private SyncSession toSyncSession(Workspace workspace) {
        // Filter repositories by workspace scope
        List<SyncTarget> syncTargets = workspace.getRepositoriesToMonitor().stream()
                .filter(workspaceScopeFilter::isRepositoryAllowed)
                .map(rtm -> SyncTargetFactory.create(workspace, rtm, connectionService))
                .toList();

        long workspaceId = workspace.getId();
        Long installationId = connectionService
                .findActiveGitHubAppConfig(workspaceId)
                .map(ConnectionConfig.GitHubAppConfig::installationId)
                .orElse(null);
        String serverUrl = connectionService
                .findActiveGitLabConfig(workspaceId)
                .map(ConnectionConfig.GitLabConfig::serverUrl)
                .or(() -> connectionService
                        .findActiveGitHubAppConfig(workspaceId)
                        .map(ConnectionConfig.GitHubAppConfig::serverUrl))
                .or(() -> connectionService
                        .findActiveGitHubPatConfig(workspaceId)
                        .map(ConnectionConfig.GitHubPatConfig::serverUrl))
                .orElse(null);

        SyncContextProvider.SyncContext syncContext = new SyncContextProvider.SyncContext(
                workspaceId, workspace.getWorkspaceSlug(), workspace.getDisplayName(), installationId);

        return new SyncSession(
                workspaceId,
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                workspace.getAccountLogin(),
                installationId,
                serverUrl,
                syncTargets,
                syncContext);
    }
}
