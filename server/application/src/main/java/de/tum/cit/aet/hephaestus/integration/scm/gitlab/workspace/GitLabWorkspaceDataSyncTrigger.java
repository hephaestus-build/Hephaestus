package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabScopeSync;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabSyncReports;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * GitLab-side adapter for {@link WorkspaceDataSyncTrigger}.
 *
 * <p>{@code syncAllRepositories} runs {@link GitLabWorkspaceInitializationService#initialize}, which rotates the token,
 * discovers the group's projects and registers the webhook, and then the one GitLab sync body,
 * {@link GitLabScopeSync}, as an {@code INITIAL} sync: the same body that the cron and a manual sync run. The
 * scope sync is skipped, with a warning on the job, when the workspace cannot sync after initialization: a refused
 * credential, an inactive connection, or a scope filter that excludes it. As an {@code INITIAL} sync it never removes
 * upstream deletions; only a {@code RECONCILIATION} sweeps ({@link GitLabScopeSync#syncWorkspaceNow} lists how the two
 * differ). The single-target path is a no-op because GitLab sync is workspace-scoped, not repository-scoped (a single
 * {@code RepositoryToMonitor} addition is covered by webhook-driven sync).
 *
 * <p>Only registered when the GitLab init service is on the classpath. {@link GitLabScopeSync} exists only while the
 * GitLab integration is enabled; without it, only initialization runs.
 *
 * <p><b>Sync-observability job recording:</b> {@link #syncAllRepositories(long)} is the {@code LIFECYCLE} trigger
 * entry point (called on Connection activation). When the workspace has an ACTIVE GitLab
 * {@link ConnectionQueries.ConnectionSnapshot}, the sync body runs inside {@link SyncJobService}'s
 * {@code INITIAL}/{@code LIFECYCLE} job template, and the scope sync reports its warnings and a consumed cancellation
 * on the job's handle; otherwise it runs unrecorded (fallback — a workspace can reach this path fractionally before its
 * Connection flips to ACTIVE). A {@link SyncJobConflictException} (already an active job on this connection) skips the
 * run rather than racing it.
 */
@Component
@ConditionalOnBean(GitLabWorkspaceInitializationService.class)
public class GitLabWorkspaceDataSyncTrigger implements WorkspaceDataSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(GitLabWorkspaceDataSyncTrigger.class);

    private final GitLabWorkspaceInitializationService gitLabInitService;
    private final @Nullable GitLabScopeSync scopeSync;
    private final WorkspaceLookup workspaceLookup;
    private final ConnectionQueries connectionQueries;
    private final SyncJobService syncJobService;
    private final SyncSchedulerProperties syncSchedulerProperties;

    public GitLabWorkspaceDataSyncTrigger(
            GitLabWorkspaceInitializationService gitLabInitService,
            @Nullable GitLabScopeSync scopeSync,
            WorkspaceLookup workspaceLookup,
            ConnectionQueries connectionQueries,
            SyncJobService syncJobService,
            SyncSchedulerProperties syncSchedulerProperties) {
        this.gitLabInitService = gitLabInitService;
        this.scopeSync = scopeSync;
        this.workspaceLookup = workspaceLookup;
        this.connectionQueries = connectionQueries;
        this.syncJobService = syncJobService;
        this.syncSchedulerProperties = syncSchedulerProperties;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    /**
     * A server start syncs a scope only when no forward sync brought it up to date within the scheduler's cooldown, so that a
     * restart or a rolling deploy does not sync every GitLab workspace again. A new connection syncs through
     * {@link #syncAllRepositories(long)} whatever its history.
     */
    @Override
    public void syncOnStartup(long workspaceId, boolean fullSync) {
        if (!fullSync) return;
        var connection = connectionQueries.findActive(workspaceId, IntegrationKind.GITLAB);
        if (connection.isPresent()) {
            Instant lastSuccess = syncJobService
                    .lastForwardSyncSucceededAt(connection.get().id())
                    .orElse(null);
            if (syncSchedulerProperties.withinCooldown(lastSuccess, Instant.now())) {
                log.info(
                        "Skipped GitLab startup sync: reason=withinCooldown, workspaceId={}, lastSuccessfulSyncAt={}",
                        workspaceId,
                        lastSuccess);
                return;
            }
        }
        syncAllRepositories(workspaceId);
    }

    @Override
    public void syncAllRepositories(long workspaceId) {
        Optional<ConnectionQueries.ConnectionSnapshot> connection =
                connectionQueries.findActive(workspaceId, IntegrationKind.GITLAB);
        if (connection.isEmpty()) {
            syncAllRepositoriesBody(workspaceId, null);
            return;
        }

        try {
            syncJobService.run(
                    new SyncJobRequest(
                            workspaceId,
                            connection.get().id(),
                            IntegrationKind.GITLAB,
                            SyncJobType.INITIAL,
                            SyncJobTrigger.LIFECYCLE,
                            null),
                    handle -> {
                        if (syncAllRepositoriesBody(workspaceId, handle).status() != SyncResult.Status.COMPLETED) {
                            handle.reportWarnings();
                        }
                    });
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped GitLab lifecycle sync, already an active sync job: workspaceId={}, connectionId={}",
                    workspaceId,
                    connection.get().id());
        }
    }

    /**
     * @return the initialization outcome, or an error when the scope cannot sync after it; the scope sync reports its
     *     own outcome on the handle
     */
    private SyncResult syncAllRepositoriesBody(long workspaceId, @Nullable SyncExecutionHandle handle) {
        Workspace workspace = workspaceLookup.find(workspaceId).orElse(null);
        if (workspace == null) {
            log.warn("Skipped GitLab data sync: reason=workspaceNotFound, workspaceId={}", workspaceId);
            return SyncResult.abortedError(0);
        }
        SyncResult initialized = gitLabInitService.initialize(workspace, handle);
        if (scopeSync == null || scopeSync.syncIfActive(workspaceId, handle, SyncJobType.INITIAL)) return initialized;
        // The scope cannot sync: initialization found the credential refused, the connection is not active, or the
        // workspace's scope filter excludes it. The sync would only repeat that for every phase.
        log.info("Skipped GitLab full data sync: reason=scopeNotEligible, workspaceId={}", workspaceId);
        GitLabSyncReports.warn(
                handle,
                SyncResult.Status.ABORTED_ERROR,
                SyncPhase.CONNECTION,
                "Sync eligibility check",
                workspace.getAccountLogin());
        return SyncResult.merge(initialized, SyncResult.abortedError(0));
    }

    @Override
    public void syncSingleSyncTarget(long syncTargetId) {
        // GitLab sync is workspace-scoped; per-target sync is unused. Webhook-driven sync
        // keeps incremental data fresh. Intentional no-op rather than a misleading partial.
        log.debug("Skipped GitLab single-target sync: reason=notSupported, syncTargetId={}", syncTargetId);
    }
}
