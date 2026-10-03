package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
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
 * workspace has an ACTIVE GitLab {@link Connection}, the sync body runs inside
 * {@link SyncJobService}'s {@code INITIAL}/{@code LIFECYCLE} job template; otherwise it runs
 * unrecorded (fallback — a workspace can reach this path fractionally before its Connection flips
 * to ACTIVE). A {@link SyncJobConflictException} (already an active job on this connection) skips
 * the run rather than racing it.
 *
 * <p>The cancellable two-arg overload is reused as-is by {@code GitLabIntegrationSyncRunner} — it
 * deliberately does NOT wrap itself in another {@code SyncJobService} job: the runner is invoked
 * from inside a job the caller (the manual-trigger endpoint) already created, and a second nested
 * {@code beginJob} for the same connection would immediately collide with the one-active-job
 * guard the outer call holds.
 */
@Component
@ConditionalOnBean(GitLabWorkspaceInitializationService.class)
public class GitLabWorkspaceDataSyncTrigger implements WorkspaceDataSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(GitLabWorkspaceDataSyncTrigger.class);

    private final GitLabWorkspaceInitializationService gitLabInitService;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionRepository connectionRepository;
    private final SyncJobService syncJobService;

    public GitLabWorkspaceDataSyncTrigger(
            GitLabWorkspaceInitializationService gitLabInitService,
            WorkspaceRepository workspaceRepository,
            ConnectionRepository connectionRepository,
            SyncJobService syncJobService) {
        this.gitLabInitService = gitLabInitService;
        this.workspaceRepository = workspaceRepository;
        this.connectionRepository = connectionRepository;
        this.syncJobService = syncJobService;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public void syncAllRepositories(long workspaceId) {
        Optional<Connection> connection =
                connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        workspaceId, IntegrationKind.GITLAB, IntegrationState.ACTIVE);
        if (connection.isEmpty()) {
            syncAllRepositoriesBody(workspaceId, () -> false);
            return;
        }

        try {
            syncJobService.run(
                    new SyncJobRequest(
                            workspaceId,
                            connection.get().getId(),
                            IntegrationKind.GITLAB,
                            SyncJobType.INITIAL,
                            SyncJobTrigger.LIFECYCLE,
                            null),
                    handle -> syncAllRepositoriesBody(workspaceId, handle::isCancellationRequested));
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped GitLab lifecycle sync, already an active sync job: workspaceId={}, connectionId={}",
                    workspaceId,
                    connection.get().getId());
        }
    }

    /**
     * Cooperatively cancellable variant used by {@code GitLabIntegrationSyncRunner} so a
     * {@code SyncJob} cancel request can stop the pass between repositories. Does NOT record its own
     * job — see class doc.
     */
    public void syncAllRepositories(long workspaceId, BooleanSupplier cancelled) {
        syncAllRepositoriesBody(workspaceId, cancelled);
    }

    private void syncAllRepositoriesBody(long workspaceId, BooleanSupplier cancelled) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            log.warn("Skipped GitLab data sync: reason=workspaceNotFound, workspaceId={}", workspaceId);
            return;
        }
        gitLabInitService.initialize(workspace);
        gitLabInitService.syncFullData(workspace, cancelled);
    }

    @Override
    public void syncSingleSyncTarget(long syncTargetId) {
        // GitLab sync is workspace-scoped; per-target sync is unused. Webhook-driven sync
        // keeps incremental data fresh. Intentional no-op rather than a misleading partial.
        log.debug("Skipped GitLab single-target sync: reason=notSupported, syncTargetId={}", syncTargetId);
    }
}
