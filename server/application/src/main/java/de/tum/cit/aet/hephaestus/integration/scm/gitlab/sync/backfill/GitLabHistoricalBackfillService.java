package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.sync.status.BackfillTally;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Historical backfill service for GitLab repositories.
 * <p>
 * Mirrors the GitHub {@link de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService} but uses GitLab-specific
 * sync services with {@code CREATED_DESC} ordering to fetch historical data
 * that predates the initial incremental sync window.
 * <p>
 * Backfill state is tracked via the {@link BackfillStateProvider} SPI using the same
 * fields as GitHub backfill (highWaterMark, checkpoint, backfillLastRunAt).
 * <p>
 * Each cycle processes one batch per repository, respecting cooldowns between runs.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabHistoricalBackfillService {

    private static final Logger log = LoggerFactory.getLogger(GitLabHistoricalBackfillService.class);
    private static final Duration COOLDOWN_NORMAL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_ERROR = Duration.ofMinutes(15);

    private final SyncTargetProvider syncTargetProvider;
    private final RepositoryRepository repositoryRepository;
    private final OrganizationRepository organizationRepository;
    private final ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider;
    private final SyncSchedulerProperties syncSchedulerProperties;

    // Per-repository cooldown tracking for error backoff
    private final Map<Long, Cooldown> repositoryCooldowns = new ConcurrentHashMap<>();

    public GitLabHistoricalBackfillService(
            SyncTargetProvider syncTargetProvider,
            RepositoryRepository repositoryRepository,
            OrganizationRepository organizationRepository,
            ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider,
            SyncSchedulerProperties syncSchedulerProperties) {
        this.syncTargetProvider = syncTargetProvider;
        this.repositoryRepository = repositoryRepository;
        this.organizationRepository = organizationRepository;
        this.syncServiceHolderProvider = syncServiceHolderProvider;
        this.syncSchedulerProperties = syncSchedulerProperties;
    }

    /**
     * Runs one backfill cycle across all active GitLab workspaces.
     * Processes one batch per repository that needs backfill.
     *
     * @return number of repositories processed
     */
    public int runBackfillCycle() {
        return runBackfillPass(null, null);
    }

    /**
     * One backfill pass, optionally threading the job's {@link SyncExecutionHandle} so a manually
     * triggered {@code BACKFILL} job reports per-repository progress and can be cancelled between
     * repositories — the per-connection entry point behind {@code GitlabIntegrationSyncRunner}.
     * Performs exactly the same per-repository step (cooldown and initial-sync gates included)
     * regardless of caller, under the handle's progress reporting instead of the scheduler's cadence.
     *
     * <p>Deliberately ignores {@link SyncSchedulerProperties.BackfillProperties#enabled()} — that flag
     * gates the scheduled cycle's tick ({@link #runBackfillCycle}'s caller), and a manually triggered
     * backfill is the point even when the scheduled cycle is administratively disabled.
     *
     * @param scopeFilter restrict to this workspace, or {@code null} for the whole fleet (the
     *                    scheduled cycle)
     * @param handle      the job handle to report progress on and poll for cancellation, or
     *                    {@code null} when no job owns this pass
     * @return number of repositories that made progress this pass
     */
    public int runBackfillPass(@Nullable Long scopeFilter, @Nullable SyncExecutionHandle handle) {
        GitLabSyncServiceHolder services = syncServiceHolderProvider.getIfAvailable();
        if (services == null) return 0;

        GitLabIssueSyncService issueSync = services.getIssueSyncService();
        GitLabMergeRequestSyncService mrSync = services.getMergeRequestSyncService();
        if (issueSync == null && mrSync == null) return 0;

        List<SyncSession> sessions = syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB).stream()
                .filter(session -> scopeFilter == null || scopeFilter.equals(session.scopeId()))
                .toList();
        if (sessions.isEmpty()) return 0;

        int batchSize = syncSchedulerProperties.backfill().batchSize();
        AtomicInteger processed = new AtomicInteger(0);

        for (SyncSession session : sessions) {
            Long providerId = getGitLabProviderId(session.accountLogin());

            // Determinate progress from the already-persisted high-water-mark / checkpoint columns.
            // Built over every target of the scope rather than only the pending ones, so the
            // denominator holds still as repositories complete instead of shrinking under the bar.
            //
            // Scoped per session because that is the grain the refresh below can re-read cheaply. Only
            // the handle-driven path reports at all, and it always filters to exactly one session.
            BackfillTally tally = new BackfillTally(session.syncTargets());
            int reposTotal = (int) session.syncTargets().stream()
                    .filter(t -> !t.isBackfillComplete())
                    .count();
            int reposDone = 0;

            for (SyncTarget target : session.syncTargets()) {
                if (handle != null && handle.isCancellationRequested()) {
                    return processed.get();
                }
                if (target.isBackfillComplete()) continue;
                if (isOnCooldown(target.id(), handle != null)) continue;

                // Only backfill repos that have completed initial sync
                if (target.lastIssuesSyncedAt() == null && target.lastPullRequestsSyncedAt() == null) {
                    continue;
                }

                Optional<Repository> repoOpt = providerId != null
                        ? repositoryRepository.findByNameWithOwnerAndProviderId(
                                target.repositoryNameWithOwner(), providerId)
                        : repositoryRepository.findByNameWithOwner(target.repositoryNameWithOwner());

                if (repoOpt.isEmpty()) continue;
                Repository repo = repoOpt.get();

                boolean worked = backfillRepository(session.scopeId(), repo, target, issueSync, mrSync, batchSize);

                if (worked) {
                    processed.incrementAndGet();
                }
                reposDone++;
                if (handle != null) {
                    // Re-read the checkpoint columns the batch just wrote, then report. A per-repository
                    // tick is already the right grain here, unlike GitHub's per-page reporting: a GitLab
                    // batch is bounded by ITEM count (batch-size = 25 items), not page count, so one
                    // repository's batch is a page or two of vendor I/O — seconds, not the minutes a
                    // 25-page GitHub batch takes.
                    tally.refresh(syncTargetProvider.getSyncTargetsForScope(session.scopeId()));
                    handle.progress(
                            tally.itemsProcessed(),
                            tally.itemsTotal(),
                            SyncProgress.ofResource(
                                    SyncPhase.REPOSITORIES,
                                    "Backfilled " + target.repositoryNameWithOwner() + " — " + reposDone + " of "
                                            + reposTotal,
                                    target.repositoryNameWithOwner(),
                                    reposDone,
                                    reposTotal));
                }
            }
        }

        return processed.get();
    }

    private boolean backfillRepository(
            Long scopeId,
            Repository repo,
            SyncTarget target,
            @Nullable GitLabIssueSyncService issueSync,
            @Nullable GitLabMergeRequestSyncService mrSync,
            int batchSize) {
        String safeName = sanitizeForLog(repo.getNameWithOwner());
        boolean didWork = false;
        boolean attempted = false;

        // Backfill issues
        if (issueSync != null && !target.isIssueBackfillComplete()) {
            attempted = true;
            try {
                BackfillBatchResult result =
                        issueSync.backfillIssues(scopeId, repo, target.issueSyncCursor(), batchSize);

                if (result.aborted()) {
                    syncTargetProvider.updateSyncError(
                            target.id(), SyncPass.HISTORICAL_BACKFILL, "Historical issue backfill aborted");
                    repositoryCooldowns.put(target.id(), Cooldown.afterError(COOLDOWN_ERROR));
                    return false;
                }

                if (result.count() > 0) {
                    didWork = true;
                    updateIssueBackfillState(target, result);
                }

                if (result.complete() && result.count() == 0 && !target.isIssueBackfillInitialized()) {
                    // No issues at all — mark as complete
                    syncTargetProvider.updateIssueBackfillState(target.id(), 0, 0, null);
                }
            } catch (Exception e) {
                syncTargetProvider.updateSyncError(
                        target.id(),
                        SyncPass.HISTORICAL_BACKFILL,
                        "Historical issue backfill failed (" + e.getClass().getSimpleName() + ")");
                log.warn("Issue backfill failed: repo={}", safeName, e);
                repositoryCooldowns.put(target.id(), Cooldown.afterError(COOLDOWN_ERROR));
                return didWork;
            }
        }

        // Backfill merge requests
        if (mrSync != null && !target.isPullRequestBackfillComplete()) {
            attempted = true;
            try {
                BackfillBatchResult result =
                        mrSync.backfillMergeRequests(scopeId, repo, target.pullRequestSyncCursor(), batchSize);

                if (result.aborted()) {
                    syncTargetProvider.updateSyncError(
                            target.id(), SyncPass.HISTORICAL_BACKFILL, "Historical merge request backfill aborted");
                    repositoryCooldowns.put(target.id(), Cooldown.afterError(COOLDOWN_ERROR));
                    return didWork;
                }

                if (result.count() > 0) {
                    didWork = true;
                    updateMrBackfillState(target, result);
                }

                if (result.complete() && result.count() == 0 && !target.isPullRequestBackfillInitialized()) {
                    syncTargetProvider.updatePullRequestBackfillState(target.id(), 0, 0, null);
                }
            } catch (Exception e) {
                syncTargetProvider.updateSyncError(
                        target.id(),
                        SyncPass.HISTORICAL_BACKFILL,
                        "Historical merge request backfill failed ("
                                + e.getClass().getSimpleName() + ")");
                log.warn("MR backfill failed: repo={}", safeName, e);
                repositoryCooldowns.put(target.id(), Cooldown.afterError(COOLDOWN_ERROR));
                return didWork;
            }
        }

        if (didWork) {
            syncTargetProvider.updateIssueBackfillState(target.id(), null, null, Instant.now());
            repositoryCooldowns.put(target.id(), Cooldown.afterProgress(COOLDOWN_NORMAL));
        }

        if (attempted) {
            syncTargetProvider.updateSyncError(target.id(), SyncPass.HISTORICAL_BACKFILL, null);
        }

        return didWork;
    }

    private void updateIssueBackfillState(SyncTarget target, BackfillBatchResult result) {
        // Initialize high water mark on first batch
        Integer highWaterMark = null;
        if (!target.isIssueBackfillInitialized() && result.maxIid() > 0) {
            highWaterMark = result.maxIid();
        }

        // Update checkpoint (lowest IID seen — backfill counts down)
        Integer checkpoint = result.minIid() > 0 ? result.minIid() : null;

        // If complete and no more pages, mark done
        if (result.complete() && result.nextCursor() == null) {
            checkpoint = 0;
        }

        syncTargetProvider.updateIssueBackfillState(target.id(), highWaterMark, checkpoint, null);

        // Save cursor for pagination resumption
        String cursor = (result.complete() && result.nextCursor() == null) ? null : result.nextCursor();
        syncTargetProvider.updateSyncCursor(target.id(), SyncCursorKind.ISSUE, cursor);
    }

    private void updateMrBackfillState(SyncTarget target, BackfillBatchResult result) {
        Integer highWaterMark = null;
        if (!target.isPullRequestBackfillInitialized() && result.maxIid() > 0) {
            highWaterMark = result.maxIid();
        }

        Integer checkpoint = result.minIid() > 0 ? result.minIid() : null;

        if (result.complete() && result.nextCursor() == null) {
            checkpoint = 0;
        }

        syncTargetProvider.updatePullRequestBackfillState(target.id(), highWaterMark, checkpoint, null);

        String cursor = (result.complete() && result.nextCursor() == null) ? null : result.nextCursor();
        syncTargetProvider.updateSyncCursor(target.id(), SyncCursorKind.PULL_REQUEST, cursor);
    }

    /**
     * Whether this repository is gated right now.
     *
     * <p>The two cooldowns answer different questions, so a job-driven pass treats them differently.
     * {@code COOLDOWN_ERROR} is backoff against a vendor that just failed and applies to everybody.
     * {@code COOLDOWN_NORMAL} is only tick-spacing: it stops the 60s scheduler from spending every tick
     * on the same repository. An administrator who clicked "Run backfill" is the pacing signal, and
     * honoring the success cooldown there would end their run after a single productive pass — the two
     * identical-looking buttons would then mean different things on GitHub and GitLab. Rate limiting is
     * enforced inside the sync services regardless of caller, so dropping the spacing cannot outrun the
     * vendor.
     *
     * @param jobDriven {@code true} when a {@link SyncExecutionHandle} owns this pass
     */
    private boolean isOnCooldown(Long syncTargetId, boolean jobDriven) {
        Cooldown cooldown = repositoryCooldowns.get(syncTargetId);
        if (cooldown == null || !Instant.now().isBefore(cooldown.until())) return false;
        return cooldown.afterError() || !jobDriven;
    }

    /** A repository's cooldown, tagged with why it was applied. */
    private record Cooldown(Instant until, boolean afterError) {
        static Cooldown afterError(Duration duration) {
            return new Cooldown(Instant.now().plus(duration), true);
        }

        static Cooldown afterProgress(Duration duration) {
            return new Cooldown(Instant.now().plus(duration), false);
        }
    }

    /**
     * Resolves the GitLab provider ID by looking up the organization.
     */
    private @Nullable Long getGitLabProviderId(String accountLogin) {
        return organizationRepository
                .findByLoginIgnoreCaseAndProvider_Type(accountLogin, IdentityProviderType.GITLAB)
                .map(org -> org.getProvider() != null ? org.getProvider().getId() : null)
                .orElse(null);
    }
}
