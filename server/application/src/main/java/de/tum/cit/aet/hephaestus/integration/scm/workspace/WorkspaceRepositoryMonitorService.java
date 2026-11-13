package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationSuspensionTracker;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.events.RepositoryAboutToBeDeletedEvent;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.micrometer.common.util.StringUtils;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@WorkspaceAgnostic("Manages workspace-repository mapping configuration")
public class WorkspaceRepositoryMonitorService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRepositoryMonitorService.class);
    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderRepository gitProviderRepository;

    private final WorkspaceScopeFilter workspaceScopeFilter;

    /**
     * Installation-bound operations dispatch by the workspace's bound kind. A missing
     * impl for a given kind is treated as "this kind doesn't support installation-bound
     * operations" (e.g. GitLab PAT) rather than an error.
     */
    private final Map<IntegrationKind, InstallationSuspensionTracker> suspensionTrackers;

    private final Map<IntegrationKind, InstallationRepositoryEnumerator> installationEnumerators;
    private final Map<IntegrationKind, WorkspaceDataSyncTrigger> dataSyncTriggers;
    private final ApplicationEventPublisher eventPublisher;

    private final ConnectionService connectionService;
    private final ConnectionRepository connectionRepository;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readTransactionTemplate;
    private final ManualRepositoryMonitorService manualAdds;

    public WorkspaceRepositoryMonitorService(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            IdentityProviderRepository gitProviderRepository,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            ConnectionRepository connectionRepository,
            List<InstallationSuspensionTracker> suspensionTrackerList,
            List<InstallationRepositoryEnumerator> installationEnumeratorList,
            List<WorkspaceDataSyncTrigger> dataSyncTriggerList,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate,
            ManualRepositoryMonitorService manualAdds) {
        this.manualAdds = manualAdds;
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.readTransactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()), transactionTemplate);
        this.readTransactionTemplate.setReadOnly(true);

        Map<IntegrationKind, InstallationSuspensionTracker> suspensionMap = new EnumMap<>(IntegrationKind.class);
        for (InstallationSuspensionTracker t : suspensionTrackerList) {
            suspensionMap.put(t.kind(), t);
        }
        this.suspensionTrackers = suspensionMap;

        Map<IntegrationKind, InstallationRepositoryEnumerator> enumeratorMap = new EnumMap<>(IntegrationKind.class);
        for (InstallationRepositoryEnumerator e : installationEnumeratorList) {
            enumeratorMap.put(e.kind(), e);
        }
        this.installationEnumerators = enumeratorMap;

        Map<IntegrationKind, WorkspaceDataSyncTrigger> triggerMap = new EnumMap<>(IntegrationKind.class);
        for (WorkspaceDataSyncTrigger t : dataSyncTriggerList) {
            triggerMap.put(t.kind(), t);
        }
        this.dataSyncTriggers = triggerMap;
    }

    @Transactional(readOnly = true)
    public List<String> getMonitoredRepositories(String slug) {
        Workspace workspace = requireWorkspace(slug);
        log.debug("Retrieved monitored repositories: workspaceId={}", workspace.getId());
        return workspace.getRepositoriesToMonitor().stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getMonitoredRepositories(WorkspaceContext workspaceContext) {
        String slug = requireSlug(workspaceContext);
        Workspace workspace = requireWorkspace(slug);
        log.debug("Retrieved monitored repositories: workspaceId={}", workspace.getId());
        return workspace.getRepositoriesToMonitor().stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .toList();
    }

    public void addRepositoryToMonitor(String slug, String nameWithOwner)
            throws RepositoryAlreadyMonitoredException, EntityNotFoundException {
        Workspace workspace = requireWorkspace(slug);
        if (isGitHubAppWorkspace(workspace)) {
            throw new RepositoryManagementNotAllowedException(slug);
        }
        RepositoryToMonitor monitor = manualAdds.add(workspace, nameWithOwner);
        log.info(
                "Added repository to monitor: nameWithOwner={}, workspaceId={}",
                LoggingUtils.sanitizeForLog(nameWithOwner),
                workspace.getId());
        announceRepositoryMonitor(workspace, monitor, false);
    }

    public void addRepositoryToMonitor(WorkspaceContext workspaceContext, String nameWithOwner)
            throws RepositoryAlreadyMonitoredException, EntityNotFoundException {
        addRepositoryToMonitor(requireSlug(workspaceContext), nameWithOwner);
    }

    public void addRepositoriesToMonitor(String slug, Collection<String> namesWithOwners)
            throws RepositoryAlreadyMonitoredException, EntityNotFoundException {
        for (String nameWithOwner : namesWithOwners) {
            addRepositoryToMonitor(slug, nameWithOwner);
        }
    }

    public void addRepositoriesToMonitor(WorkspaceContext workspaceContext, Collection<String> namesWithOwners)
            throws RepositoryAlreadyMonitoredException, EntityNotFoundException {
        addRepositoriesToMonitor(requireSlug(workspaceContext), namesWithOwners);
    }

    public void removeRepositoryFromMonitor(String slug, String nameWithOwner) throws EntityNotFoundException {
        transactionTemplate.executeWithoutResult(status -> {
            Workspace workspace = requireWorkspace(slug);

            boolean gitLab = connectionService
                    .findActiveProviderKind(workspace.getId())
                    .map(kind -> kind == IntegrationKind.GITLAB)
                    .orElseGet(() -> connectionRepository
                            .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                                    workspace.getId(), IntegrationKind.GITLAB, IntegrationState.SUSPENDED)
                            .isPresent());
            if (gitLab) {
                // Takes the GitLab lock, so a discovery that runs at the same time finishes first.
                if (connectionService
                        .updateGitLabProjectExclusions(workspace.getId(), exclusions -> exclusions)
                        .isEmpty()) {
                    throw new RepositoryProviderNotConnectedException(slug);
                }
            }

            if (isGitHubAppWorkspace(workspace)) {
                throw new RepositoryManagementNotAllowedException(slug);
            }

            // Read after the GitLab lock, so a monitor that a concurrent discovery added while Stop waited is seen.
            RepositoryToMonitor repositoryToMonitor = repositoryToMonitorRepository
                    .findByWorkspaceIdAndNameWithOwner(workspace.getId(), nameWithOwner)
                    .orElse(null);

            if (repositoryToMonitor == null) {
                log.debug(
                        "Skipped repository monitor removal: reason=notMonitored,"
                                + " nameWithOwner={}, workspaceId={}",
                        LoggingUtils.sanitizeForLog(nameWithOwner),
                        workspace.getId());
                throw new EntityNotFoundException("Repository", nameWithOwner);
            }

            log.info(
                    "Removed repository from monitor: nameWithOwner={}, workspaceId={}",
                    LoggingUtils.sanitizeForLog(nameWithOwner),
                    workspace.getId());

            List<Long> repositoryIds =
                    repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(repositoryToMonitor.getId());
            if (gitLab) {
                Long stoppedNativeId = repositoryToMonitor.getNativeId();
                if (connectionService
                        .updateGitLabProjectExclusions(
                                workspace.getId(), exclusions -> exclusions.stop(stoppedNativeId, UUID.randomUUID()))
                        .isEmpty()) {
                    throw new RepositoryProviderNotConnectedException(slug);
                }
            }
            // A GitLab Stop keeps the work the workspace indexed; Remove on the stopped project releases it.
            deleteRepositoryMonitor(workspace, repositoryToMonitor, gitLab);
            repositoryIds.forEach(this::deleteRepositoryIfOrphaned);
        });
    }

    public void removeRepositoryFromMonitor(WorkspaceContext workspaceContext, String nameWithOwner)
            throws EntityNotFoundException {
        removeRepositoryFromMonitor(requireSlug(workspaceContext), nameWithOwner);
    }

    /**
     * Provider events preserve the native identity; a reused path is not the removed repository.
     */
    @Transactional
    public Optional<Workspace> removeRepositoryMonitorForInstallation(
            long installationId, String nameWithOwner, @Nullable Long expectedNativeId) {
        return removeRepositoryMonitorInTransaction(installationId, nameWithOwner, expectedNativeId);
    }

    private Optional<Workspace> removeRepositoryMonitorInTransaction(
            long installationId, String nameWithOwner, @Nullable Long expectedNativeId) {
        if (StringUtils.isBlank(nameWithOwner)) {
            return Optional.empty();
        }

        var workspaceOpt = workspaceRepository.findActiveByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty()) {
            return Optional.empty();
        }
        Workspace workspace = workspaceOpt.get();
        Long workspaceId = workspace.getId();
        // A path can have been reused since the event. An omitted ID cannot authorize removal.
        if (expectedNativeId == null) {
            log.warn(
                    "Skipped repository removal: reason=missingNativeId, installationId={}, workspaceId={}",
                    installationId,
                    workspaceId);
            return Optional.empty();
        }
        var monitorOpt = repositoryToMonitorRepository.findOneByWorkspaceIdAndNativeId(workspaceId, expectedNativeId);
        if (monitorOpt.isEmpty()) {
            log.debug("Skipped repository removal: reason=notMonitored, workspaceId={}", workspaceId);
            return workspaceOpt;
        }
        removeMonitorAndOrphanedRepositories(workspace, monitorOpt.get());
        return workspaceOpt;
    }

    private void removeMonitorAndOrphanedRepositories(Workspace workspace, RepositoryToMonitor monitor) {
        List<Long> repositoryIds = repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(monitor.getId());
        deleteRepositoryMonitor(workspace, monitor, false);
        repositoryIds.forEach(this::deleteRepositoryIfOrphaned);
    }

    @Transactional
    public Optional<Workspace> removeAllRepositoriesFromMonitor(long installationId, boolean deleteRepositories) {
        return removeAllRepositoriesInTransaction(installationId, deleteRepositories);
    }

    private Optional<Workspace> removeAllRepositoriesInTransaction(long installationId, boolean deleteRepositories) {
        var workspaceOpt = workspaceRepository.findActiveByInstallationIdForUpdate(installationId);
        workspaceOpt.ifPresent(workspace -> {
            List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
            for (RepositoryToMonitor monitor : monitors) {
                List<Long> repositoryIds = deleteRepositories
                        ? repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(monitor.getId())
                        : List.of();
                deleteRepositoryMonitor(workspace, monitor, false);
                repositoryIds.forEach(this::deleteRepositoryIfOrphaned);
            }
        });
        return workspaceOpt;
    }

    @Transactional
    public void ensureRepositoryAndMonitorFromSnapshot(
            long installationId, ProvisioningListener.RepositorySnapshot snapshot) {
        if (snapshot == null || StringUtils.isBlank(snapshot.nameWithOwner())) {
            return;
        }

        var workspaceOpt = workspaceRepository.findActiveByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty()) {
            return;
        }

        Workspace workspace = workspaceOpt.get();

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException(
                        "GitHub is not set up on this instance. Ask your" + " instance operator to set it up."));

        if (ensureRepositoryFromSnapshot(
                workspace, provider, snapshot.id(), snapshot.nameWithOwner(), snapshot.name(), snapshot.isPrivate())) {
            ensureRepositoryMonitorInternal(workspace, snapshot.id(), snapshot.nameWithOwner(), true);
        }
    }

    /**
     * Enumerate all repositories available to the installation when repository selection is ALL and
     * ensure monitors exist.
     *
     * @param installationId the GitHub App installation ID
     * @param protectedRepositories repositories to keep even if not in installation (can be null)
     * @param deferSync if true, skip immediate sync (use during provisioning when activation will
     *     sync in bulk)
     */
    @Transactional(propagation = Propagation.NEVER)
    public void ensureAllInstallationRepositoriesCovered(
            long installationId, @Nullable Collection<String> protectedRepositories, boolean deferSync) {
        if (isInstallationSuspended(installationId)) {
            log.info("Skipped repository enumeration: reason=installationSuspended, installationId={}", installationId);
            return;
        }
        InstallationRepositoryEnumerator enumerator = installationEnumerators.get(IntegrationKind.GITHUB);
        if (enumerator == null) return;
        var captured = readTransactionTemplate.execute(status -> connectionRepository
                .findActiveInstallationSnapshot(Long.toString(installationId))
                .map(connection -> new EnumerationSnapshot(
                        connection,
                        List.copyOf(
                                repositoryToMonitorRepository.findSnapshotsByWorkspaceId(connection.workspaceId())))));
        if (captured == null || captured.isEmpty()) return;
        var snapshot = captured.orElseThrow();
        var authority = snapshot.connection();
        var snapshots = enumerator.enumerate(installationId);
        if (snapshots.isEmpty()) {
            log.warn(
                    "Skipped repository enumeration: reason=noDataReturned, installationId={}, workspaceId={}",
                    installationId,
                    authority.workspaceId());
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            var workspace = workspaceRepository
                    .findByIdForUpdate(authority.workspaceId())
                    .orElse(null);
            if (workspace == null || workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
                discardedEnumeration(installationId, authority, "workspaceChanged");
                return;
            }
            if (connectionRepository
                    .findAndLockLifecycleConnection(authority.connectionId(), authority.workspaceId())
                    .isEmpty()) {
                discardedEnumeration(installationId, authority, "connectionMissing");
                return;
            }
            var connection = connectionRepository
                    .findByIdAndWorkspaceId(authority.connectionId(), authority.workspaceId())
                    .orElseThrow();
            if (connection.getState() != IntegrationState.ACTIVE
                    || connection.getLifecycleGeneration() != authority.lifecycleGeneration()
                    || !Objects.equals(connection.getVersion(), authority.version())
                    || !(connection.getConfig() instanceof ConnectionConfig.GitHubAppConfig config)
                    || !Objects.equals(config.installationId(), installationId)
                    || isInstallationSuspended(installationId)) {
                discardedEnumeration(installationId, authority, "connectionChanged");
                return;
            }
            if (!repositoryToMonitorRepository
                    .findSnapshotsByWorkspaceId(authority.workspaceId())
                    .equals(snapshot.monitors())) {
                discardedEnumeration(installationId, authority, "monitorsChanged");
                return;
            }
            reconcileInstallationRepositories(workspace, installationId, snapshots, protectedRepositories, deferSync);
        });
    }

    private record EnumerationSnapshot(
            ConnectionRepository.InstallationSnapshot connection,
            List<RepositoryToMonitorRepository.MonitorSnapshot> monitors) {}

    private void discardedEnumeration(
            long installationId, ConnectionRepository.InstallationSnapshot authority, String reason) {
        log.info(
                "Skipped repository enumeration result: reason={}, installationId={}, workspaceId={}, connectionId={}",
                reason,
                installationId,
                authority.workspaceId(),
                authority.connectionId());
    }

    private void reconcileInstallationRepositories(
            Workspace workspace,
            long installationId,
            List<InstallationRepositoryEnumerator.InstallationRepository> snapshots,
            @Nullable Collection<String> protectedRepositories,
            boolean deferSync) {
        // Filter before processing so excluded repositories do not acquire mirror rows.
        List<InstallationRepositoryEnumerator.InstallationRepository> allowedSnapshots = snapshots.stream()
                .filter(s -> workspaceScopeFilter.isRepositoryAllowed(s.nameWithOwner()))
                .toList();

        if (allowedSnapshots.size() < snapshots.size()) {
            log.debug(
                    "Filtered repositories during enumeration: allowed={}, total={}," + " installationId={}",
                    allowedSnapshots.size(),
                    snapshots.size(),
                    installationId);
        }

        Set<Long> desiredIds = allowedSnapshots.stream()
                .map(InstallationRepositoryEnumerator.InstallationRepository::id)
                .collect(Collectors.toSet());
        Set<String> protectedNames = protectedRepositories == null
                ? Set.of()
                : protectedRepositories.stream()
                        .filter(name -> !StringUtils.isBlank(name))
                        .filter(name -> workspaceScopeFilter.isRepositoryAllowed(name))
                        .map(name -> name.toLowerCase(Locale.ENGLISH))
                        .collect(Collectors.toSet());

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException(
                        "GitHub is not set up on this instance. Ask your" + " instance operator to set it up."));

        // Removal runs first so a path the installation now uses for another repository is free before it is
        // monitored. A monitor with a native id is kept by that id, so a renamed repository keeps its monitor.
        repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(monitor -> !StringUtils.isBlank(monitor.getNameWithOwner()))
                .filter(monitor -> {
                    String name = monitor.getNameWithOwner().toLowerCase(Locale.ENGLISH);
                    boolean listed = desiredIds.contains(monitor.getNativeId());
                    return !listed && !protectedNames.contains(name);
                })
                .forEach(monitor -> removeMonitorAndOrphanedRepositories(workspace, monitor));

        allowedSnapshots.forEach(snapshot -> {
            if (ensureRepositoryFromSnapshot(
                    workspace,
                    provider,
                    snapshot.id(),
                    snapshot.nameWithOwner(),
                    snapshot.name(),
                    snapshot.isPrivate())) {
                ensureRepositoryMonitorInternal(workspace, snapshot.id(), snapshot.nameWithOwner(), deferSync);
            }
        });
    }

    /**
     * Deletes an exact source repository only after its last matching monitor is removed and no
     * workspace still keeps it indexed, as a stopped GitLab project is kept until its work is
     * removed. Callers capture its internal id before removal and flush that removal before this
     * check. A same-named row on another provider is neither a deletion target nor proof that this
     * source remains monitored.
     */
    public void deleteRepositoryIfOrphaned(Long repositoryId) {
        long monitorCount = repositoryToMonitorRepository.countForRepository(repositoryId);
        if (monitorCount > 0) {
            log.debug(
                    "Skipped repository deletion: reason=stillMonitored, repositoryId={}," + " monitorCount={}",
                    repositoryId,
                    monitorCount);
            return;
        }
        if (repositoryRepository.acquireDeletionClaim(repositoryId).isEmpty()) return;
        // A publisher can claim the source between the fast check and our deletion claim.
        if (repositoryToMonitorRepository.countForRepository(repositoryId) > 0) return;
        if (repositoryRepository.isIndexedByAnyWorkspace(repositoryId)) {
            log.debug("Skipped repository deletion: reason=stillIndexed, repositoryId={}", repositoryId);
            return;
        }
        repositoryRepository.findById(repositoryId).ifPresent(repository -> {
            eventPublisher.publishEvent(new RepositoryAboutToBeDeletedEvent(repositoryId));
            repositoryRepository.delete(repository);
            log.debug("Deleted orphaned repository: repositoryId={}", repositoryId);
        });
    }

    private void persistRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor, boolean deferSync) {
        repositoryToMonitorRepository.saveAndFlush(monitor);
        workspace.getRepositoriesToMonitor().add(monitor);
        workspaceRepository.save(workspace);
        eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(
                workspace.getId(), repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(monitor.getId())));
        announceRepositoryMonitor(workspace, monitor, deferSync);
    }

    private void announceRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor, boolean deferSync) {
        boolean repositoryAllowed = workspaceScopeFilter.isRepositoryAllowed(monitor);
        if (deferSync) {
            log.debug(
                    "Persisted repository with deferred sync: repoName={}",
                    LoggingUtils.sanitizeForLog(monitor.getNameWithOwner()));
            return;
        }
        if (repositoryAllowed) {
            // Sync the just-persisted target through the bound kind's trigger (async, looked up
            // by monitor id). No trigger for the active kind = no sync wiring, not an error.
            connectionService.findActiveProviderKind(workspace.getId()).ifPresent(kind -> {
                WorkspaceDataSyncTrigger trigger = dataSyncTriggers.get(kind);
                if (trigger != null) {
                    trigger.syncSingleSyncTarget(monitor.getId());
                } else {
                    log.debug(
                            "Skipped single-target sync: reason=noTriggerForKind," + " repoName={}, kind={}",
                            LoggingUtils.sanitizeForLog(monitor.getNameWithOwner()),
                            kind);
                }
            });
        } else {
            log.debug(
                    "Persisted repository without sync: reason=filteredByScope, repoName={}",
                    LoggingUtils.sanitizeForLog(monitor.getNameWithOwner()));
        }
    }

    private void deleteRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor, boolean retainIndex) {
        List<Long> repositoryIds = repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(monitor.getId());
        // Remove from collection first - orphanRemoval=true will handle deletion
        workspace.getRepositoriesToMonitor().remove(monitor);
        workspaceRepository.saveAndFlush(workspace);
        eventPublisher.publishEvent(
                new RepositoryMonitoringChangedEvent(workspace.getId(), repositoryIds, retainIndex));
    }

    /**
     * Callers hold the workspace lock and have recorded the snapshot on the mirrored repository. The
     * provider-stable id finds the monitor. The monitor takes the installation's path when no other monitor holds it.
     */
    private void ensureRepositoryMonitorInternal(
            Workspace workspace, long nativeId, String nameWithOwner, boolean deferSync) {
        Long workspaceId = workspace.getId();
        var existing = repositoryToMonitorRepository.findOneByWorkspaceIdAndNativeId(workspaceId, nativeId);
        if (existing.isEmpty()) {
            if (repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(workspaceId, nameWithOwner)) {
                log.warn(
                        "Skipped repository monitor: reason=nameHeldByAnotherMonitor, workspaceId={}, nativeId={}",
                        workspaceId,
                        nativeId);
                return;
            }
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setNameWithOwner(nameWithOwner);
            monitor.setNativeId(nativeId);
            monitor.setWorkspace(workspace);
            persistRepositoryMonitor(workspace, monitor, deferSync);
            return;
        }

        RepositoryToMonitor monitor = existing.get();
        boolean renamed = !nameWithOwner.equals(monitor.getNameWithOwner());
        if (renamed
                && repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwnerAndIdNot(
                        workspaceId, nameWithOwner, monitor.getId())) {
            log.warn(
                    "Skipped repository monitor re-key: reason=nameHeldByAnotherMonitor, workspaceId={},"
                            + " syncTargetId={}, nativeId={}",
                    workspaceId,
                    monitor.getId(),
                    nativeId);
            renamed = false;
        }
        if (renamed) {
            log.info(
                    "Re-keyed repository monitor to the installation's path: workspaceId={}, syncTargetId={},"
                            + " oldName={}, newName={}",
                    workspaceId,
                    monitor.getId(),
                    LoggingUtils.sanitizeForLog(monitor.getNameWithOwner()),
                    LoggingUtils.sanitizeForLog(nameWithOwner));
            monitor.setNameWithOwner(nameWithOwner);
        }
        if (renamed) {
            repositoryToMonitorRepository.saveAndFlush(monitor);
        }
        eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(
                workspaceId, repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(monitor.getId())));
    }

    /** Whether the mirrored repository with this id now has the snapshot's path. */
    private boolean ensureRepositoryFromSnapshot(
            Workspace workspace,
            IdentityProvider provider,
            long nativeId,
            String nameWithOwner,
            String name,
            boolean isPrivate) {
        if (StringUtils.isBlank(nameWithOwner)) {
            return false;
        }
        boolean recorded = repositoryRepository.upsertFromSnapshot(
                nativeId,
                Objects.requireNonNull(provider.getId()),
                nameWithOwner,
                name,
                isPrivate,
                workspace.getOrganizationId() != null ? workspace.getOrganizationId() : null);
        if (!recorded) {
            log.warn(
                    "Skipped repository from installation: reason=pathHeldByAnotherRepository, workspaceId={},"
                            + " nativeId={}, repoName={}",
                    workspace.getId(),
                    nativeId,
                    LoggingUtils.sanitizeForLog(nameWithOwner));
        }
        return recorded;
    }

    private Workspace requireWorkspace(String slug) {
        if (StringUtils.isBlank(slug)) {
            throw new IllegalArgumentException("Workspace slug must not be blank.");
        }
        return workspaceRepository
                .findByWorkspaceSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", slug));
    }

    private String requireSlug(WorkspaceContext workspaceContext) {
        Objects.requireNonNull(workspaceContext, "WorkspaceContext must not be null");
        String slug = workspaceContext.slug();
        if (StringUtils.isBlank(slug)) {
            throw new IllegalArgumentException("Workspace context slug must not be blank.");
        }
        return slug;
    }

    private boolean isInstallationSuspended(long installationId) {
        for (InstallationSuspensionTracker t : suspensionTrackers.values()) {
            if (t.isInstallationMarkedSuspended(installationId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGitHubAppWorkspace(Workspace workspace) {
        return (connectionService
                        .findActiveProviderKind(workspace.getId())
                        .map(k -> k == IntegrationKind.GITHUB)
                        .orElse(false)
                && connectionService
                        .findActiveGitHubAppConfig(workspace.getId())
                        .isPresent());
    }
}
