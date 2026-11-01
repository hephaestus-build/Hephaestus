package de.tum.cit.aet.hephaestus.workspace.adapter;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
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
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkspaceSyncTargetProvider implements SyncTargetProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncTargetProvider.class);

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final ConnectionService connectionService;
    private final NatsConnectionProperties natsProperties;
    private final ApplicationEventPublisher eventPublisher;

    // Absent under the webhook runtime role — reconcile then skips the consumer refresh.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    private final ReviewScopeRepositoryRepository practiceReviewRepositoryTargetRepository;

    public WorkspaceSyncTargetProvider(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            NatsConnectionProperties natsProperties,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            ReviewScopeRepositoryRepository practiceReviewRepositoryTargetRepository,
            ApplicationEventPublisher eventPublisher) {
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.natsProperties = natsProperties;
        this.eventPublisher = eventPublisher;
        this.natsConsumerService = natsConsumerService;
        this.practiceReviewRepositoryTargetRepository = practiceReviewRepositoryTargetRepository;
    }

    private void logSkippedForUnreadableCredential(Workspace workspace, CredentialUnreadableException e) {
        log.error(
                "Skipping workspace from sync enumeration: reason=credentialUnreadable,"
                        + " workspaceId={}, connectionId={}, kind={}",
                workspace.getId(),
                e.connectionId(),
                e.kind());
    }

    private boolean hasActiveProvider(Workspace workspace, IntegrationKind kind) {
        try {
            return connectionService
                    .findActiveProviderKind(workspace.getId())
                    .map(k -> k == kind)
                    .orElse(false);
        } catch (IllegalStateException e) {
            // Ambiguous provider configuration must exclude only its own tenant from fleet enumeration.
            log.error(
                    "Skipping workspace from sync enumeration: reason=dualActiveProvider,"
                            + " workspaceId={}, errorType={}",
                    workspace.getId(),
                    e.getClass().getName());
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncTarget> getSyncTargetsForScope(Long scopeId) {
        return workspaceRepository
                .findById(scopeId)
                .map(ws -> ws.getRepositoriesToMonitor().stream()
                        // Apply repository filter to respect monitoring
                        // configuration (e.g., dev environment limits)
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
                                case COLLABORATORS -> rtm.setCollaboratorsSyncedAt(syncedAt);
                                case FULL_REPOSITORY -> rtm.setRepositorySyncedAt(syncedAt);
                                case REPOSITORY_VISIBILITY -> rtm.setRepositoryVisibilityConfirmedAt(syncedAt);
                                default -> {}
                            }
                            repositoryToMonitorRepository.save(rtm);
                        },
                        () -> log.debug(
                                "Skipped sync timestamp update: reason=syncTargetNotFound," + " syncTargetId={}",
                                syncTargetId));
    }

    @Override
    public void updateSyncError(Long syncTargetId, SyncPass pass, @Nullable String error) {
        switch (pass) {
            case RECENT -> repositoryToMonitorRepository.updateRecentSyncError(syncTargetId, error);
            case HISTORICAL_BACKFILL ->
                repositoryToMonitorRepository.updateHistoricalBackfillSyncError(syncTargetId, error);
        }
    }

    @Override
    public boolean deferUnavailableRepository(Long scopeId, Long syncTargetId) {
        if (!isRepositoryUnavailable(scopeId, syncTargetId)) {
            return false;
        }
        Instant now = Instant.now();
        return repositoryToMonitorRepository.reserveUnavailableRecheck(
                        scopeId, syncTargetId, now, now.plus(Duration.ofDays(1)))
                == 0;
    }

    @Override
    public boolean isRepositoryUnavailable(Long scopeId, Long syncTargetId) {
        return repositoryToMonitorRepository.existsByWorkspaceIdAndIdAndUnavailableSinceIsNotNull(
                scopeId, syncTargetId);
    }

    @Override
    public void recordRepositoryUnavailable(Long scopeId, Long syncTargetId) {
        Instant now = Instant.now();
        repositoryToMonitorRepository.recordUnavailable(scopeId, syncTargetId, now, now.plus(Duration.ofDays(1)));
    }

    @Override
    public void clearRepositoryUnavailable(Long scopeId, Long syncTargetId) {
        repositoryToMonitorRepository.clearUnavailable(scopeId, syncTargetId);
    }

    @Override
    public void retryUnavailableRepository(Long scopeId, Long syncTargetId) {
        repositoryToMonitorRepository.retryUnavailable(scopeId, syncTargetId);
    }

    @Override
    public void recheckUnavailableRepositories(Long scopeId) {
        repositoryToMonitorRepository.recheckUnavailable(scopeId);
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
                                "Failed to update scope sync timestamp:" + " reason=workspaceNotFound, workspaceId={}",
                                scopeId));
    }

    private SyncMetadata toSyncMetadata(Workspace workspace) {
        // Derive organization login: prefer the linked organization, fall back to accountLogin.
        // The accountLogin is always the GitHub account (org or user) that owns the repositories,
        // so it's safe to use for organization-level operations like team sync.
        // For user accounts, the team sync will simply find no teams (which is expected).
        String orgLogin = workspace.getAccountLogin();

        return new SyncMetadata(
                workspace.getId(),
                workspace.getDisplayName(),
                orgLogin,
                workspace.getOrganizationId(),
                workspace.getIssueTypesSyncedAt(),
                workspace.getIssueDependenciesSyncedAt(),
                workspace.getSubIssuesSyncedAt());
    }

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
                                "Failed to update users sync timestamp:" + " reason=workspaceNotFound, workspaceId={}",
                                scopeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamSyncState> getTeamSyncState(Long scopeId) {
        return workspaceRepository.findById(scopeId).map(ws -> {
            var orgNames = ws.getRepositoriesToMonitor().stream()
                    // Apply repository filter to derive org names only from
                    // allowed repositories
                    .filter(workspaceScopeFilter::isRepositoryAllowed)
                    .map(RepositoryToMonitor::getNameWithOwner)
                    .map(s -> s.split("/", -1)[0])
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
                                "Failed to update teams sync timestamp:" + " reason=workspaceNotFound, workspaceId={}",
                                scopeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncTarget> findSyncTargetById(Long syncTargetId) {
        return loadSyncTarget(syncTargetId);
    }

    private Optional<SyncTarget> loadSyncTarget(Long syncTargetId) {
        return repositoryToMonitorRepository.findById(syncTargetId).flatMap(rtm -> {
            var workspace = rtm.getWorkspace();
            return workspace == null
                    ? Optional.empty()
                    : Optional.of(SyncTargetFactory.create(workspace, rtm, connectionService));
        });
    }

    @Override
    @Transactional
    public Optional<SyncTarget> restartCompletedBackfill(
            long workspaceId, long syncTargetId, int providerCount, long storedCount) {
        if (repositoryToMonitorRepository.restartCompletedBackfill(
                        workspaceId, syncTargetId, providerCount, storedCount)
                == 0) return Optional.empty();
        return loadSyncTarget(syncTargetId);
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
                                // DEBUG: Expected during workspace reconfiguration when repository
                                // is removed mid-sync
                                log.debug(
                                        "Skipped issue backfill state update:"
                                                + " reason=syncTargetNotFound, syncTargetId={}",
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
                                // DEBUG: Expected during workspace reconfiguration when repository
                                // is removed mid-sync
                                log.debug(
                                        "Skipped pull request backfill state update:"
                                                + " reason=syncTargetNotFound, syncTargetId={}",
                                        syncTargetId));
    }

    @Override
    @Transactional
    public void removeSyncTarget(Long syncTargetId) {
        removeMonitor(syncTargetId);
    }

    private void removeMonitor(Long syncTargetId) {
        // A workspace holds its monitors eagerly with orphan removal: one deleted while its loaded
        // workspace still
        // holds it would be persisted again at flush, so it is removed from that set as well.
        repositoryToMonitorRepository.findById(syncTargetId).ifPresent(monitor -> {
            Workspace workspace = monitor.getWorkspace();
            List<Long> repositoryIds = repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(syncTargetId);
            if (workspace != null) {
                workspace.getRepositoriesToMonitor().remove(monitor);
            }
            repositoryToMonitorRepository.delete(monitor);
            repositoryToMonitorRepository.flush();
            if (workspace != null) {
                eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(workspace.getId(), repositoryIds));
            }
        });
    }

    @Override
    @Transactional
    public boolean mergeSyncTarget(Long keptSyncTargetId, Long duplicateSyncTargetId) {
        RepositoryToMonitor kept =
                repositoryToMonitorRepository.findById(keptSyncTargetId).orElse(null);
        RepositoryToMonitor duplicate =
                repositoryToMonitorRepository.findById(duplicateSyncTargetId).orElse(null);
        if (kept == null || duplicate == null) {
            return false;
        }
        Workspace workspace = kept.getWorkspace();
        Workspace duplicateWorkspace = duplicate.getWorkspace();
        Long workspaceId = workspace != null ? workspace.getId() : null;
        if (workspaceId == null || duplicateWorkspace == null || !workspaceId.equals(duplicateWorkspace.getId())) {
            return false;
        }
        // An empty list is a policy too; neither monitor's generated paths may be discarded.
        if (!kept.getGeneratedPaths().equals(duplicate.getGeneratedPaths())) {
            log.warn(
                    "Skipped repository monitor merge: reason=generatedPathsDiffer, workspaceId={}, syncTargetId={},"
                            + " keptSyncTargetId={}",
                    workspaceId,
                    duplicateSyncTargetId,
                    keptSyncTargetId);
            return false;
        }
        practiceReviewRepositoryTargetRepository
                .findById(new ReviewScopeRepository.Key(workspaceId, duplicateSyncTargetId))
                .ifPresent(carried -> {
                    // The kept monitor reviews what either one did: no base branches means
                    // every branch.
                    List<String> branches = practiceReviewRepositoryTargetRepository
                            .findById(new ReviewScopeRepository.Key(workspaceId, keptSyncTargetId))
                            .map(ReviewScopeRepository::getBaseBranches)
                            .map(own ->
                                    own.isEmpty() || carried.getBaseBranches().isEmpty()
                                            ? List.<String>of()
                                            : List.copyOf(new LinkedHashSet<>(
                                                    Stream.concat(own.stream(), carried.getBaseBranches().stream())
                                                            .toList())))
                            .orElse(carried.getBaseBranches());
                    practiceReviewRepositoryTargetRepository.delete(carried);
                    practiceReviewRepositoryTargetRepository.save(
                            new ReviewScopeRepository(workspaceId, keptSyncTargetId, branches));
                });
        removeMonitor(duplicateSyncTargetId);
        return true;
    }

    @Override
    @Transactional
    public void reconcileSyncTargetIdentity(
            Long syncTargetId, @Nullable Long resolvedNativeId, @Nullable String resolvedNameWithOwner) {
        reconcileSyncTargetInTransaction(syncTargetId, resolvedNativeId, resolvedNameWithOwner);
    }

    private boolean holdsAnotherInstancesId(RepositoryToMonitor rtm, Long resolvedNativeId) {
        return rtm.getId() != null
                && repositoryToMonitorRepository.monitorHoldsAnotherInstancesId(rtm.getId(), resolvedNativeId);
    }

    private void reconcileSyncTargetInTransaction(
            Long syncTargetId, @Nullable Long resolvedNativeId, @Nullable String resolvedNameWithOwner) {
        if (syncTargetId == null) {
            return;
        }
        repositoryToMonitorRepository.findById(syncTargetId).ifPresent(rtm -> {
            boolean dirty = false;

            // Capture the stable id the first time it resolves, and replace one
            // proven to belong to another provider
            // instance's repository of the same name: the scoped queries would hide
            // the monitored repository.
            Long workspaceId = Objects.requireNonNull(rtm.getWorkspace()).getId();
            if (resolvedNativeId != null
                    && (rtm.getNativeId() == null
                            || (!rtm.getNativeId().equals(resolvedNativeId)
                                    && holdsAnotherInstancesId(rtm, resolvedNativeId)))) {
                // These writes run without the workspace lock. Another monitor of this workspace holding the id
                // is reported and left as it is; the uniqueness constraint would reject the write.
                if (repositoryToMonitorRepository.existsByWorkspaceIdAndNativeIdAndIdNot(
                        workspaceId, resolvedNativeId, syncTargetId)) {
                    log.warn(
                            "Skipped repository monitor identity capture: reason=nativeIdHeldByAnotherMonitor,"
                                    + " workspaceId={}, syncTargetId={}, nativeId={}",
                            workspaceId,
                            syncTargetId,
                            resolvedNativeId);
                } else {
                    rtm.setNativeId(resolvedNativeId);
                    dirty = true;
                }
            }

            boolean nameChanged = false;
            boolean nameDiffers = resolvedNameWithOwner != null
                    && !resolvedNameWithOwner.isBlank()
                    && !resolvedNameWithOwner.equals(rtm.getNameWithOwner());
            // Re-key only when the stable id agrees (or was just captured) — never
            // rename by name
            // alone, which could clobber a legitimately reassigned row.
            boolean idAgrees = rtm.getNativeId() != null
                    && (resolvedNativeId == null || rtm.getNativeId().equals(resolvedNativeId));
            if (nameDiffers
                    && idAgrees
                    && repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwnerAndIdNot(
                            workspaceId, Objects.requireNonNull(resolvedNameWithOwner), syncTargetId)) {
                log.warn(
                        "Skipped repository monitor re-key: reason=nameHeldByAnotherMonitor, workspaceId={},"
                                + " syncTargetId={}, nativeId={}",
                        workspaceId,
                        syncTargetId,
                        rtm.getNativeId());
            } else if (nameDiffers && idAgrees) {
                log.info(
                        "Re-keying repository monitor after upstream"
                                + " rename/transfer: syncTargetId={}, oldName={},"
                                + " newName={}, nativeId={}",
                        syncTargetId,
                        sanitizeForLog(rtm.getNameWithOwner()),
                        sanitizeForLog(resolvedNameWithOwner),
                        rtm.getNativeId());
                rtm.setNameWithOwner(Objects.requireNonNull(resolvedNameWithOwner));
                dirty = true;
                nameChanged = true;
            }

            if (dirty) {
                repositoryToMonitorRepository.saveAndFlush(rtm);
            }
            eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(
                    workspaceId, repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(syncTargetId)));

            // A name change moves the repo's NATS subject; rebuild the workspace
            // consumer filters so
            // live events under the new name are delivered instead of silently
            // ACK-dropped.
            if (nameChanged && natsProperties.enabled()) {
                natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspaceId));
            }
        });
    }

    @Override
    @Transactional
    public void reconcileSyncTargetsForRepository(
            long providerId, @Nullable Long nativeId, @Nullable String newNameWithOwner) {
        if (nativeId == null || newNameWithOwner == null || newNameWithOwner.isBlank()) {
            return;
        }
        // Shared repositories mean N monitors for one nativeId on this provider — re-key each,
        // reusing the single
        // re-key/consumer-refresh body so webhook healing and reconcile healing cannot diverge.
        for (RepositoryToMonitor rtm :
                repositoryToMonitorRepository.findByNativeIdOnSourceProvider(nativeId, providerId)) {
            reconcileSyncTargetInTransaction(rtm.getId(), nativeId, newNameWithOwner);
        }
    }

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
                            }
                            repositoryToMonitorRepository.save(rtm);
                        },
                        () ->
                                // DEBUG: Expected during workspace reconfiguration when repository
                                // is removed mid-sync
                                log.debug(
                                        "Skipped sync cursor update: reason=syncTargetNotFound,"
                                                + " kind={}, syncTargetId={}",
                                        kind,
                                        syncTargetId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncSession> getSyncSessions(IntegrationKind kind) {
        List<Workspace> allWorkspaces = workspaceRepository.findAll();

        return allWorkspaces.stream()
                .filter(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE)
                .filter(ws -> hasActiveProvider(ws, kind))
                .filter(workspaceScopeFilter::isWorkspaceAllowed)
                .<SyncSession>mapMulti((ws, sessions) -> {
                    try {
                        sessions.accept(toSyncSession(ws));
                    } catch (CredentialUnreadableException e) {
                        logSkippedForUnreadableCredential(ws, e);
                    }
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncSession> getSyncSession(Long scopeId, IntegrationKind kind) {
        return workspaceRepository
                .findById(scopeId)
                .filter(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE)
                .filter(ws -> hasActiveProvider(ws, kind))
                .filter(workspaceScopeFilter::isWorkspaceAllowed)
                .map(this::toSyncSession);
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
