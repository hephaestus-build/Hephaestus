package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.GitHubHeadCheckRunsReread;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one GitHub scope's full sync: the organization's issue types, then each repository with progress, rate-limit
 * waits and a cooperative cancel between repositories, then the head check-run reread, teams, sub-issues and issue
 * dependencies, and, on a {@code RECONCILIATION}, the deletion sweep. {@link GitHubDataSyncScheduler} runs it for each
 * scope on the cron; a manual job runs it through {@link #syncWorkspaceNow}.
 *
 * @see GitHubDataSyncService
 */
@Component
public class GitHubScopeSync {

    private static final Logger log = LoggerFactory.getLogger(GitHubScopeSync.class);

    private final SyncTargetProvider syncTargetProvider;
    private final SyncContextProvider syncContextProvider;
    private final GitHubDataSyncService dataSyncService;
    private final GitHubDeletionSweepService deletionSweepService;
    private final GitHubSubIssueSyncService subIssueSyncService;
    private final GitHubIssueTypeSyncService issueTypeSyncService;
    private final GitHubIssueDependencySyncService issueDependencySyncService;
    private final GitHubTeamSyncService teamSyncService;
    private final RateLimitTracker rateLimitTracker;
    private final GitHubHeadCheckRunsReread headCheckRunsReread;

    public GitHubScopeSync(
            SyncTargetProvider syncTargetProvider,
            SyncContextProvider syncContextProvider,
            GitHubDataSyncService dataSyncService,
            GitHubDeletionSweepService deletionSweepService,
            GitHubSubIssueSyncService subIssueSyncService,
            GitHubIssueTypeSyncService issueTypeSyncService,
            GitHubIssueDependencySyncService issueDependencySyncService,
            GitHubTeamSyncService teamSyncService,
            RateLimitTracker rateLimitTracker,
            GitHubHeadCheckRunsReread headCheckRunsReread) {
        this.headCheckRunsReread = headCheckRunsReread;
        this.syncTargetProvider = syncTargetProvider;
        this.syncContextProvider = syncContextProvider;
        this.dataSyncService = dataSyncService;
        this.deletionSweepService = deletionSweepService;
        this.subIssueSyncService = subIssueSyncService;
        this.issueTypeSyncService = issueTypeSyncService;
        this.issueDependencySyncService = issueDependencySyncService;
        this.teamSyncService = teamSyncService;
        this.rateLimitTracker = rateLimitTracker;
    }

    /**
     * Run the same warning-aware body used by the scheduled path for one manual job.
     *
     * @param type the job type; decides whether the body ends with a deletion sweep
     */
    public void syncWorkspaceNow(long workspaceId, SyncExecutionHandle handle, SyncJobType type) {
        SyncSession session = syncTargetProvider
                .getSyncSession(workspaceId, IntegrationKind.GITHUB)
                .orElseThrow(() -> new IllegalStateException("No GitHub sync scope for workspace "
                        + workspaceId
                        + ": it is not active, or its provider refused its credential"));
        run(session, handle, type);
    }

    /**
     * Runs one scope's full sync. When invoked through a {@link SyncJobService} job the caller passes the
     * live {@link SyncExecutionHandle} so this body reports coarse repos-done/repos-total progress and honors a
     * cooperative cancel between repositories — parity with the manual "Sync now" runner. The unrecorded
     * fallback (no ACTIVE connection) passes {@code null} and simply runs to completion.
     */
    void run(SyncSession session, @Nullable SyncExecutionHandle handle, SyncJobType type) {
        try {
            syncContextProvider.setContext(session.syncContext());

            log.info("Starting scope sync: scopeId={}", session.scopeId());

            Runnable syncTask = syncContextProvider.wrapWithContext(() -> {
                // Report every phase boundary, not just the repository loop. A reconcile spends real
                // time in these org-level phases, and without a report the UI shows the previous phase's
                // last tick for their whole duration — the multi-phase sync reads as a stall.
                int totalRepos = session.syncTargets().size();
                reportPhase(handle, SyncPhase.ORGANIZATION, "Syncing organization issue types", 0, totalRepos);

                // Issue types are organization-level entities that issues reference, so they must
                // exist before repository sync processes issues.
                syncIssueTypes(session, handle);

                int reposProcessed = 0;
                for (SyncTarget target : session.syncTargets()) {
                    // Cooperative cancel checkpoint between repositories (matches the manual runner):
                    // a job cancelled mid-run stops before the next repo rather than mid-repository.
                    if (handle != null && handle.isCancellationRequested()) {
                        log.info(
                                "Scheduled sync cancelled between repositories: scopeId={}, reposProcessed={}, reposRemaining={}",
                                session.scopeId(),
                                reposProcessed,
                                totalRepos - reposProcessed);
                        break;
                    }
                    // Wait for rate limit reset instead of aborting — ensures all repos
                    // get synced even when rate limit is exhausted mid-loop. Without this,
                    // skipped repos would have stale/NULL sync timestamps and miss both
                    // incremental updates and historical backfill eligibility.
                    if (rateLimitTracker.isCritical(session.scopeId())) {
                        log.info(
                                "Rate limit critical during scheduled sync, waiting for reset: scopeId={}, remaining={}, totalRepos={}, reposProcessed={}, reposRemaining={}",
                                session.scopeId(),
                                rateLimitTracker.getRemaining(session.scopeId()),
                                session.syncTargets().size(),
                                reposProcessed,
                                session.syncTargets().size() - reposProcessed);
                        try {
                            dataSyncService.waitForRateLimitReset(
                                    session.scopeId(), handle == null ? null : handle::isCancellationRequested);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.info(
                                    "Scheduled sync interrupted while waiting for rate limit: scopeId={}, reposProcessed={}, reposRemaining={}",
                                    session.scopeId(),
                                    reposProcessed,
                                    session.syncTargets().size() - reposProcessed);
                            reportWarning(handle);
                            break;
                        }
                    }
                    if (!dataSyncService.syncSyncTarget(target)) {
                        reportWarning(handle);
                    }
                    reposProcessed++;
                    if (handle != null) {
                        handle.progress(
                                reposProcessed,
                                totalRepos,
                                // Just the repository — the "N of M" is already the progress bar's own
                                // reading (unitsCompleted/unitsTotal travel on the same record), so
                                // repeating it in the step text renders the same fact twice.
                                SyncProgress.ofResource(
                                        SyncPhase.REPOSITORIES,
                                        "Syncing " + target.repositoryNameWithOwner(),
                                        target.repositoryNameWithOwner(),
                                        reposProcessed,
                                        totalRepos));
                    }
                }

                // A cancel observed during the repo loop skips the remaining (cheaper) org-level phases —
                // cooperative best-effort, consistent with stopping between repositories above.
                if (handle != null && handle.isCancellationRequested()) {
                    return;
                }

                // A read of check runs that lagged its hook is made up here: only a later suite or status of the
                // head would read them again.
                if (!rateLimitTracker.isCritical(session.scopeId())) {
                    for (SyncTarget target : session.syncTargets()) {
                        headCheckRunsReread.reread(session.scopeId(), target.repositoryNameWithOwner());
                    }
                }

                // Sync teams AFTER repositories exist (team repo permissions need repos).
                // This mirrors the startup sync order in GitHubDataSyncService.
                reportPhase(handle, SyncPhase.TEAMS, "Syncing teams and memberships", reposProcessed, totalRepos);
                syncTeams(session, handle);

                // Sub-issues and dependencies are scope-level relationships that need issues/PRs to
                // already exist; skip when rate limit is critically low to avoid wasting calls.
                if (!rateLimitTracker.isCritical(session.scopeId())) {
                    reportPhase(
                            handle,
                            SyncPhase.ISSUES,
                            "Linking sub-issues and issue dependencies",
                            reposProcessed,
                            totalRepos);
                    syncSubIssues(session, handle);
                    syncIssueDependencies(session, handle);
                } else {
                    log.warn(
                            "Skipped sub-issue and dependency sync: reason=rateLimitCritical, scopeId={}, remaining={}",
                            session.scopeId(),
                            rateLimitTracker.getRemaining(session.scopeId()));
                    reportWarning(handle);
                }

                // Deletion sweep — RECONCILIATION only, and the one thing that makes RECONCILIATION
                // different from INITIAL. Everything above this line only ever upserts, so an issue or
                // pull request deleted upstream would otherwise survive here forever and keep inflating
                // the per-repository counts (webhooks are not redeliverable, and GitHub has no
                // pull_request.deleted event at all).
                //
                // Not on INITIAL: a mirror still being populated has nothing stale in it, and every row
                // not fetched yet would read as an upstream deletion.
                //
                // Last, deliberately: the sweep's set difference is only meaningful against a mirror
                // that has already had this run's upserts applied. Sweeping first would race the fetch
                // and tombstone rows that were about to arrive.
                if (type == SyncJobType.RECONCILIATION) {
                    var sweep = deletionSweepService.sweepScope(session.scopeId(), handle);
                    if (sweep.skipped()) {
                        // At least one repository's upstream listing could not be proven complete, so
                        // its deletions are still outstanding. Surface it rather than reporting a clean
                        // reconciliation that did not fully reconcile.
                        reportWarning(handle);
                    }
                }
            });

            syncTask.run();

            // A still-set cancel flag means the loop aborted on a checkpoint (not a normal finish);
            // declare it so the job finalizes CANCELLED rather than a false SUCCEEDED.
            if (handle != null && handle.isCancellationRequested()) {
                handle.reportCancelled();
            }
        } catch (InstallationNotFoundException e) {
            log.warn(
                    "Aborting scope sync: reason=installationDeleted, scopeId={}, installationId={}",
                    session.scopeId(),
                    e.getInstallationId());
            if (handle != null) {
                throw e;
            }
        } catch (Exception e) {
            if (handle != null) {
                throw new IllegalStateException("Failed to sync GitHub scope " + session.scopeId(), e);
            }
            log.error("Failed to sync scope: scopeId={}", session.scopeId(), e);
        } finally {
            syncContextProvider.clearContext();
        }
    }

    /**
     * Reports a phase boundary.
     *
     * <p>{@code reposProcessed}/{@code totalRepos} stay the job-global pair even for org-level phases,
     * which have no items of their own. Two reasons: inventing a second unit would make the bar jump
     * between scales as the sync moves between phases, and passing null here would blank a count the
     * repository loop already established — turning a determinate bar indeterminate mid-job. The phase
     * itself is the narrative; the bar keeps one meaning start to finish.
     */
    private static void reportPhase(
            @Nullable SyncExecutionHandle handle,
            SyncPhase phase,
            String currentStep,
            int reposProcessed,
            int totalRepos) {
        if (handle != null) {
            handle.progress(reposProcessed, totalRepos, SyncProgress.of(phase, currentStep));
        }
    }

    private void syncSubIssues(SyncSession session, @Nullable SyncExecutionHandle handle) {
        try {
            log.debug("Starting sub-issues sync: scopeId={}", session.scopeId());
            subIssueSyncService.syncSubIssuesForScope(session.scopeId());
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync sub-issues: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncTeams(SyncSession session, @Nullable SyncExecutionHandle handle) {
        String accountLogin = session.accountLogin();
        if (accountLogin == null || accountLogin.isBlank()) {
            log.debug("Skipped team sync: reason=noAccountLogin, scopeId={}", session.scopeId());
            return;
        }

        // Skip if rate limit is critically low to avoid wasting API calls
        if (rateLimitTracker.isCritical(session.scopeId())) {
            log.warn(
                    "Skipped team sync: reason=rateLimitCritical, scopeId={}, remaining={}",
                    session.scopeId(),
                    rateLimitTracker.getRemaining(session.scopeId()));
            reportWarning(handle);
            return;
        }

        try {
            log.debug("Starting team sync: scopeId={}", session.scopeId());
            int teamCount = teamSyncService.syncTeamsForOrganization(session.scopeId(), accountLogin);
            syncTargetProvider.updateTeamsSyncTimestamp(session.scopeId(), Instant.now());
            log.debug("Completed team sync: scopeId={}, teamCount={}", session.scopeId(), teamCount);
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync teams: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncIssueTypes(SyncSession session, @Nullable SyncExecutionHandle handle) {
        try {
            log.debug("Starting issue types sync: scopeId={}", session.scopeId());
            issueTypeSyncService.syncIssueTypesForScope(session.scopeId());
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync issue types: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncIssueDependencies(SyncSession session, @Nullable SyncExecutionHandle handle) {
        try {
            log.debug("Starting issue dependencies sync: scopeId={}", session.scopeId());
            issueDependencySyncService.syncDependenciesForScope(session.scopeId());
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync issue dependencies: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private static void reportWarning(@Nullable SyncExecutionHandle handle) {
        if (handle != null) {
            handle.reportWarnings();
        }
    }
}
