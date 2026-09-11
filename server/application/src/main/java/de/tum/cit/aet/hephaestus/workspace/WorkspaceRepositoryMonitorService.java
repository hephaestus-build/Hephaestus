package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationSuspensionTracker;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.events.RepositoryAboutToBeDeletedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.exception.RepositoryAlreadyMonitoredException;
import de.tum.cit.aet.hephaestus.workspace.exception.RepositoryManagementNotAllowedException;
import io.micrometer.common.util.StringUtils;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages monitored repositories within workspaces. All methods take workspace
 * slug/context as parameters to identify which workspace to operate on.
 */
@Service
@WorkspaceAgnostic("Manages workspace-repository mapping configuration")
public class WorkspaceRepositoryMonitorService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRepositoryMonitorService.class);

    private final NatsConnectionProperties natsProperties;

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderRepository gitProviderRepository;

    // natsConsumerService absent under webhook profile.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final GitRepositoryManager gitRepositoryManager;

    /**
     * Per-kind ports. The workspace module never imports a vendor-specific service —
     * installation-bound operations dispatch by the workspace's bound kind. A missing
     * impl for a given kind is treated as "this kind doesn't support installation-bound
     * operations" (e.g. GitLab PAT) rather than an error.
     */
    private final Map<IntegrationKind, InstallationSuspensionTracker> suspensionTrackers;

    private final Map<IntegrationKind, InstallationRepositoryEnumerator> installationEnumerators;
    private final Map<IntegrationKind, WorkspaceDataSyncTrigger> dataSyncTriggers;
    private final ApplicationEventPublisher eventPublisher;

    private final ConnectionService connectionService;

    public WorkspaceRepositoryMonitorService(
            NatsConnectionProperties natsProperties,
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            IdentityProviderRepository gitProviderRepository,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            WorkspaceScopeFilter workspaceScopeFilter,
            GitRepositoryManager gitRepositoryManager,
            ConnectionService connectionService,
            List<InstallationSuspensionTracker> suspensionTrackerList,
            List<InstallationRepositoryEnumerator> installationEnumeratorList,
            List<WorkspaceDataSyncTrigger> dataSyncTriggerList,
            ApplicationEventPublisher eventPublisher) {
        this.natsProperties = natsProperties;
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.natsConsumerService = natsConsumerService;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.gitRepositoryManager = gitRepositoryManager;
        this.connectionService = connectionService;
        this.eventPublisher = eventPublisher;

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

        if (workspace.getRepositoriesToMonitor().stream()
                .anyMatch(r -> r.getNameWithOwner().equals(nameWithOwner))) {
            log.debug(
                    "Skipped repository monitor addition: reason=alreadyMonitored, nameWithOwner={}, workspaceId={}",
                    LoggingUtils.sanitizeForLog(nameWithOwner),
                    workspace.getId());
            throw new RepositoryAlreadyMonitoredException(nameWithOwner);
        }

        // For GitLab PAT workspaces, the repo may not be synced yet — allow adding by name.
        if (connectionService
                .findActiveProviderKind(workspace.getId())
                .map(kind -> kind != IntegrationKind.GITLAB)
                .orElse(true)) {
            var repository = findRepository(nameWithOwner);
            if (repository.isEmpty()) {
                log.debug(
                        "Skipped repository monitor addition: reason=repositoryNotFound, nameWithOwner={}",
                        LoggingUtils.sanitizeForLog(nameWithOwner));
                throw new EntityNotFoundException("Repository", nameWithOwner);
            }
        }

        log.info(
                "Added repository to monitor: nameWithOwner={}, workspaceId={}",
                LoggingUtils.sanitizeForLog(nameWithOwner),
                workspace.getId());

        RepositoryToMonitor repositoryToMonitor = new RepositoryToMonitor();
        repositoryToMonitor.setNameWithOwner(nameWithOwner);
        repositoryToMonitor.setNativeId(resolveNativeId(nameWithOwner));
        repositoryToMonitor.setWorkspace(workspace);
        persistRepositoryMonitor(workspace, repositoryToMonitor);
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
        Workspace workspace = requireWorkspace(slug);

        if (isGitHubAppWorkspace(workspace)) {
            throw new RepositoryManagementNotAllowedException(slug);
        }

        RepositoryToMonitor repositoryToMonitor = workspace.getRepositoriesToMonitor().stream()
                .filter(r -> r.getNameWithOwner().equals(nameWithOwner))
                .findFirst()
                .orElse(null);

        if (repositoryToMonitor == null) {
            log.debug(
                    "Skipped repository monitor removal: reason=notMonitored, nameWithOwner={}, workspaceId={}",
                    LoggingUtils.sanitizeForLog(nameWithOwner),
                    workspace.getId());
            throw new EntityNotFoundException("Repository", nameWithOwner);
        }

        log.info(
                "Removed repository from monitor: nameWithOwner={}, workspaceId={}",
                LoggingUtils.sanitizeForLog(nameWithOwner),
                workspace.getId());

        deleteRepositoryMonitor(workspace, repositoryToMonitor);

        deleteRepositoryIfOrphaned(nameWithOwner);
    }

    public void removeRepositoryFromMonitor(WorkspaceContext workspaceContext, String nameWithOwner)
            throws EntityNotFoundException {
        removeRepositoryFromMonitor(requireSlug(workspaceContext), nameWithOwner);
    }

    /** Remove a repository monitor for a given installation id if it exists; no-op if missing. */
    @Transactional
    public Optional<Workspace> removeRepositoryMonitorForInstallation(long installationId, String nameWithOwner) {
        return removeRepositoryMonitorInTransaction(installationId, nameWithOwner);
    }

    private Optional<Workspace> removeRepositoryMonitorInTransaction(long installationId, String nameWithOwner) {
        if (StringUtils.isBlank(nameWithOwner)) {
            return Optional.empty();
        }

        var workspaceOpt = workspaceRepository.findActiveByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty()) {
            return Optional.empty();
        }
        Workspace workspace = workspaceOpt.get();
        var monitorOpt =
                repositoryToMonitorRepository.findByWorkspaceIdAndNameWithOwner(workspace.getId(), nameWithOwner);
        if (monitorOpt.isEmpty()) {
            return workspaceOpt;
        }

        RepositoryToMonitor monitor = monitorOpt.get();
        var result = removeRepositoryMonitorInternal(workspace, monitor);

        // Orphan repos would cause permanent sync errors.
        deleteRepositoryIfOrphaned(nameWithOwner);

        return result;
    }

    /**
     * Remove all repository monitors tied to an installation and optionally delete orphaned repositories.
     *
     * @param installationId     the GitHub App installation ID
     * @param deleteRepositories if true, also delete Repository entities that are no longer monitored by any workspace
     */
    @Transactional
    public Optional<Workspace> removeAllRepositoriesFromMonitor(long installationId, boolean deleteRepositories) {
        return removeAllRepositoriesInTransaction(installationId, deleteRepositories);
    }

    private Optional<Workspace> removeAllRepositoriesInTransaction(long installationId, boolean deleteRepositories) {
        var workspaceOpt = workspaceRepository.findActiveByInstallationIdForUpdate(installationId);
        workspaceOpt.ifPresent(workspace -> {
            List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
            for (RepositoryToMonitor monitor : monitors) {
                String nameWithOwner = monitor.getNameWithOwner();
                deleteRepositoryMonitor(workspace, monitor);

                if (deleteRepositories && nameWithOwner != null) {
                    deleteRepositoryIfOrphaned(nameWithOwner);
                }
            }
        });
        return workspaceOpt;
    }

    /**
     * Creates a Repository entity and corresponding RepositoryToMonitor from a provisioning
     * snapshot (webhook metadata). Idempotent: an existing repository is updated with the
     * snapshot data; an existing monitor is left alone.
     */
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
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow(() -> new IllegalStateException(
                        "IdentityProvider not found for type=GITHUB, serverUrl=https://github.com"));

        ensureRepositoryFromSnapshot(
                workspace, provider, snapshot.id(), snapshot.nameWithOwner(), snapshot.name(), snapshot.isPrivate());

        ensureRepositoryMonitorInternal(workspace, snapshot.nameWithOwner(), true);
    }

    /**
     * Enumerate all repositories available to the installation when repository
     * selection is ALL and ensure monitors exist.
     *
     * @param installationId       the GitHub App installation ID
     * @param protectedRepositories repositories to keep even if not in installation (can be null)
     * @param deferSync            if true, skip immediate sync (use during provisioning
     *                             when activation will sync in bulk)
     */
    @Transactional
    public void ensureAllInstallationRepositoriesCovered(
            long installationId, @Nullable Collection<String> protectedRepositories, boolean deferSync) {
        // Check suspension BEFORE adding repos so NATS replay of old "created" events
        // cannot trigger hundreds of failed syncs.
        if (isInstallationSuspended(installationId)) {
            log.info("Skipped repository enumeration: reason=installationSuspended, installationId={}", installationId);
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
                    "Skipped repository enumeration: reason=noEnumeratorForKind, installationId={}, kind={}",
                    installationId,
                    IntegrationKind.GITHUB);
            return;
        }
        var snapshots = enumerator.enumerate(installationId);
        if (snapshots.isEmpty()) {
            log.warn(
                    "Skipped repository enumeration: reason=noDataReturned, installationId={}, workspaceSlug={}",
                    installationId,
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()));
            return;
        }

        // Filter snapshots BEFORE processing to avoid creating Repository entities for filtered repos
        List<InstallationRepositoryEnumerator.InstallationRepository> allowedSnapshots = snapshots.stream()
                .filter(s -> workspaceScopeFilter.isRepositoryAllowed(s.nameWithOwner()))
                .toList();

        if (allowedSnapshots.size() < snapshots.size()) {
            log.debug(
                    "Filtered repositories during enumeration: allowed={}, total={}, installationId={}",
                    allowedSnapshots.size(),
                    snapshots.size(),
                    installationId);
        }

        Set<String> desiredRepositories = allowedSnapshots.stream()
                .map(snapshot -> snapshot.nameWithOwner())
                .filter(name -> !StringUtils.isBlank(name))
                .map(name -> name.toLowerCase(Locale.ENGLISH))
                .collect(Collectors.toSet());

        if (protectedRepositories != null) {
            protectedRepositories.stream()
                    .filter(name -> !StringUtils.isBlank(name))
                    .filter(name -> workspaceScopeFilter.isRepositoryAllowed(name))
                    .map(name -> name.toLowerCase(Locale.ENGLISH))
                    .forEach(desiredRepositories::add);
        }

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow(() -> new IllegalStateException(
                        "IdentityProvider not found for type=GITHUB, serverUrl=https://github.com"));

        allowedSnapshots.forEach(snapshot -> {
            ensureRepositoryFromSnapshot(
                    workspace,
                    provider,
                    snapshot.id(),
                    snapshot.nameWithOwner(),
                    snapshot.name(),
                    snapshot.isPrivate());
            ensureRepositoryMonitorInternal(workspace, snapshot.nameWithOwner(), deferSync);
        });

        repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(monitor -> !StringUtils.isBlank(monitor.getNameWithOwner()))
                .filter(monitor ->
                        !desiredRepositories.contains(monitor.getNameWithOwner().toLowerCase(Locale.ENGLISH)))
                .forEach(monitor -> removeRepositoryMonitorInTransaction(installationId, monitor.getNameWithOwner()));
    }

    /**
     * Repositories are global entities (integration.scm is workspace-agnostic),
     * so this lookup is intentionally not scoped to a workspace.
     */
    private Optional<Repository> findRepository(String nameWithOwner) {
        return repositoryRepository.findByNameWithOwner(nameWithOwner);
    }

    /**
     * Best-effort capture of the provider's stable repository id ({@code native_id}) at
     * monitor-creation time by resolving the (already-upserted) domain {@link Repository} by name.
     * Returns {@code null} when the repository is not yet known locally — e.g. a GitLab PAT repo added
     * by name before its first sync — in which case the periodic sync backfills it later via
     * {@link de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider#reconcileSyncTargetIdentity}.
     */
    @org.jspecify.annotations.Nullable
    private Long resolveNativeId(String nameWithOwner) {
        if (StringUtils.isBlank(nameWithOwner)) {
            return null;
        }
        return findRepository(nameWithOwner).map(Repository::getNativeId).orElse(null);
    }

    private boolean shouldUseNats(Workspace workspace) {
        return natsProperties.enabled() && workspace != null;
    }

    /**
     * Deletes a repository only if no workspace is monitoring it (repositories are shared
     * across workspaces). Cascades deletion to any projects owned by this repository to
     * maintain referential integrity for the polymorphic project ownership model.
     *
     * <p><b>The cross-tenant guard of the whole SCM domain.</b> {@code repository} and everything
     * hanging off it carry no {@code workspace_id} — they key on {@code (provider_id, native_id)}
     * ({@code BaseGitServiceEntity}) and are genuinely SHARED between workspaces that monitor the
     * same source repository. The {@code countByNameWithOwner} check below is therefore the only
     * thing standing between a workspace-scoped erase and a global one: the caller removes
     * <em>its own</em> {@code repository_to_monitor} row first, then asks this method to drop the
     * shared row only if the count fell to zero. Callers MUST flush the monitor removal before
     * calling (a pending orphanRemoval delete is invisible to this count query).
     *
     * <p>Public so {@link ScmWorkspaceContentEraser} can route the disconnect and workspace-purge
     * erase triggers through this exact cascade instead of a second, unguarded deleter.
     */
    public void deleteRepositoryIfOrphaned(String nameWithOwner) {
        if (StringUtils.isBlank(nameWithOwner)) {
            return;
        }

        long monitorCount = repositoryToMonitorRepository.countByNameWithOwner(nameWithOwner);
        if (monitorCount > 0) {
            log.debug(
                    "Skipped repository deletion: reason=stillMonitored, repoName={}, monitorCount={}",
                    LoggingUtils.sanitizeForLog(nameWithOwner),
                    monitorCount);
            return;
        }

        repositoryRepository.findByNameWithOwner(nameWithOwner).ifPresent(repository -> {
            Long repoId = repository.getId();

            // Clean up local git clone before deleting the DB entity
            gitRepositoryManager.deleteOrphanedRepository(repoId);

            // Synchronous publish — listeners run in this transaction so any vendor-
            // owned dependents (GitHub Projects V2 polymorphic ownership rows) are gone
            // before the repository delete fires.
            eventPublisher.publishEvent(new RepositoryAboutToBeDeletedEvent(repoId));

            repositoryRepository.delete(repository);
            log.debug("Deleted orphaned repository: repoName={}", LoggingUtils.sanitizeForLog(nameWithOwner));
        });
    }

    private void persistRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor) {
        persistRepositoryMonitor(workspace, monitor, false);
    }

    /**
     * Persist a repository monitor and optionally trigger immediate sync.
     *
     * @param deferSync if true, skip immediate sync (useful during provisioning
     *                  when activation will sync all repositories in bulk)
     */
    private void persistRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor, boolean deferSync) {
        // Use saveAndFlush to force immediate constraint check so callers can catch DataIntegrityViolationException
        repositoryToMonitorRepository.saveAndFlush(monitor);
        workspace.getRepositoriesToMonitor().add(monitor);
        workspaceRepository.save(workspace);
        boolean repositoryAllowed = workspaceScopeFilter.isRepositoryAllowed(monitor);
        if (shouldUseNats(workspace) && repositoryAllowed) {
            // Update workspace consumer to include new repository subjects
            natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspace.getId()));
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
                            "Skipped single-target sync: reason=noTriggerForKind, repoName={}, kind={}",
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

    private void deleteRepositoryMonitor(Workspace workspace, RepositoryToMonitor monitor) {
        // Remove from collection first - orphanRemoval=true will handle deletion
        workspace.getRepositoriesToMonitor().remove(monitor);
        workspaceRepository.save(workspace);
        if (shouldUseNats(workspace)) {
            // Update workspace consumer to remove repository subjects
            natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspace.getId()));
        }
    }

    /**
     * Idempotently ensures a repository monitor exists for a workspace.
     *
     * <p>Handles race conditions from NATS message replay by catching duplicate key
     * violations. If another thread/message already created the monitor, we simply
     * return success - that's the idempotent behavior we want.
     */
    private Optional<Workspace> ensureRepositoryMonitorInternal(
            Workspace workspace, String nameWithOwner, boolean deferSync) {
        if (workspace == null || StringUtils.isBlank(nameWithOwner)) {
            return Optional.ofNullable(workspace);
        }

        if (repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(workspace.getId(), nameWithOwner)) {
            return Optional.of(workspace);
        }

        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(nameWithOwner);
        monitor.setNativeId(resolveNativeId(nameWithOwner));
        monitor.setWorkspace(workspace);

        try {
            persistRepositoryMonitor(workspace, monitor, deferSync);
        } catch (DataIntegrityViolationException e) {
            log.debug(
                    "Repository monitor already exists (idempotent): repoName={}, workspaceId={}",
                    LoggingUtils.sanitizeForLog(nameWithOwner),
                    workspace.getId());
        }

        return Optional.of(workspace);
    }

    private Optional<Workspace> removeRepositoryMonitorInternal(Workspace workspace, RepositoryToMonitor monitor) {
        if (workspace == null || monitor == null) {
            return Optional.ofNullable(workspace);
        }

        deleteRepositoryMonitor(workspace, monitor);
        return Optional.of(workspace);
    }

    /**
     * Creates or updates a Repository entity from a snapshot (installation enumeration or
     * provisioning). The organization comes from the workspace so repositories from
     * organization installations get the proper organization_id.
     *
     * <p>Uses a native SQL upsert ({@code INSERT ... ON CONFLICT DO UPDATE}) to atomically
     * handle concurrent inserts from NATS event processing and GraphQL sync, eliminating
     * optimistic locking errors. Visibility and HTML URL are derived inside the SQL from
     * {@code isPrivate} and {@code nameWithOwner} respectively.
     */
    private void ensureRepositoryFromSnapshot(
            Workspace workspace,
            IdentityProvider provider,
            long nativeId,
            String nameWithOwner,
            String name,
            boolean isPrivate) {
        if (StringUtils.isBlank(nameWithOwner)) {
            return;
        }

        repositoryRepository.upsertFromSnapshot(
                nativeId,
                Objects.requireNonNull(provider.getId()),
                nameWithOwner,
                name,
                isPrivate,
                workspace.getOrganization() != null
                        ? workspace.getOrganization().getId()
                        : null);
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

    /**
     * Dispatches the installation-suspension check across kind-specific trackers (today only
     * the GitHub adapter contributes an impl); true if any tracker reports suspended.
     */
    private boolean isInstallationSuspended(long installationId) {
        for (InstallationSuspensionTracker t : suspensionTrackers.values()) {
            if (t.isInstallationMarkedSuspended(installationId)) {
                return true;
            }
        }
        return false;
    }

    /** Workspace is bound to a GitHub App installation (vs GitHub PAT or GitLab PAT). */
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
