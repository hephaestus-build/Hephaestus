package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationSuspensionTracker;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.events.RepositoryAboutToBeDeletedEvent;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.micrometer.common.util.StringUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@WorkspaceAgnostic("Manages workspace-repository mapping configuration")
public class WorkspaceRepositoryMonitorService {

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRepositoryMonitorService.class);
    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final NatsConnectionProperties natsProperties;

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderRepository gitProviderRepository;

    // natsConsumerService absent under webhook profile.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;
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
    private final ManualRepositoryMonitorService manualAdds;

    public WorkspaceRepositoryMonitorService(
            NatsConnectionProperties natsProperties,
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            IdentityProviderRepository gitProviderRepository,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            ConnectionRepository connectionRepository,
            List<InstallationSuspensionTracker> suspensionTrackerList,
            List<InstallationRepositoryEnumerator> installationEnumeratorList,
            List<WorkspaceDataSyncTrigger> dataSyncTriggerList,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate,
            ManualRepositoryMonitorService manualAdds) {
        this.natsProperties = natsProperties;
        this.manualAdds = manualAdds;
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.natsConsumerService = natsConsumerService;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;

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
        log.debug(
                "Retrieved monitored repositories: workspaceId={}, workspaceSlug={}",
                workspace.getId(),
                LoggingUtils.sanitizeForLog(slug));
        return workspace.getRepositoriesToMonitor().stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getMonitoredRepositories(WorkspaceContext workspaceContext) {
        String slug = requireSlug(workspaceContext);
        Workspace workspace = requireWorkspace(slug);
        log.debug(
                "Retrieved monitored repositories: workspaceId={}, workspaceSlug={}",
                workspace.getId(),
                LoggingUtils.sanitizeForLog(slug));
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
                var locked = connectionService
                        .updateGitLabProjectExclusions(workspace.getId(), exclusions -> exclusions)
                        .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
                // A concurrent discovery may have changed the eager monitor set while Stop waited for the lock.
                Objects.requireNonNull(entityManager).refresh(locked.getWorkspace());
            }

            if (isGitHubAppWorkspace(workspace)) {
                throw new RepositoryManagementNotAllowedException(slug);
            }

            RepositoryToMonitor repositoryToMonitor = workspace.getRepositoriesToMonitor().stream()
                    .filter(r -> r.getNameWithOwner().equals(nameWithOwner))
                    .findFirst()
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
                Long stoppedNativeId = repositoryToMonitor.getNativeId() != null
                        ? repositoryToMonitor.getNativeId()
                        : repositoryIds.size() == 1
                                ? repositoryRepository
                                        .findById(repositoryIds.getFirst())
                                        .map(Repository::getNativeId)
                                        .orElse(null)
                                : null;
                String stoppedPath = repositoryToMonitor.getNameWithOwner();
                if (connectionService
                        .updateGitLabProjectExclusions(
                                workspace.getId(),
                                exclusions -> stoppedNativeId == null
                                        ? exclusions.exclude(null, stoppedPath)
                                        : exclusions.stop(stoppedNativeId, stoppedPath, UUID.randomUUID()))
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
        // The id names the removed repository even after a rename; the path alone may name a repository reusing it.
        var monitorOpt = expectedNativeId == null
                ? repositoryToMonitorRepository.findByWorkspaceIdAndNameWithOwner(workspaceId, nameWithOwner)
                : repositoryToMonitorRepository
                        .findOneByWorkspaceIdAndNativeId(workspaceId, expectedNativeId)
                        .or(() -> repositoryToMonitorRepository
                                .findByWorkspaceIdAndNameWithOwnerAndNativeIdIsNull(workspaceId, nameWithOwner)
                                .filter(monitor ->
                                        repositoryToMonitorRepository
                                                .findSourceRepositoryIdsOfMonitor(monitor.getId())
                                                .stream()
                                                .map(repositoryRepository::findById)
                                                .flatMap(Optional::stream)
                                                .anyMatch(repository ->
                                                        expectedNativeId.equals(repository.getNativeId()))));
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
    @Transactional
    public void ensureAllInstallationRepositoriesCovered(
            long installationId, @Nullable Collection<String> protectedRepositories, boolean deferSync) {
        // Suspended workspaces must not restart syncs when NATS replays creation events.
        if (isInstallationSuspended(installationId)) {
            log.info(
                    "Skipped repository enumeration: reason=installationSuspended," + " installationId={}",
                    installationId);
            return;
        }

        var workspaceOpt = workspaceRepository.findActiveByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty()) {
            return;
        }

        Workspace workspace = workspaceOpt.get();
        if (!isGitHubAppWorkspace(workspace)) {
            return;
        }

        InstallationRepositoryEnumerator enumerator = installationEnumerators.get(IntegrationKind.GITHUB);
        if (enumerator == null) {
            log.debug(
                    "Skipped repository enumeration: reason=noEnumeratorForKind, installationId={}," + " kind={}",
                    installationId,
                    IntegrationKind.GITHUB);
            return;
        }
        var snapshots = enumerator.enumerate(installationId);
        if (snapshots.isEmpty()) {
            log.warn(
                    "Skipped repository enumeration: reason=noDataReturned, installationId={}," + " workspaceSlug={}",
                    installationId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return;
        }

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
        Set<String> desiredNames = allowedSnapshots.stream()
                .map(snapshot -> snapshot.nameWithOwner())
                .filter(name -> !StringUtils.isBlank(name))
                .map(name -> name.toLowerCase(Locale.ENGLISH))
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
                    boolean listed = monitor.getNativeId() != null
                            ? desiredIds.contains(monitor.getNativeId())
                            : desiredNames.contains(name);
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

    private boolean shouldUseNats(Workspace workspace) {
        return natsProperties.enabled() && workspace != null;
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

    // Consumers read committed monitors; refresh only after commit, never after rollback.
    private void announceRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor, boolean deferSync) {
        boolean repositoryAllowed = workspaceScopeFilter.isRepositoryAllowed(monitor);
        if (repositoryAllowed) {
            refreshScopeConsumerAfterCommit(workspace);
        }
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
        refreshScopeConsumerAfterCommit(workspace);
    }

    /** The consumer builds its subject filters from committed monitors, so it is refreshed only after commit. */
    private void refreshScopeConsumerAfterCommit(Workspace workspace) {
        if (shouldUseNats(workspace)) {
            Long workspaceId = workspace.getId();
            afterCommit(() -> natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspaceId)));
        }
    }

    /**
     * Callers hold the workspace lock and have recorded the snapshot on the mirrored repository. The
     * provider-stable id finds the monitor; GitHub paths are case-insensitive, so an unresolved monitor at the
     * path in any casing is adopted. The monitor takes the installation's path when no other monitor holds it.
     */
    private void ensureRepositoryMonitorInternal(
            Workspace workspace, long nativeId, String nameWithOwner, boolean deferSync) {
        Long workspaceId = workspace.getId();
        var existing = repositoryToMonitorRepository.findOneByWorkspaceIdAndNativeId(workspaceId, nativeId);
        if (existing.isEmpty()) {
            List<RepositoryToMonitor> unresolved =
                    repositoryToMonitorRepository.findByWorkspaceIdAndNameWithOwnerIgnoreCaseAndNativeIdIsNull(
                            workspaceId, nameWithOwner);
            if (unresolved.size() > 1) {
                log.warn(
                        "Skipped repository monitor: reason=ambiguousUnresolvedMonitors, workspaceId={},"
                                + " syncTargetIds={}, nativeId={}",
                        workspaceId,
                        unresolved.stream().map(RepositoryToMonitor::getId).toList(),
                        nativeId);
                return;
            }
            existing = unresolved.isEmpty() ? Optional.empty() : Optional.of(unresolved.getFirst());
        }
        if (existing.isEmpty()) {
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setNameWithOwner(nameWithOwner);
            monitor.setNativeId(nativeId);
            monitor.setWorkspace(workspace);
            persistRepositoryMonitor(workspace, monitor, deferSync);
            return;
        }

        RepositoryToMonitor monitor = existing.get();
        boolean adopted = monitor.getNativeId() == null;
        if (adopted) {
            monitor.setNativeId(nativeId);
        }
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
            refreshScopeConsumerAfterCommit(workspace);
        }
        if (adopted || renamed) {
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
