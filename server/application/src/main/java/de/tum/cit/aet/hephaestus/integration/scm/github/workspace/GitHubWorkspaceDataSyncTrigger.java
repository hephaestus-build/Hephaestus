package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDataSyncService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepositoryMonitorService;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * GitHub-side adapter that lets workspace lifecycle code (activation, post-install
 * provisioning) drive a full GraphQL sync without importing
 * {@link GitHubDataSyncService} directly.
 *
 * <p>{@link GitHubDataSyncService} (and, for the same reason, {@link SyncTargetProvider}) is
 * consumed via {@link ObjectProvider} because there is a known circular reference between the sync
 * service and the workspace activation layer (the sync service injects {@link SyncTargetProvider}
 * which in turn lives in the workspace module). Lazy lookup breaks the cycle without forcing the
 * workspace caller to know about the cycle. {@link ConnectionRepository} and {@link SyncJobService}
 * have no such documented cycle, but are looked up the same defensive way for consistency with the
 * rest of this class. Active-connection writes never bypass {@link SyncJobService}; if the control
 * plane is unavailable, the sync is skipped rather than run unfenced.
 *
 * <p>{@link WorkspaceRepositoryMonitorService} injects every {@link WorkspaceDataSyncTrigger}, so this
 * class looks it up lazily too.
 */
