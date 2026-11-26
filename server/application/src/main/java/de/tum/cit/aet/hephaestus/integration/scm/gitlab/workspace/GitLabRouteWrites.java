package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission.number;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission.text;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission.AdmittedRoute;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The write side of a route {@link GitLabRouteAdmission} admitted: it holds the admitted connection for a handler's
 * write transaction ({@link #holdActive}), decides whether the admitted workspace may handle work on a stored
 * repository ({@link #admitRepository}), and stores and monitors the project a delivery names once GitLab reports it
 * inside the connected group ({@link #admitProject}). It also says why a connection no longer matches the group a
 * route names ({@link #mismatch}).
 *
 * <p>The GitLab reads here happen outside any transaction; every write happens in a transaction that first holds the
 * connection's lifecycle lock, so a disconnect either happens before and nothing is written, or after.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabRouteWrites {

    private static final Logger log = LoggerFactory.getLogger(GitLabRouteWrites.class);

    private final ConnectionCommands connectionCommands;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceRepositoryMonitors monitors;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final GitLabRepositoryMonitors repositoryMonitors;
    private final GitLabProjectSyncService projectSyncService;
    private final SyncTargetProvider syncTargetProvider;
    private final GitLabProperties gitLabProperties;
    private final TransactionTemplate transaction;
    private final GitLabProjectAdmission projectAdmission;

    public GitLabRouteWrites(
            ConnectionCommands connectionCommands,
            RepositoryRepository repositoryRepository,
            WorkspaceRepositoryMonitors monitors,
            WorkspaceScopeFilter workspaceScopeFilter,
            GitLabRepositoryMonitors repositoryMonitors,
            GitLabProjectSyncService projectSyncService,
            SyncTargetProvider syncTargetProvider,
            GitLabProperties gitLabProperties,
            PlatformTransactionManager transactionManager,
            GitLabProjectAdmission projectAdmission) {
        this.connectionCommands = connectionCommands;
        this.repositoryRepository = repositoryRepository;
        this.monitors = monitors;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.repositoryMonitors = repositoryMonitors;
        this.projectSyncService = projectSyncService;
        this.syncTargetProvider = syncTargetProvider;
        this.gitLabProperties = gitLabProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.projectAdmission = projectAdmission;
    }

    /**
     * Locks the admitted connection for the rest of the caller's write transaction, the way every connection lifecycle
     * change does, and confirms everything the route was admitted for still holds. A handler calls this before its
     * first write, so a disconnect either finishes first and the handler writes nothing, or waits until it is done.
     */
    public boolean holdActive(AdmittedRoute route) {
        connectionCommands.holdLifecycleLock(route.workspaceId(), route.connectionId());
        boolean active = connectionCommands
                .findHeldConnection(route.workspaceId(), route.connectionId())
                .filter(connection -> mismatch(connection, route.groupId(), route.groupPath()) == null)
                .isPresent();
        if (!active) {
            log.info("Skipped GitLab delivery: reason=connectionInactive, connectionId={}", route.connectionId());
        }
        return active;
    }

    /**
     * Whether the admitted workspace may handle work on {@code repository}. Called inside the handler's write
     * transaction: the connection is held active, the repository must be on the connection's GitLab instance and
     * inside its group, and the workspace must monitor it now. A monitor is only ever created where GitLab has just
     * reported the project ({@link #monitorIfAllowed}), so a monitor removed meanwhile, say by a transfer out of the
     * group, is never recreated from the shared row's old path.
     */
    public boolean admitRepository(AdmittedRoute route, Repository repository) {
        if (!holdActive(route)) {
            return false;
        }
        String path = repository.getNameWithOwner();
        if (!Objects.equals(repository.getProvider().getId(), route.providerId()) || !route.contains(path)) {
            log.warn(
                    "Skipped GitLab delivery: reason=outsideConnectedGroup, connectionId={}, repoId={}",
                    route.connectionId(),
                    repository.getId());
            return false;
        }
        GitLabProjectAdmission.ProjectRead project = GitLabRouteAdmission.currentProject();
        return project != null
                && project.repositoryId() == repository.getId()
                && projectAdmission.hold(project)
                && workspaceScopeFilter.isRepositoryAllowed(workspace(route), path);
    }

    /**
     * Brings this workspace's monitor of {@code repository}, which GitLab has just reported inside the connected group,
     * in line with it, and monitors it when the policy allows and no monitor exists; called in the transaction that
     * stored it, holding the lifecycle lock.
     */
    private void monitorIfAllowed(AdmittedRoute route, Repository repository) {
        repositoryMonitors.monitorAllowed(workspace(route), List.of(repository));
    }

    /** This workspace's monitors of the exact provider project. */
    private List<RepositoryToMonitor> ownMonitors(AdmittedRoute route, long nativeId) {
        return monitors.monitorsOf(route.workspaceId(), nativeId);
    }

    private Workspace workspace(AdmittedRoute route) {
        return connectionCommands
                .findHeldConnection(route.workspaceId(), route.connectionId())
                .orElseThrow()
                .getWorkspace();
    }

    /** Why {@code connection} is no longer the active GitLab connection of group {@code groupId} at {@code groupPath}. */
    @Nullable
    String mismatch(Connection connection, long groupId, String groupPath) {
        if (connection.getKind() != IntegrationKind.GITLAB
                || connection.getState() != IntegrationState.ACTIVE
                || !(connection.getConfig() instanceof ConnectionConfig.GitLabConfig config)) {
            return "connectionInactive";
        }
        Workspace workspace = connection.getWorkspace();
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE
                || !workspaceScopeFilter.isWorkspaceAllowed(workspace)) {
            return "workspaceInactive";
        }
        Optional<String> configured = ScmOrigin.of(gitLabProperties.defaultServerUrl());
        if (configured.isEmpty() || !ScmOrigin.of(config.serverUrl()).equals(configured)) {
            return "instanceMismatch";
        }
        String accountLogin = workspace.getAccountLogin();
        if (!Objects.equals(config.gitlabGroupId(), groupId)
                || accountLogin == null
                || !accountLogin.equalsIgnoreCase(groupPath)) {
            return "groupMismatch";
        }
        return null;
    }

    /**
     * Whether the project {@code payload} names, if any, is one GitLab reports inside the connected group, after storing
     * what GitLab reported. A created, renamed, moved or deleted project is looked up by its id, so a stale or invented
     * path cannot move it. A project GitLab reports elsewhere, or not at all, only stops being monitored by this
     * workspace: its shared row is left as last stored, since not being reported to one connection proves neither a
     * deletion nor where it went.
     */
    boolean admitProject(AdmittedRoute route, JsonNode payload, GitLabProjectAdmission.ConnectionRead read) {
        String eventName = text(payload, "event_name");
        if (eventName != null && eventName.startsWith("project_")) {
            Long nativeId = number(payload, "project_id");
            return nativeId != null && admitProjectEvent(route, nativeId, read);
        }
        JsonNode project = payload.path("project");
        String path = text(project, "path_with_namespace");
        if (path == null) {
            return true;
        }
        Long nativeId = number(project, "id");
        Long projectId = nativeId != null ? nativeId : number(payload, "project_id");
        if (!route.contains(path)) {
            log.warn("Skipped GitLab delivery: reason=outsideConnectedGroup, path={}", sanitizeForLog(path));
            return false;
        }
        // A stored project is trusted without asking GitLab only while this workspace monitors it: the shared row may
        // still hold a path its project was moved away from, and monitoring it again needs GitLab's word.
        Boolean monitoredKnown = transaction.execute(status -> holdActive(route)
                && projectAllowed(route, projectId)
                && monitors.monitorsPath(route.workspaceId(), path)
                && repositoryRepository
                        .findByNameWithOwnerAndProviderId(path, route.providerId())
                        .filter(known -> projectId == null || projectId.equals(known.getNativeId()))
                        .isPresent());
        if (Boolean.TRUE.equals(monitoredKnown)) {
            return true;
        }
        var metadata = projectAdmission.beginRepositoryReads(read);
        GitLabProjectResponse reported =
                projectSyncService.fetchProject(route.workspaceId(), path).orElse(null);
        if (reported == null
                || !route.contains(reported.fullPath())
                || (projectId != null && projectId != extractId(reported))) {
            log.info("Skipped GitLab delivery: reason=projectNotReported, connectionId={}", route.connectionId());
            return false;
        }
        return projectAdmission
                .discover(
                        read,
                        extractId(reported),
                        Objects.requireNonNull(reported.fullPath()),
                        gitLabProperties.defaultServerUrl(),
                        () -> {
                            if (!holdActive(route)) return false;
                            if (!DeliveryInbox.claim("gitlab.route.discover." + extractId(reported))) {
                                return repositoryRepository
                                        .findByNativeIdAndProviderId(extractId(reported), route.providerId())
                                        .isPresent();
                            }
                            Optional<Repository> stored = projectSyncService.persistProject(
                                    reported, metadata.forRepository(extractId(reported)));
                            stored.ifPresent(repository -> monitorIfAllowed(route, repository));
                            return stored.isPresent();
                        })
                .orElse(false);
    }

    private boolean admitProjectEvent(AdmittedRoute route, long nativeId, GitLabProjectAdmission.ConnectionRead read) {
        var metadata = projectAdmission.beginRepositoryReads(read).forRepository(nativeId);
        final GitLabProjectResponse reported;
        try {
            reported = projectSyncService
                    .fetchProjectById(route.workspaceId(), nativeId)
                    .orElse(null);
        } catch (RepositoryNotFoundOnGitProviderException | WebClientResponseException.NotFound e) {
            transaction.executeWithoutResult(status -> {
                if (holdActive(route) && DeliveryInbox.claim("gitlab.route.unavailable." + nativeId)) {
                    syncTargetProvider.recordRepositoryLoss(metadata);
                }
            });
            return false;
        }
        boolean inside = reported != null && route.contains(reported.fullPath());
        Boolean applied = transaction.execute(status -> {
            if (!holdActive(route)) {
                return false;
            }
            if (!DeliveryInbox.claim("gitlab.route.project." + nativeId)) {
                return repositoryRepository
                        .findByNativeIdAndProviderId(nativeId, route.providerId())
                        .filter(repository -> route.contains(repository.getNameWithOwner()))
                        .isPresent();
            }
            // The shared row may already carry another workspace's view of the move, so this workspace's monitor is
            // found by the project's id, not by the row's current path.
            List<RepositoryToMonitor> own = ownMonitors(route, nativeId);
            Repository stored = inside && reported != null
                    ? projectAdmission
                            .discover(
                                    read,
                                    nativeId,
                                    Objects.requireNonNull(reported.fullPath()),
                                    gitLabProperties.defaultServerUrl(),
                                    () -> projectSyncService
                                            .persistProject(reported, metadata)
                                            .orElse(null))
                            .orElse(null)
                    : null;
            if (!inside) {
                own.stream()
                        .filter(monitor -> read.originalMonitors().values().stream()
                                .anyMatch(ids -> ids.contains(monitor.getId())))
                        .forEach(monitor -> syncTargetProvider.removeSyncTarget(monitor.getId()));
            }
            if (stored == null) {
                return false;
            }
            monitorIfAllowed(route, stored);
            return true;
        });
        if (!inside) {
            log.info(
                    "Skipped GitLab project event: reason={}, projectId={}",
                    reported == null ? "projectNotReported" : "outsideConnectedGroup",
                    nativeId);
        }
        return Boolean.TRUE.equals(applied);
    }

    private boolean projectAllowed(AdmittedRoute route, @Nullable Long nativeId) {
        if (nativeId == null) return false;
        return connectionCommands
                .configurationInState(route.workspaceId(), route.connectionId(), IntegrationState.ACTIVE)
                .filter(ConnectionConfig.GitLabConfig.class::isInstance)
                .map(ConnectionConfig.GitLabConfig.class::cast)
                .filter(config -> !config.projectExclusions().excludes(nativeId))
                .isPresent();
    }

    private static long extractId(GitLabProjectResponse project) {
        String id = project.id();
        return Long.parseLong(id.substring(id.lastIndexOf('/') + 1));
    }
}
