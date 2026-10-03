package de.tum.cit.aet.hephaestus.integration.scm.github.sync.status;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.BackfillTally;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GitHub {@link IntegrationSyncRunner}: the manual "Sync now" / "Backfill now" job bodies invoked by
 * {@code SyncJobService.run}.
 */
@Component
public class GitHubIntegrationSyncRunner implements IntegrationSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(GitHubIntegrationSyncRunner.class);

    private final GitHubDataSyncScheduler dataSyncScheduler;
    private final SyncTargetProvider syncTargetProvider;
    private final GitHubHistoricalBackfillService backfillService;
    private final SyncSchedulerProperties syncSchedulerProperties;

    public GitHubIntegrationSyncRunner(
            GitHubDataSyncScheduler dataSyncScheduler,
            SyncTargetProvider syncTargetProvider,
            GitHubHistoricalBackfillService backfillService,
            SyncSchedulerProperties syncSchedulerProperties) {
        this.dataSyncScheduler = dataSyncScheduler;
        this.syncTargetProvider = syncTargetProvider;
        this.backfillService = backfillService;
        this.syncSchedulerProperties = syncSchedulerProperties;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    /**
     * The same warning-aware body the daily scheduler uses, threaded with the job handle.
     *
     * <p>{@code type} is forwarded rather than dropped: it is what decides whether the body ends with
     * a deletion sweep, and it is the only difference between an {@code INITIAL} and a
     * {@code RECONCILIATION} run.
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
     * GitHubHistoricalBackfillService#runBackfillBatch} deliberately ignores the flag for exactly this
     * reason, so gating the capability here would make an administratively paused cycle also
     * un-resumable by hand — the exact situation manual backfill exists for.
     */
    @Override
    public boolean supportsBackfill() {
        return true;
    }

    /**
     * Drives historical backfill batch-by-batch across every monitored repository in the connection
     * until all are complete or cancellation is requested — the same per-repository step ({@link
     * GitHubHistoricalBackfillService#runBackfillBatch}, cooldowns/rate-limit gate included) the 60s
     * scheduler tick performs, but under this job's own progress reporting and cooperative
     * cancellation instead of the scheduler's fire-and-forget cadence.
     */
    @Override
    public void backfill(IntegrationRef ref, SyncExecutionHandle handle) {
        long workspaceId = ref.workspaceId();
        int batchSize = syncSchedulerProperties.backfill().batchSize();

        while (!handle.isCancellationRequested()) {
            // Every target in the scope, not just the pending ones: the pending set shrinks as
            // repositories finish, and computing the denominator from it would make the bar retreat
            // every time one completed. Completed targets contribute their full high-water mark to both
            // sides of the fraction, so the total holds still and the bar only fills.
            List<SyncTarget> allTargets = syncTargetProvider.getSyncTargetsForScope(workspaceId);
            List<SyncTarget> pending = allTargets.stream()
                    .filter(target -> !target.isBackfillComplete())
                    .toList();
            if (pending.isEmpty()) {
                break;
            }

            BackfillTally tally = new BackfillTally(allTargets);

            boolean anyWork = false;
            int reposDone = 0;
            for (SyncTarget target : pending) {
                if (handle.isCancellationRequested()) {
                    break;
                }
                int reposDoneSoFar = reposDone;
                int reposTotal = pending.size();
                // Report per vendor page rather than per batch: the handle owns the write budget, and
                // under-calling it would leave a manual backfill looking frozen for long stretches
                // before jumping straight to done.
                boolean didWork = backfillService.runBackfillBatch(
                        target,
                        batchSize,
                        (syncTargetId, repositoryName, phase, lowestNumberSeen, itemsSyncedInBatch) -> {
                            tally.observe(syncTargetId, phase, lowestNumberSeen);
                            handle.progress(
                                    tally.itemsProcessed(),
                                    tally.itemsTotal(),
                                    SyncProgress.ofResource(
                                            phase,
                                            step(
                                                    repositoryName,
                                                    phase,
                                                    lowestNumberSeen,
                                                    tally.highWaterMarkFor(syncTargetId, phase)),
                                            repositoryName,
                                            reposDoneSoFar,
                                            reposTotal));
                        });
                anyWork = anyWork || didWork;
                reposDone++;
                // Batch boundary: the checkpoint columns have just been written, so re-read them. The
                // live tally tracked one repo's page-level minimum; this folds in whatever the batch
                // actually persisted (including a repo that finished, or one that did no work at all).
                tally.refresh(syncTargetProvider.getSyncTargetsForScope(workspaceId));
                handle.progress(
                        tally.itemsProcessed(),
                        tally.itemsTotal(),
                        // Just the repository — "N of M" is already the progress bar's own reading
                        // (unitsCompleted/unitsTotal travel on the same record).
                        SyncProgress.ofResource(
                                SyncPhase.REPOSITORIES,
                                "Backfilled " + target.repositoryNameWithOwner(),
                                target.repositoryNameWithOwner(),
                                reposDone,
                                pending.size()));
            }

            if (!anyWork) {
                handle.reportWarnings();
                // Every pending repo was skipped this pass (cooldown / rate limit) — avoid a tight
                // spin loop; the scheduled backfill cycle keeps making progress at its own cadence.
                log.info(
                        "Manual backfill paused: reason=noProgressThisPass, workspaceId={}, reposPending={}",
                        workspaceId,
                        pending.size());
                break;
            }
        }

        // The loop exits with the flag still set only when it aborted on a cancel checkpoint (the
        // empty-pending and no-progress breaks leave it clear); declare it so the job finalizes CANCELLED.
        if (handle.isCancellationRequested()) {
            handle.reportCancelled();
        }
    }

    /**
     * The one human sentence the UI renders for a backfill page, e.g.
     * {@code "Backfilling ls1intum/Artemis — issues #4812 → #3200"}.
     *
     * <p>Backfill walks issue/PR numbers down toward #1, so the range reads as a countdown: high-water
     * mark on the left, live position on the right — the repository's own numbering rather than an
     * abstract percent.
     */
    static String step(String repositoryName, SyncPhase phase, int lowestNumberSeen, @Nullable Integer highWaterMark) {
        String entity = phase == SyncPhase.ISSUES ? "issues" : "pull requests";
        String range = highWaterMark == null ? "#" + lowestNumberSeen : "#" + highWaterMark + " → #" + lowestNumberSeen;
        return "Backfilling " + repositoryName + " — " + entity + " " + range;
    }
}
