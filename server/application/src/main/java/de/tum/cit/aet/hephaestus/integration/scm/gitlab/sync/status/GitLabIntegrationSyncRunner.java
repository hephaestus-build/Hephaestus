package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.status;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill.GitLabHistoricalBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * GitLab {@link IntegrationSyncRunner}: the manual "Sync now" / "Backfill now" job bodies invoked by
 * {@code SyncJobService.run}.
 */
@Component
@ConditionalOnBean(GitLabDataSyncScheduler.class)
public class GitLabIntegrationSyncRunner implements IntegrationSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(GitLabIntegrationSyncRunner.class);

    private final GitLabDataSyncScheduler dataSyncScheduler;
    private final GitLabHistoricalBackfillService backfillService;

    public GitLabIntegrationSyncRunner(
            GitLabDataSyncScheduler dataSyncScheduler, GitLabHistoricalBackfillService backfillService) {
        this.dataSyncScheduler = dataSyncScheduler;
        this.backfillService = backfillService;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    /**
     * The same warning-aware body the daily scheduler uses, threaded with the job handle.
     *
     * <p>{@code type} is forwarded rather than dropped: it is what decides whether the body ends with a
     * deletion sweep ({@code GitLabDeletionSweepService}). A {@code RECONCILIATION} run set-differences
     * the full upstream issue/merge-request IID set against the local mirror and tombstones what
     * upstream no longer has (fail-closed); an {@code INITIAL} run does not, because a mirror still
     * being populated has nothing stale in it. This is the only difference between the two on GitLab.
     */
    @Override
    public void reconcile(IntegrationRef ref, SyncExecutionHandle handle, SyncJobType type) {
        dataSyncScheduler.syncWorkspaceNow(ref.workspaceId(), handle, type);
        if (handle.isCancellationRequested()) {
            handle.reportCancelled();
        }
    }

    /**
     * Manual backfill is always offered: {@code hephaestus.sync.backfill.enabled} gates the scheduled
     * backfill cycle, not the vendor's ability to backfill on request. {@link
     * GitLabHistoricalBackfillService#runBackfillPass} honors that distinction, so gating the
     * capability here would make an administratively paused cycle also un-resumable by hand — the
     * exact situation manual backfill exists for.
     */
    @Override
    public boolean supportsBackfill() {
        return true;
    }

    /**
     * Drives historical backfill pass-by-pass across the workspace until no pass advances a repository or
     * cancellation is requested — the same drive-to-completion shape {@link
     * de.tum.cit.aet.hephaestus.integration.scm.github.sync.status.GitHubIntegrationSyncRunner#backfill}
     * has, so the two identical-looking "Run backfill" buttons mean the same thing. Each pass is the same
     * per-repository step (cooldown and initial-sync gates included) the 60s scheduler tick performs, but
     * scoped to one connection and under this job's own progress reporting and cooperative cancellation.
     *
     * <p>The loop drains because {@link GitLabHistoricalBackfillService} applies its five-minute success
     * cooldown only to the scheduler's 60s tick, not to a pass a job owns: that cooldown is tick-spacing,
     * and the administrator who clicked the button is the pacing signal. Error backoff still applies to
     * everybody, and rate limiting is enforced inside the sync services regardless of caller, so a manual
     * run cannot outrun the vendor.
     *
     * <p>The no-progress warning is therefore raised only when <em>nothing</em> advanced across the whole
     * job, not merely on the pass that ends the loop: a backfill that moved two repositories and then hit
     * the cooldown is a clean success, and reporting it as SUCCEEDED_WITH_WARNINGS would be the same
     * misreading in the opposite direction.
     */
    @Override
    public void backfill(IntegrationRef ref, SyncExecutionHandle handle) {
        long workspaceId = ref.workspaceId();
        backfillService.repairCompletedRepositories(workspaceId, handle);
        int totalProcessed = 0;

        while (!handle.isCancellationRequested()) {
            int processed = backfillService.runBackfillPass(workspaceId, handle);
            totalProcessed += processed;
            if (processed == 0) {
                // Nothing advanced this pass: backfill is complete, or every pending repository is gated
                // (cooldown / rate limit). Indistinguishable from here — and either way another pass could
                // only repeat the skip, so stop rather than spin.
                if (totalProcessed == 0) {
                    // The whole job moved nothing. Surface it as a warning rather than a clean success —
                    // matching GitHubIntegrationSyncRunner.
                    handle.reportWarnings();
                    log.info("Manual GitLab backfill advanced no repository: workspaceId={}", workspaceId);
                }
                break;
            }
        }

        // The loop exits with the flag still set only when it aborted on a cancel checkpoint (the
        // no-progress break leaves it clear); declare it so the job finalizes CANCELLED rather than
        // SUCCEEDED. runBackfillPass itself returns early on its own between-repository checkpoint.
        if (handle.isCancellationRequested()) {
            handle.reportCancelled();
        }
    }
}
