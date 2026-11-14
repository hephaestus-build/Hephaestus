package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * GitLab-side adapter for {@link WorkspaceDataSyncTrigger}.
 *
 * <p>{@code syncAllRepositories} delegates to
 * {@link GitLabWorkspaceInitializationService#initialize(Workspace)} followed by
 * {@link GitLabWorkspaceInitializationService#syncFullData(Workspace)} — both phases are
 * required for a complete startup activation. The single-target path is a no-op today
 * because GitLab sync is workspace-scoped, not repository-scoped (a single
 * {@code RepositoryToMonitor} addition is covered by webhook-driven sync).
 *
 * <p>Only registered when the GitLab init service is on the classpath — under the
 * webhook-only runtime role the GitLab stack is gated off, so the trigger simply doesn't
 * exist.
 *
 * <p><b>Sync-observability job recording:</b> {@link #syncAllRepositories(long)}
 * is the {@code LIFECYCLE} trigger entry point (called on Connection activation). When the
 * workspace has an ACTIVE GitLab {@link ConnectionQueries.ConnectionSnapshot}, the sync body runs inside
 * {@link SyncJobService}'s {@code INITIAL}/{@code LIFECYCLE} job template; otherwise it runs
 * unrecorded (fallback — a workspace can reach this path fractionally before its Connection flips
 * to ACTIVE). A {@link SyncJobConflictException} (already an active job on this connection) skips
 * the run rather than racing it.
 *
 * <p>The tracked lifecycle body reports returned incomplete outcomes as warnings. The cancellable
 * two-argument entry point returns the same outcome without creating a job. Manual synchronization
 * uses the warning-aware scheduler through {@code GitLabIntegrationSyncRunner}.
 */
@Component
@ConditionalOnBean(GitLabWorkspaceInitializationService.class)
public class GitLabWorkspaceDataSyncTrigger implements WorkspaceDataSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(GitLabWorkspaceDataSyncTrigger.class);

    private final GitLabWorkspaceInitializationService gitLabInitService;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionQueries connectionQueries;
    private final SyncJobService syncJobService;

    public GitLabWorkspaceDataSyncTrigger(
            GitLabWorkspaceInitializationService gitLabInitService,
            WorkspaceRepository workspaceRepository,
            ConnectionQueries connectionQueries,
            SyncJobService syncJobService) {
        this.gitLabInitService = gitLabInitService;
        this.workspaceRepository = workspaceRepository;
        this.connectionQueries = connectionQueries;
        this.syncJobService = syncJobService;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public void syncAllRepositories(long workspaceId) {
        Optional<ConnectionQueries.ConnectionSnapshot> connection =
                connectionQueries.findActive(workspaceId, IntegrationKind.GITLAB);
        if (connection.isEmpty()) {
            syncAllRepositoriesBody(workspaceId, () -> false);
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
                        SyncResult result = syncAllRepositoriesBody(
                                workspaceId,
                                () -> {
                                    if (handle.isCancellationRequested()) {
                                        handle.reportCancelled();
                                        return true;
                                    }
                                    return false;
                                },
                                handle);
                        if (result.status() != SyncResult.Status.COMPLETED) {
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
     * Runs initialization and full data sync with the supplied cancellation checkpoint, without recording a job.
     *
     * @return combined outcome; counts are internal accounting across phases
     */
    public SyncResult syncAllRepositories(long workspaceId, BooleanSupplier cancelled) {
        return syncAllRepositoriesBody(workspaceId, cancelled);
    }

    private SyncResult syncAllRepositoriesBody(long workspaceId, BooleanSupplier cancelled) {
        return syncAllRepositoriesBody(workspaceId, cancelled, null);
    }

    private SyncResult syncAllRepositoriesBody(
            long workspaceId, BooleanSupplier cancelled, @Nullable SyncExecutionHandle handle) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            log.warn("Skipped GitLab data sync: reason=workspaceNotFound, workspaceId={}", workspaceId);
            return SyncResult.abortedError(0);
        }
        return SyncResult.merge(
                gitLabInitService.initialize(workspace, handle),
                gitLabInitService.syncFullData(workspace, cancelled, handle));
    }

    @Override
    public void syncSingleSyncTarget(long syncTargetId) {
        // GitLab sync is workspace-scoped; per-target sync is unused. Webhook-driven sync
        // keeps incremental data fresh. Intentional no-op rather than a misleading partial.
        log.debug("Skipped GitLab single-target sync: reason=notSupported, syncTargetId={}", syncTargetId);
    }
}
