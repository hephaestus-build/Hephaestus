package de.tum.cit.aet.hephaestus.workspace.adapter;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.event.RepositoryExcludedFromWorkspacesEvent;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.framework.ResourceReaders;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofState;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.ResourceReaderRepository;
import de.tum.cit.aet.hephaestus.workspace.SyncTargetFactory;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceKind;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.metrics.WorkspaceMetrics;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkspaceSyncTargetProvider implements SyncTargetProvider, RepositoryScopeProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncTargetProvider.class);

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final ResourceReaderRepository resourceReaderRepository;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final ConnectionService connectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final PrivacyJournal journal;

    private final ReviewScopeRepositoryRepository practiceReviewRepositoryTargetRepository;
    private final MeterRegistry meterRegistry;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public WorkspaceSyncTargetProvider(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            ResourceReaderRepository resourceReaderRepository,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            ReviewScopeRepositoryRepository practiceReviewRepositoryTargetRepository,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry,
            PrivacyJournal journal) {
        this.meterRegistry = meterRegistry;
        this.journal = journal;
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.resourceReaderRepository = resourceReaderRepository;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.eventPublisher = eventPublisher;
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

    /**
     * A credential its provider refused pauses every sync of the scope until it is replaced or accepted
     * again: each call would only repeat the refusal. {@code IntegrationAttentionService} logs the change.
     */
    private boolean credentialRefused(long workspaceId) {
        return Stream.of(IntegrationKind.GITHUB, IntegrationKind.GITLAB)
                .flatMap(kind -> connectionService.findActive(workspaceId, kind).stream())
                .anyMatch(connection -> connection.getAttentionProblem() == Problem.CREDENTIAL_REVOKED);
    }

    private boolean syncable(Workspace workspace, IntegrationKind kind) {
        if (!credentialRefused(workspace.getId())) return true;
        meterRegistry
                .counter(
                        WorkspaceMetrics.WORKSPACE_SYNC_PAUSED,
                        "kind",
                        kind.name().toLowerCase(Locale.ROOT),
                        "reason",
                        "credential_revoked")
                .increment();
        return false;
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
                .map(ws -> {
                    // Apply repository filter to respect monitoring
                    // configuration (e.g., dev environment limits)
                    List<RepositoryToMonitor> monitors =
                            repositoryToMonitorRepository.findByWorkspaceId(scopeId).stream()
                                    .filter(allowedIn(ws))
                                    .toList();
                    List<SyncTarget> targets = monitors.stream()
                            .flatMap(rtm -> SyncTargetFactory.create(ws, rtm, connectionService).stream())
                            .toList();
                    if (targets.isEmpty() && !monitors.isEmpty()) {
                        log.info(
                                "Skipped sync targets: reason=noActiveConnection, workspaceId={}, monitorCount={}",
                                scopeId,
                                monitors.size());
                    }
                    return targets;
                })
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isScopeActiveForSync(Long scopeId) {
        return workspaceRepository
                .findById(scopeId)
                .map(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE && !credentialRefused(ws.getId()))
                .orElse(false);
    }

    @Override
    public List<Long> getActiveScopesForRepository(long repositoryNativeId, long providerId) {
        return repositoryToMonitorRepository.findByNativeIdOnSourceProvider(repositoryNativeId, providerId).stream()
                .map(RepositoryToMonitor::getWorkspace)
                .filter(Objects::nonNull)
                .map(Workspace::getId)
                .filter(this::isScopeActiveForSync)
                .toList();
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
    @Transactional(readOnly = true)
    public ReadersRead beginReadersProof(
            Long syncTargetId, long repositoryNativeId, long providerId, Instant requestedAt) {
        long generation = repositoryToMonitorRepository
                .findById(syncTargetId)
                .map(rtm -> rtm.getReaders().getProofGeneration())
                .orElse(-1L);
        return new ReadersRead(syncTargetId, repositoryNativeId, providerId, generation, requestedAt);
    }

    @Override
    @Transactional
    public void recordRepositoryPublic(ReadersRead read) {
        if (!readOfThisMonitor(read)) {
            return;
        }
        locked(read.syncTargetId(), null)
                .filter(rtm -> rtm.getNativeId() == read.repositoryNativeId())
                .ifPresent(rtm -> {
                    if (rtm.getReaders().confirmedPublic(read.generation(), read.requestedAt())) {
                        forgetReaders(rtm);
                    }
                });
    }

    /** Whether the read asked the provider of this monitor's workspace connection about exactly this repository. */
    private boolean readOfThisMonitor(ReadersRead read) {
        return repositoryToMonitorRepository
                .findByNativeIdOnSourceProvider(read.repositoryNativeId(), read.providerId())
                .stream()
                .anyMatch(monitor -> read.syncTargetId().equals(monitor.getId()));
    }

    @Override
    @Transactional
    public void recordRepositoryNotPublic(ReadersRead read) {
        locked(read.syncTargetId(), null)
                .filter(rtm -> rtm.getNativeId() == read.repositoryNativeId())
                .ifPresent(rtm -> rtm.getReaders().confirmedNotPublic());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean readersListingDue(Long syncTargetId, Instant now, Duration renewAfter) {
        return repositoryToMonitorRepository
                .findById(syncTargetId)
                .map(RepositoryToMonitor::getReaders)
                .map(readers -> readers.getVisibilityClass() != VisibilityClass.RESTRICTED
                        || readers.getProofState() != ProofState.PROVEN
                        || readers.getReadersSyncedAt() == null
                        || readers.getReadersSyncedAt().isBefore(now.minus(renewAfter)))
                .orElse(false);
    }

    @Override
    @Transactional
    public void recordRepositoryReaders(ReadersRead read, ReaderListing listing) {
        if (listing instanceof ReaderListing.Listed && !readOfThisMonitor(read)) {
            return;
        }
        locked(read.syncTargetId(), null)
                .filter(rtm -> rtm.getNativeId() == read.repositoryNativeId())
                .ifPresent(rtm -> {
                    ResourceReaders readers = rtm.getReaders();
                    switch (listing) {
                        case ReaderListing.Listed listed -> {
                            if (readers.readersListed(read.generation(), read.requestedAt())) {
                                forgetReaders(rtm);
                                resourceReaderRepository.insertReaders(
                                        workspaceIdOf(rtm),
                                        Objects.requireNonNull(rtm.getId()),
                                        listed.readerNativeIds().toArray(Long[]::new));
                            }
                        }
                        case ReaderListing.Stopped stopped -> readers.listingStopped(stopped.reason());
                        case ReaderListing.NotListable notListable -> {
                            readers.readersNotListable();
                            forgetReaders(rtm);
                        }
                    }
                });
    }

    /** A monitor holds readers only while a listing proves its class, so every answer that ends that proof drops them. */
    private void forgetReaders(RepositoryToMonitor rtm) {
        resourceReaderRepository.deleteReaders(workspaceIdOf(rtm), Objects.requireNonNull(rtm.getId()));
    }

    private static long workspaceIdOf(RepositoryToMonitor rtm) {
        return Objects.requireNonNull(Objects.requireNonNull(rtm.getWorkspace()).getId());
    }

    @Override
    @Transactional
    public void recordRepositoryPrivatized(long repositoryNativeId) {
        repositoryToMonitorRepository.narrowRepositoryMonitors(repositoryNativeId);
    }

    /**
     * The monitored repository, row-locked for this transaction and read again from the database, so its proof is the
     * one that other replicas committed, also when this transaction already holds the entity. With a workspace, a
     * monitor of another workspace is neither locked nor returned.
     */
    private Optional<RepositoryToMonitor> locked(Long syncTargetId, @Nullable Long workspaceId) {
        EntityManager entities = Objects.requireNonNull(entityManager);
        entities.flush();
        List<RepositoryToMonitor> found = entities.createQuery(
                        "SELECT m FROM RepositoryToMonitor m WHERE m.id = :id"
                                + " AND (:workspaceId IS NULL OR m.workspace.id = :workspaceId)",
                        RepositoryToMonitor.class)
                .setParameter("id", syncTargetId)
                .setParameter("workspaceId", workspaceId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RepositoryToMonitor rtm = found.getFirst();
        entities.refresh(rtm);
        return Optional.of(rtm);
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
    @Transactional
    public void recordRepositoryUnavailable(Long scopeId, Long syncTargetId) {
        Instant now = Instant.now();
        repositoryToMonitorRepository.recordUnavailable(scopeId, syncTargetId, now, now.plus(Duration.ofDays(1)));
        locked(syncTargetId, scopeId).ifPresent(rtm -> {
            rtm.getReaders().unavailable();
            forgetReaders(rtm);
        });
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
        // The organization login is the workspace's accountLogin, with no lookup of a linked organization.
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
            return workspace == null ? Optional.empty() : SyncTargetFactory.create(workspace, rtm, connectionService);
        });
    }

    @Override
    @Transactional
    public Optional<SyncTarget> restartCompletedBackfill(
            long workspaceId,
            long syncTargetId,
            int providerCount,
            long storedCount,
            boolean repairIncompleteCoverage) {
        if (repositoryToMonitorRepository.restartCompletedBackfill(
                        workspaceId, syncTargetId, providerCount, storedCount, repairIncompleteCoverage)
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
        removeMonitor(syncTargetId, true);
    }

    private void removeMonitor(Long syncTargetId, boolean releasingScope) {
        // The workspace's monitor set removes orphans. A monitor deleted while a loaded set still holds it would be
        // persisted again at flush, so it is removed from that set as well.
        repositoryToMonitorRepository.findById(syncTargetId).ifPresent(monitor -> {
            Workspace workspace = monitor.getWorkspace();
            List<Long> repositoryIds = repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(syncTargetId);
            if (workspace != null) {
                workspace.getRepositoriesToMonitor().remove(monitor);
            }
            repositoryToMonitorRepository.delete(monitor);
            repositoryToMonitorRepository.flush();
            if (workspace != null) {
                if (releasingScope) {
                    journal.record(
                            PrivacyJournalKind.REPOSITORY_REMOVED,
                            Map.of("workspaceId", workspace.getId(), "monitorId", syncTargetId));
                }
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
        removeMonitor(duplicateSyncTargetId, false);
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
        locked(syncTargetId, null).ifPresent(rtm -> {
            boolean dirty = false;

            // Replace an ID proven to belong to another provider instance's repository of the same name:
            // the scoped queries would hide the monitored repository.
            Long workspaceId = Objects.requireNonNull(rtm.getWorkspace()).getId();
            if (resolvedNativeId != null
                    && !rtm.getNativeId().equals(resolvedNativeId)
                    && holdsAnotherInstancesId(rtm, resolvedNativeId)) {
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
                    rtm.getReaders().identityChanged();
                    forgetReaders(rtm);
                    dirty = true;
                }
            }

            boolean nameChanged = false;
            boolean nameDiffers = resolvedNameWithOwner != null
                    && !resolvedNameWithOwner.isBlank()
                    && !resolvedNameWithOwner.equals(rtm.getNameWithOwner());
            // A name alone cannot identify a reassigned repository.
            boolean idAgrees = resolvedNativeId == null || rtm.getNativeId().equals(resolvedNativeId);
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
                // The monitor now names a repository that no GitHub workspace may hold. The withdrawal runs after
                // this commit, in a transaction for each workspace, so it sees the new name.
                Workspace owner = Objects.requireNonNull(rtm.getWorkspace());
                if (!hasActiveProvider(owner, IntegrationKind.GITLAB)
                        && workspaceScopeFilter.isExcluded(resolvedNameWithOwner)) {
                    eventPublisher.publishEvent(new RepositoryExcludedFromWorkspacesEvent(resolvedNameWithOwner));
                }
            }

            if (dirty) {
                repositoryToMonitorRepository.saveAndFlush(rtm);
            }
            eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(
                    workspaceId, repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(syncTargetId)));
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
    public void updateRecentWindow(
            Long syncTargetId, SyncCursorKind kind, SyncTargetProvider.@Nullable RecentWindow window) {
        repositoryToMonitorRepository.findById(syncTargetId).ifPresent(rtm -> {
            String cursor = window == null ? null : window.resumeCursor();
            Instant startedAt = window == null ? null : window.startedAt();
            switch (kind) {
                case ISSUE -> {
                    rtm.setIssueRecentCursor(cursor);
                    rtm.setIssueRecentWindowStart(startedAt);
                }
                case PULL_REQUEST -> {
                    rtm.setPullRequestRecentCursor(cursor);
                    rtm.setPullRequestRecentWindowStart(startedAt);
                }
            }
            repositoryToMonitorRepository.save(rtm);
        });
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
        List<Workspace> allWorkspaces = workspaceRepository.findAllByKind(WorkspaceKind.TEAM);

        return allWorkspaces.stream()
                .filter(ws -> ws.getStatus() == WorkspaceStatus.ACTIVE)
                .filter(ws -> hasActiveProvider(ws, kind))
                .filter(workspaceScopeFilter::isWorkspaceAllowed)
                .filter(ws -> syncable(ws, kind))
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
                .filter(ws -> syncable(ws, kind))
                .map(this::toSyncSession);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncStatistics getSyncStatistics() {
        List<Workspace> allWorkspaces = workspaceRepository.findAllByKind(WorkspaceKind.TEAM);

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

    /**
     * A GitLab project can share the excluded GitHub repository's path, and it is a different repository. A GitLab
     * workspace's monitors sit in its group, which may be a nested group that the organization filter names.
     */
    private Predicate<RepositoryToMonitor> allowedIn(Workspace workspace) {
        return hasActiveProvider(workspace, IntegrationKind.GITLAB)
                ? monitor -> workspaceScopeFilter.isRepositoryAllowed(workspace, monitor.getNameWithOwner())
                : workspaceScopeFilter::isGitHubRepositoryAllowed;
    }

    private SyncSession toSyncSession(Workspace workspace) {
        // Filter repositories by workspace scope
        List<SyncTarget> syncTargets = workspace.getRepositoriesToMonitor().stream()
                .filter(allowedIn(workspace))
                .flatMap(rtm -> SyncTargetFactory.create(workspace, rtm, connectionService).stream())
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
