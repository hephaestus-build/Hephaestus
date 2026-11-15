package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderAccounts;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * How a connected GitLab workspace comes to monitor the projects GitLab reports for it (initial connect, discovery and
 * work or lifecycle events), and the policy for it: a workspace that follows every repository of its group monitors
 * each one the repository filter allows. Adding a project by hand is {@code WorkspaceRepositoryMonitorService}'s.
 *
 * <p>Callers hand in projects as GitLab reported them, so a monitor is matched by the project's id, not by the path it
 * had when it was created: a project renamed or moved inside the group keeps its one monitor, with its sync state,
 * under the new path, and a second monitor of this workspace for the same project is merged into it.
 */
@Component
public class GitLabRepositoryMonitors {

    private static final Logger log = LoggerFactory.getLogger(GitLabRepositoryMonitors.class);

    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final WorkspaceRepositoryMonitors monitors;
    private final RepositoryMonitorCommands monitorCommands;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceProviderAccounts providerAccounts;
    private final ConnectionCommands connectionCommands;
    private final SyncTargetProvider syncTargetProvider;

    public GitLabRepositoryMonitors(
            WorkspaceScopeFilter workspaceScopeFilter,
            WorkspaceRepositoryMonitors monitors,
            RepositoryMonitorCommands monitorCommands,
            RepositoryRepository repositoryRepository,
            WorkspaceProviderAccounts providerAccounts,
            ConnectionCommands connectionCommands,
            SyncTargetProvider syncTargetProvider) {
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.monitors = monitors;
        this.monitorCommands = monitorCommands;
        this.repositoryRepository = repositoryRepository;
        this.providerAccounts = providerAccounts;
        this.connectionCommands = connectionCommands;
        this.syncTargetProvider = syncTargetProvider;
    }

    /**
     * Brings the workspace's monitors of {@code repositories} in line with them and monitors the allowed ones it does
     * not monitor yet; returns how many monitors were created.
     */
    @Transactional
    public int monitorAllowed(Workspace workspace, Collection<Repository> repositories) {
        return reconcile(workspace, repositories, false);
    }

    /**
     * Brings the workspace's monitors of {@code repositories} in line with them and, unless the workspace selected its
     * projects, monitors every one it does not monitor yet, as when the workspace is first set up; returns how many
     * monitors were created.
     */
    @Transactional
    public int monitorAll(Workspace workspace, Collection<Repository> repositories) {
        return reconcile(workspace, repositories, true);
    }

    private int reconcile(Workspace given, Collection<Repository> repositories, boolean createAll) {
        Long workspaceId = given.getId();
        if (workspaceId == null) {
            return 0;
        }
        // The connection's lifecycle lock, held until this transaction ends, serializes this with disconnect, manual
        // add and other calls here. The connection's state and instance and the workspace's group and selection are
        // read after it from the database, not from entities this transaction loaded before it.
        GitLabConfig config = connectionCommands
                .holdActiveConnection(workspaceId, IntegrationKind.GITLAB)
                .map(Connection::getConfig)
                .filter(GitLabConfig.class::isInstance)
                .map(GitLabConfig.class::cast)
                .orElse(null);
        WorkspaceProviderAccounts.ProviderAccount policy =
                providerAccounts.providerAccount(workspaceId).orElse(null);
        Optional<String> origin = config != null ? ScmOrigin.of(config.serverUrl()) : Optional.empty();
        if (config == null || policy == null || origin.isEmpty()) {
            return 0;
        }
        String group = policy.accountLogin();
        List<RepositoryToMonitor> own = new ArrayList<>(monitors.monitors(workspaceId));
        int created = 0;
        // Callers may hand in repositories a finished transaction loaded; they are read again here, as stored now.
        List<Long> ids = repositories.stream().map(Repository::getId).toList();
        for (Repository repository : repositoryRepository.findAllById(ids)) {
            String path = repository.getNameWithOwner();
            if (!isOfConnection(repository, origin.get(), group)) {
                log.debug(
                        "Skipped repository monitor: reason=notOfCurrentConnection, workspaceId={}, repoId={}",
                        workspaceId,
                        repository.getId());
                continue;
            }
            Long nativeId = repository.getNativeId();
            if (config.projectExclusions().excludes(nativeId)) {
                continue;
            }
            List<RepositoryToMonitor> same = own.stream()
                    .filter(monitor -> nativeId.equals(monitor.getNativeId()))
                    .sorted(Comparator.comparing(RepositoryToMonitor::getId))
                    .toList();
            if (same.isEmpty()) {
                // A workspace that selected its projects monitors only those it already monitors.
                if (policy.repositorySelection() != RepositorySelection.SELECTED
                        && (createAll || workspaceScopeFilter.isGroupRepositoryAllowed(group, path))) {
                    if (own.stream().anyMatch(monitor -> path.equals(monitor.getNameWithOwner()))) {
                        log.warn(
                                "Skipped repository monitor: reason=nameHeldByAnotherMonitor, workspaceId={}, repoId={}",
                                workspaceId,
                                repository.getId());
                        continue;
                    }
                    RepositoryToMonitor monitor = monitorCommands.add(workspaceId, path, nativeId);
                    syncTargetProvider.reconcileSyncTargetIdentity(monitor.getId(), nativeId, path);
                    own.add(monitor);
                    created++;
                }
                continue;
            }
            // The first monitor keeps its sync state; later ones hand over their review selection and are gone
            // before it takes the current path, so the path is never held twice. A duplicate with other generated
            // paths stays, and the kept monitor does not take a path it holds.
            RepositoryToMonitor kept = same.getFirst();
            for (RepositoryToMonitor duplicate : same.subList(1, same.size())) {
                if (!syncTargetProvider.mergeSyncTarget(kept.getId(), duplicate.getId())) {
                    continue;
                }
                own.remove(duplicate);
                log.info(
                        "Merged duplicate repository monitor: workspaceId={}, syncTargetId={}, keptSyncTargetId={}",
                        workspaceId,
                        duplicate.getId(),
                        kept.getId());
            }
            syncTargetProvider.reconcileSyncTargetIdentity(kept.getId(), nativeId, path);
        }
        if (created > 0) {
            log.info(
                    "Created repository monitors: workspaceId={}, created={}, total={}",
                    workspaceId,
                    created,
                    repositories.size());
        }
        return created;
    }

    /** Whether {@code repository} is a project of the connection's current GitLab instance, inside its current group. */
    private static boolean isOfConnection(Repository repository, String origin, String group) {
        IdentityProvider provider = repository.getProvider();
        String path = repository.getNameWithOwner();
        String candidate = path.toLowerCase(Locale.ROOT);
        String root = group.toLowerCase(Locale.ROOT);
        return provider.getType() == IdentityProviderType.GITLAB
                && ScmOrigin.of(provider.getServerUrl()).equals(Optional.of(origin))
                && (candidate.equals(root) || candidate.startsWith(root + "/"));
    }
}