@Component
public class GitHubWorkspaceDataSyncTrigger implements WorkspaceDataSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(GitHubWorkspaceDataSyncTrigger.class);

    private final ObjectProvider<GitHubDataSyncService> dataSyncServiceProvider;
    private final ObjectProvider<SyncTargetProvider> syncTargetProvider;
    private final ObjectProvider<ConnectionRepository> connectionRepositoryProvider;
    private final ObjectProvider<SyncJobService> syncJobServiceProvider;
    private final ObjectProvider<WorkspaceRepositoryMonitorService> repositoryMonitorServiceProvider;
    private final AsyncTaskExecutor monitoringExecutor;

    public GitHubWorkspaceDataSyncTrigger(
            ObjectProvider<GitHubDataSyncService> dataSyncServiceProvider,
            ObjectProvider<SyncTargetProvider> syncTargetProvider,
            ObjectProvider<ConnectionRepository> connectionRepositoryProvider,
            ObjectProvider<SyncJobService> syncJobServiceProvider,
            ObjectProvider<WorkspaceRepositoryMonitorService> repositoryMonitorServiceProvider,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.dataSyncServiceProvider = dataSyncServiceProvider;
        this.syncTargetProvider = syncTargetProvider;
        this.connectionRepositoryProvider = connectionRepositoryProvider;
        this.syncJobServiceProvider = syncJobServiceProvider;
        this.repositoryMonitorServiceProvider = repositoryMonitorServiceProvider;
        this.monitoringExecutor = monitoringExecutor;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    /**
     * Resolves the workspace's ACTIVE GitHub {@link Connection} and, when both it and {@link
     * SyncJobService} are available, records the lifecycle sync as an {@code INITIAL}/{@code
     * LIFECYCLE} job. A workspace without an active Connection still uses the bootstrap fallback;
     * an active Connection never runs untracked. An already-active job skips this run.
     */
    @Override
    public void syncAllRepositories(long workspaceId) {
        Optional<Connection> connection = findActiveConnection(workspaceId);
        if (connection.isEmpty()) {
            dataSyncServiceProvider.getObject().syncAllRepositories(workspaceId);
            return;
        }
        runLifecycleJob(
                workspaceId,
                connection.get(),
                SyncJobType.INITIAL,
                handle -> dataSyncServiceProvider.getObject().syncAllRepositories(workspaceId, handle));
    }

    /**
     * A GitHub App workspace first reconciles its monitored repositories with the installation, then runs the
     * full sync if {@code fullSync} is set. Both steps run in one {@code RECONCILIATION}/{@code LIFECYCLE} job,
     * because the reconciliation can remove monitors of repositories that the installation no longer covers.
     * The monitor transaction commits before the sync starts, so the sync and the scope consumer see the
     * reconciled monitors.
     * A GitHub PAT workspace, or a workspace without an active connection, keeps the default behavior.
     */
    @Override
    public void syncOnStartup(long workspaceId, boolean fullSync) {
        Optional<Connection> connection = findActiveConnection(workspaceId);
        Optional<Long> appInstallation = connection
                .map(Connection::getConfig)
                .filter(ConnectionConfig.GitHubAppConfig.class::isInstance)
                .map(ConnectionConfig.GitHubAppConfig.class::cast)
                .map(ConnectionConfig.GitHubAppConfig::installationId);
        if (appInstallation.isEmpty()) {
            WorkspaceDataSyncTrigger.super.syncOnStartup(workspaceId, fullSync);
            return;
        }
        long installationId = appInstallation.get();
        runLifecycleJob(workspaceId, connection.get(), SyncJobType.RECONCILIATION, handle -> {
            repositoryMonitorServiceProvider
                    .getObject()
                    .ensureAllInstallationRepositoriesCovered(installationId, null, true);
            if (fullSync) {
                dataSyncServiceProvider.getObject().syncAllRepositories(workspaceId, handle);
            }
        });
    }

    private Optional<Connection> findActiveConnection(long workspaceId) {
        ConnectionRepository connectionRepository = connectionRepositoryProvider.getIfAvailable();
        return connectionRepository == null
                ? Optional.empty()
                : connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        workspaceId, IntegrationKind.GITHUB, IntegrationState.ACTIVE);
    }

    private void runLifecycleJob(
            long workspaceId, Connection connection, SyncJobType type, Consumer<SyncJobHandle> body) {
        SyncJobService syncJobService = syncJobServiceProvider.getIfAvailable();
        if (syncJobService == null) {
            log.warn("Skipped lifecycle sync: reason=syncJobServiceUnavailable, workspaceId={}", workspaceId);
            return;
        }
        try {
            syncJobService.run(
                    new SyncJobRequest(
                            workspaceId,
                            connection.getId(),
                            IntegrationKind.GITHUB,
                            type,
                            SyncJobTrigger.LIFECYCLE,
                            null),
                    body);
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped lifecycle sync: reason=activeJobAlready, workspaceId={}, activeJobId={}",
                    workspaceId,
                    e.activeJob().getId());
        }
    }

    @Override
    public void syncSingleSyncTarget(long syncTargetId) {
        SyncTargetProvider provider = syncTargetProvider.getIfAvailable();
        if (provider == null) {
            return;
        }
        provider.findSyncTargetById(syncTargetId)
                .ifPresent(target -> monitoringExecutor.execute(() -> syncTarget(target)));
    }

    private void syncTarget(SyncTargetProvider.SyncTarget target) {
        ConnectionRepository connectionRepository = connectionRepositoryProvider.getIfAvailable();
        SyncJobService syncJobService = syncJobServiceProvider.getIfAvailable();
        if (connectionRepository == null || syncJobService == null) {
            log.warn(
                    "Skipped single-target sync: reason=controlPlaneUnavailable, workspaceId={}, syncTargetId={}",
                    target.scopeId(),
                    target.id());
            return;
        }
        Optional<Connection> connection =
                connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        target.scopeId(), IntegrationKind.GITHUB, IntegrationState.ACTIVE);
        if (connection.isEmpty()) {
            log.debug(
                    "Skipped single-target sync: reason=noActiveConnection, workspaceId={}, syncTargetId={}",
                    target.scopeId(),
                    target.id());
            return;
        }
        try {
            syncJobService.run(
                    new SyncJobRequest(
                            target.scopeId(),
                            connection.get().getId(),
                            IntegrationKind.GITHUB,
                            SyncJobType.INITIAL,
                            SyncJobTrigger.LIFECYCLE,
                            null),
                    // The job handle is NOT threaded into syncSyncTarget: a single sync-target refresh is a
                    // short, indivisible GraphQL unit, so cooperative cancel/progress (which only make sense
                    // across the multi-repo loop of a full sync) add nothing here. When a broader sync is
                    // already running on this connection, the SyncJobConflictException below intentionally
                    // absorbs this single-target resync, since the in-flight full sync already covers it.
                    handle -> dataSyncServiceProvider.getObject().syncSyncTarget(target));
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped single-target sync: reason=activeJobAlready, workspaceId={}, syncTargetId={}, activeJobId={}",
                    target.scopeId(),
                    target.id(),
                    e.activeJob().getId());
        }
    }
}
