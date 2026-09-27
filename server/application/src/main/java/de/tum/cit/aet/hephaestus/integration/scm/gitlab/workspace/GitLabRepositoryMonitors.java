package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository.MonitorPolicy;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
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
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionRepository connectionRepository;
    private final SyncTargetProvider syncTargetProvider;

    public GitLabRepositoryMonitors(
            WorkspaceScopeFilter workspaceScopeFilter,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            WorkspaceRepository workspaceRepository,
            ConnectionRepository connectionRepository,
            SyncTargetProvider syncTargetProvider) {
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.connectionRepository = connectionRepository;
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
     * Brings the workspace's monitors of {@code repositories} in line with them and monitors every one it does not
     * monitor yet, as when the workspace is first set up; returns how many monitors were created.
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
        GitLabConfig config = connectionRepository
                .lockActiveConfig(workspaceId, IntegrationKind.GITLAB)
                .filter(GitLabConfig.class::isInstance)
                .map(GitLabConfig.class::cast)
                .orElse(null);
        MonitorPolicy policy =
                workspaceRepository.findMonitorPolicy(workspaceId).orElse(null);
        Optional<String> origin = config != null ? ScmOrigin.of(config.serverUrl()) : Optional.empty();
        if (policy == null || origin.isEmpty()) {
            return 0;
        }
        String group = policy.getAccountLogin();
        Workspace workspace = workspaceRepository.getReferenceById(workspaceId);
        List<RepositoryToMonitor> own = new ArrayList<>(repositoryToMonitorRepository.findByWorkspaceId(workspaceId));
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
            List<RepositoryToMonitor> same = own.stream()
                    .filter(monitor -> nativeId.equals(monitor.getNativeId())
                            || (monitor.getNativeId() == null && path.equals(monitor.getNameWithOwner())))
                    .sorted(Comparator.comparing(RepositoryToMonitor::getId))
                    .toList();
            if (same.isEmpty()) {
                if (createAll
                        || (policy.getRepositorySelection() != RepositorySelection.SELECTED
                                && workspaceScopeFilter.isGroupRepositoryAllowed(group, path))) {
                    RepositoryToMonitor monitor = new RepositoryToMonitor();
                    monitor.setNameWithOwner(path);
                    monitor.setNativeId(nativeId);
                    monitor.setWorkspace(workspace);
                    repositoryToMonitorRepository.save(monitor);
                    own.add(monitor);
                    created++;
                }
                continue;
            }
            // The first monitor keeps its sync state; later ones hand over their review selection and are gone
            // before it takes the current path, so the path is never held twice.
            RepositoryToMonitor kept = same.getFirst();
            for (RepositoryToMonitor duplicate : same.subList(1, same.size())) {
                syncTargetProvider.mergeSyncTarget(kept.getId(), duplicate.getId());
                own.remove(duplicate);
                log.info(
                        "Merged duplicate repository monitor: workspaceId={}, syncTargetId={}, keptSyncTargetId={}",
                        workspaceId,
                        duplicate.getId(),
                        kept.getId());
            }
            repositoryToMonitorRepository.flush();
            if (kept.getNativeId() == null || !path.equals(kept.getNameWithOwner())) {
                syncTargetProvider.reconcileSyncTargetIdentity(kept.getId(), nativeId, path);
            }
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
