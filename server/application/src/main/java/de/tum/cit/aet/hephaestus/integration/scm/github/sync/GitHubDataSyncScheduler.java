package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncStatistics;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.GitHubOrganizationSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.Project;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for periodic GitHub data synchronization across all scopes.
 *
 * <h2>Purpose</h2>
 * Runs scheduled sync jobs using Spring's {@code @Scheduled} annotation.
 * Iterates over all active scopes and syncs their repositories via GraphQL.
 *
 * <h2>Architecture</h2>
 * Uses SPI interfaces to remain decoupled from consuming modules:
 * <ul>
 *   <li>{@link SyncTargetProvider} - provides scope/repository info to sync</li>
 *   <li>{@link SyncContextProvider} - manages context for logging and isolation</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * This class is thread-safe by design:
 * <ul>
 *   <li>Spring's default scheduling uses a single-threaded executor, so
 *       {@code syncDataCron()} will not run concurrently with itself</li>
 *   <li>Each scope sync is isolated - no shared mutable state between scopes</li>
 *   <li>Context is set/cleared per scope via {@code SyncContextProvider}</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Spring creates this component at startup (after {@code IntegrationNatsConsumer}
 *       due to {@code @Order(2)})</li>
 *   <li>{@code @Scheduled} method runs at cron interval from {@code hephaestus.sync.cron}</li>
 *   <li>Each run processes all ACTIVE scopes, respecting monitoring filters</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code hephaestus.sync.cron} - Cron expression for sync schedule (default: "0 0 3 * * *" = 3 AM daily)</li>
 *   <li>{@code hephaestus.sync.filters.allowed-organizations} - Limit to specific orgs (dev filter)</li>
 *   <li>{@code hephaestus.sync.filters.allowed-repositories} - Limit to specific repos (dev filter)</li>
 * </ul>
 *
 * @see GitHubDataSyncService
 * @see SyncTargetProvider
 */
@Order(value = 2)
@Component
public class GitHubDataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitHubDataSyncScheduler.class);

    private final SyncTargetProvider syncTargetProvider;
    private final SyncContextProvider syncContextProvider;
    private final GitHubDataSyncService dataSyncService;
    private final GitHubDeletionSweepService deletionSweepService;
    private final GitHubSubIssueSyncService subIssueSyncService;
    private final GitHubIssueTypeSyncService issueTypeSyncService;
    private final GitHubIssueDependencySyncService issueDependencySyncService;
    private final GitHubProjectSyncService projectSyncService;
    private final GitHubOrganizationSyncService organizationSyncService;
    private final GitHubTeamSyncService teamSyncService;
    private final OrganizationRepository organizationRepository;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final RateLimitTracker rateLimitTracker;
    private final Executor monitoringExecutor;
    private final ConnectionRepository connectionRepository;
    private final SyncJobService syncJobService;

    public GitHubDataSyncScheduler(
            SyncTargetProvider syncTargetProvider,
            SyncContextProvider syncContextProvider,
            GitHubDataSyncService dataSyncService,
            GitHubDeletionSweepService deletionSweepService,
            GitHubSubIssueSyncService subIssueSyncService,
            GitHubIssueTypeSyncService issueTypeSyncService,
            GitHubIssueDependencySyncService issueDependencySyncService,
            GitHubProjectSyncService projectSyncService,
            GitHubOrganizationSyncService organizationSyncService,
            GitHubTeamSyncService teamSyncService,
            OrganizationRepository organizationRepository,
            SyncSchedulerProperties syncSchedulerProperties,
            RateLimitTracker rateLimitTracker,
            @Qualifier("monitoringExecutor") Executor monitoringExecutor,
            ConnectionRepository connectionRepository,
            SyncJobService syncJobService) {
        this.syncTargetProvider = syncTargetProvider;
        this.syncContextProvider = syncContextProvider;
        this.dataSyncService = dataSyncService;
        this.deletionSweepService = deletionSweepService;
        this.subIssueSyncService = subIssueSyncService;
        this.issueTypeSyncService = issueTypeSyncService;
        this.issueDependencySyncService = issueDependencySyncService;
        this.projectSyncService = projectSyncService;
        this.organizationSyncService = organizationSyncService;
        this.teamSyncService = teamSyncService;
        this.organizationRepository = organizationRepository;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.rateLimitTracker = rateLimitTracker;
        this.monitoringExecutor = monitoringExecutor;
        this.connectionRepository = connectionRepository;
        this.syncJobService = syncJobService;
    }

    /**
     * Logs the sync configuration at startup for visibility.
     * Clarifies the difference between incremental sync and historical backfill.
     */
    @PostConstruct
    void logSyncConfiguration() {
        log.info(
                "Incremental sync config: runOnStartup={}, cron={}, timeframeDays={}, cooldownMinutes={} "
                        + "(syncs repos, issues, PRs, labels, milestones, teams, AND projects)",
                syncSchedulerProperties.runOnStartup(),
                syncSchedulerProperties.cron(),
                syncSchedulerProperties.timeframeDays(),
                syncSchedulerProperties.cooldownMinutes());
        log.info(
                "Historical backfill config: enabled={}, batchSize={}, intervalSeconds={} "
                        + "(backfills old issues/PRs only - does NOT affect projects)",
                syncSchedulerProperties.backfill().enabled(),
                syncSchedulerProperties.backfill().batchSize(),
                syncSchedulerProperties.backfill().intervalSeconds());

        var filters = syncSchedulerProperties.filters();
        if (!filters.allowedOrganizations().isEmpty()
                || !filters.allowedRepositories().isEmpty()) {
            log.info(
                    "Sync filters active: allowedOrganizations={}, allowedRepositories={}",
                    filters.allowedOrganizations(),
                    filters.allowedRepositories());
        }
    }

    /**
     * Scheduled job to sync GitHub data for all active scopes.
     * Respects monitoring filters to limit sync scope during development.
     */
    @Scheduled(cron = "${hephaestus.sync.cron}")
    @SchedulerLock(name = "github-data-sync", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void syncDataCron() {
        log.info("Starting scheduled sync");

        SyncStatistics stats = syncTargetProvider.getSyncStatistics();

        // Pre-filtered by status and monitoring scope.
        List<SyncSession> sessions = syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB);

        if (sessions.isEmpty()) {
            log.info(
                    "No scopes to sync: totalScopes={}, skippedByStatus={}, skippedByFilter={}",
                    stats.totalScopes(),
                    stats.skippedByStatus(),
                    stats.skippedByFilter());
            return;
        }

        if (stats.filterActive()) {
            log.info(
                    "Monitoring filter active: scopesToSync={}, totalScopes={}, skippedByStatus={}, skippedByFilter={}",
                    stats.activeAndAllowed(),
                    stats.totalScopes(),
                    stats.skippedByStatus(),
                    stats.skippedByFilter());
        } else {
            log.info(
                    "Found scopes to sync: count={}, skippedByStatus={}",
                    stats.activeAndAllowed(),
                    stats.skippedByStatus());
        }

        // Each workspace has its own GitHub App installation with separate rate limits, so syncing
        // them in parallel (virtual threads via monitoringExecutor) is safe, and whenComplete() below
        // logs and counts each outcome independently so one workspace's failure can't block the rest.
        //
        // No global timeout: large repositories (10k+ issues) combined with rate limits can
        // legitimately take many hours. Individual GraphQL calls have their own timeouts for transient
        // failures. Spring's single-threaded scheduler prevents overlapping runs, and join() respects
        // thread interruption for JVM shutdown.
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        CompletableFuture<?>[] futures = sessions.stream()
                .map(session -> CompletableFuture.runAsync(() -> syncScope(session), monitoringExecutor)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                // Already logged inside syncScope
                                failureCount.incrementAndGet();
                            } else {
                                successCount.incrementAndGet();
                            }
                        }))
                .toArray(CompletableFuture[]::new);

        // Use get() instead of join() because get() throws InterruptedException,
        // allowing graceful shutdown when Ctrl+C is pressed.
        try {
            CompletableFuture.allOf(futures).get();
            log.info(
                    "Completed scheduled sync: scopeCount={}, successful={}, failed={}",
                    sessions.size(),
                    successCount.get(),
                    failureCount.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Scheduled sync interrupted (shutdown requested): scopeCount={}, successful={}, failed={}",
                    sessions.size(),
                    successCount.get(),
                    failureCount.get());
        } catch (ExecutionException e) {
            // Should not happen since each future handles its own exceptions via whenComplete()
            log.error("Unexpected error during scheduled sync", e);
        }
    }

    /**
     * Run the same warning-aware body used by the scheduled path for one manual job.
     *
     * @param type the job type; decides whether the body ends with a deletion sweep
     */
    public void syncWorkspaceNow(long workspaceId, SyncExecutionHandle handle, SyncJobType type) {
        SyncSession session = syncTargetProvider.getSyncSessions(IntegrationKind.GITHUB).stream()
                .filter(candidate -> candidate.scopeId().equals(workspaceId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("No active GitHub sync scope for workspace " + workspaceId));
        runScopeSyncBody(session, handle, type);
    }

    /**
     * Resolves the workspace's ACTIVE GitHub {@link Connection} (if any) and wraps the scope sync in
     * {@link SyncJobService#run} as a {@code RECONCILIATION}/{@code SCHEDULED} job row, so the daily
     * cron shows up in the connection's job history. Workspaces without an ACTIVE
     * GitHub connection (shouldn't normally reach here, since {@code getSyncSessions} already filters
     * by active provider — defensive) run the sync unrecorded rather than skipping it outright.
     *
     * <p>A {@link SyncJobConflictException} means a manual sync is already active for this
     * connection — the scheduled run is skipped for this tick rather than queued or run unrecorded,
     * so the manual job's progress/outcome isn't clobbered.
     */
    private void syncScope(SyncSession session) {
        Optional<Connection> connection =
                connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        session.scopeId(), IntegrationKind.GITHUB, IntegrationState.ACTIVE);
        if (connection.isEmpty()) {
            runScopeSyncBody(session, null, SyncJobType.RECONCILIATION);
            return;
        }

        try {
            syncJobService.run(
                    new SyncJobRequest(
                            session.scopeId(),
                            connection.get().getId(),
                            IntegrationKind.GITHUB,
                            SyncJobType.RECONCILIATION,
                            SyncJobTrigger.SCHEDULED,
                            null),
                    handle -> runScopeSyncBody(session, handle, SyncJobType.RECONCILIATION));
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped scheduled sync: reason=manualSyncAlreadyRunning, scopeId={}, scopeSlug={}, activeJobId={}",
                    session.scopeId(),
                    session.slug(),
                    e.activeJob().getId());
        }
    }

    /**
     * Runs one scope's full sync. When invoked through a {@link SyncJobService} job the caller passes the
     * live {@link SyncExecutionHandle} so this body reports coarse repos-done/repos-total progress and honors a
     * cooperative cancel between repositories — parity with the manual "Sync now" runner. The unrecorded
     * fallback (no ACTIVE connection) passes {@code null} and simply runs to completion.
     */
    private void runScopeSyncBody(SyncSession session, @Nullable SyncExecutionHandle handle, SyncJobType type) {
        try {
            syncContextProvider.setContext(session.syncContext());

            log.info(
                    "Starting scope sync: scopeId={}, scopeSlug={}, accountLogin={}",
                    session.scopeId(),
                    session.slug(),
                    sanitizeForLog(session.accountLogin()));

            Runnable syncTask = syncContextProvider.wrapWithContext(() -> {
                // Report every phase boundary, not just the repository loop. A reconcile spends real
                // time in these org-level phases, and without a report the UI shows the previous phase's
                // last tick for their whole duration — the multi-phase sync reads as a stall.
                int totalRepos = session.syncTargets().size();
                reportPhase(handle, SyncPhase.ORGANIZATION, "Syncing organization issue types", 0, totalRepos);

                // Issue types are organization-level entities that issues reference, so they must
                // exist before repository sync processes issues.
                syncIssueTypes(session, handle);

                // Projects sync before repositories so embedded project items can be linked.
                if (syncSchedulerProperties.projects().enabled()) {
                    reportPhase(handle, SyncPhase.ORGANIZATION, "Syncing organization projects", 0, totalRepos);
                    syncProjects(session, handle);
                } else {
                    log.debug("Skipped project sync: reason=projectsSyncDisabled, scopeId={}", session.scopeId());
                }

                // Sync repositories (issues/PRs include embedded project items)
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
                                "Rate limit critical during scheduled sync, waiting for reset: scopeId={}, scopeSlug={}, remaining={}, totalRepos={}, reposProcessed={}, reposRemaining={}",
                                session.scopeId(),
                                session.slug(),
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

                // Relink orphaned project items now that issues/PRs have been synced.
                // Project items created before their referenced issues were synced locally
                // will have NULL issue_id but a valid content_database_id. This fills
                // in the FK for any items whose issues now exist. Skipped when project
                // sync is disabled — there are no project items to relink.
                if (syncSchedulerProperties.projects().enabled()) {
                    projectSyncService.relinkOrphanedProjectItems();
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
                    "Aborting scope sync: reason=installationDeleted, scopeId={}, scopeSlug={}, installationId={}",
                    session.scopeId(),
                    session.slug(),
                    e.getInstallationId());
            if (handle != null) {
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to sync scope: scopeId={}, scopeSlug={}", session.scopeId(), session.slug(), e);
            if (handle != null) {
                throw new IllegalStateException("Failed to sync GitHub scope " + session.scopeId(), e);
            }
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
            log.debug("Starting sub-issues sync: scopeId={}, scopeSlug={}", session.scopeId(), session.slug());
            subIssueSyncService.syncSubIssuesForScope(session.scopeId());
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync sub-issues: scopeId={}, scopeSlug={}", session.scopeId(), session.slug(), e);
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
            log.debug(
                    "Starting team sync: scopeId={}, scopeSlug={}, orgLogin={}",
                    session.scopeId(),
                    session.slug(),
                    sanitizeForLog(accountLogin));
            int teamCount = teamSyncService.syncTeamsForOrganization(session.scopeId(), accountLogin);
            syncTargetProvider.updateTeamsSyncTimestamp(session.scopeId(), Instant.now());
            log.debug(
                    "Completed team sync: scopeId={}, orgLogin={}, teamCount={}",
                    session.scopeId(),
                    sanitizeForLog(accountLogin),
                    teamCount);
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to sync teams: scopeId={}, scopeSlug={}, orgLogin={}",
                    session.scopeId(),
                    session.slug(),
                    sanitizeForLog(accountLogin),
                    e);
            reportWarning(handle);
        }
    }

    private void syncIssueTypes(SyncSession session, @Nullable SyncExecutionHandle handle) {
        try {
            log.debug("Starting issue types sync: scopeId={}, scopeSlug={}", session.scopeId(), session.slug());
            issueTypeSyncService.syncIssueTypesForScope(session.scopeId());
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync issue types: scopeId={}, scopeSlug={}", session.scopeId(), session.slug(), e);
            reportWarning(handle);
        }
    }

    private void syncIssueDependencies(SyncSession session, @Nullable SyncExecutionHandle handle) {
        try {
            log.debug("Starting issue dependencies sync: scopeId={}, scopeSlug={}", session.scopeId(), session.slug());
            issueDependencySyncService.syncDependenciesForScope(session.scopeId());
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to sync issue dependencies: scopeId={}, scopeSlug={}",
                    session.scopeId(),
                    session.slug(),
                    e);
            reportWarning(handle);
        }
    }

    private void syncProjects(SyncSession session, @Nullable SyncExecutionHandle handle) {
        String safeAccountLogin = sanitizeForLog(session.accountLogin());
        try {
            log.debug(
                    "Starting projects sync: scopeId={}, scopeSlug={}, accountLogin={}",
                    session.scopeId(),
                    session.slug(),
                    safeAccountLogin);

            // Ensure the organization exists locally before syncing projects
            Organization organization = organizationRepository
                    .findByLoginIgnoreCaseAndProvider_Type(session.accountLogin(), IdentityProviderType.GITHUB)
                    .orElse(null);
            if (organization == null) {
                log.info(
                        "Organization missing before project sync, attempting sync: scopeId={}, orgLogin={}",
                        session.scopeId(),
                        safeAccountLogin);
                organization = organizationSyncService.syncOrganization(session.scopeId(), session.accountLogin());
            }

            // Sync project list for the organization
            projectSyncService.syncProjectsForOrganization(session.scopeId(), session.accountLogin());

            if (organization == null) {
                log.debug("Skipped project items sync: reason=organizationNotFound, orgLogin={}", safeAccountLogin);
                return;
            }

            // Sync project items for each project that needs it
            // This is done separately because project items can be large and
            // benefit from resumable pagination via cursor persistence
            List<Project> projects = projectSyncService.getProjectsNeedingItemSync(organization.getId());
            if (projects.isEmpty()) {
                log.debug("Skipped project items sync: reason=noProjects, orgLogin={}", safeAccountLogin);
                return;
            }

            int totalItemsSynced = 0;
            int projectsWithItems = 0;

            for (Project project : projects) {
                try {
                    SyncResult itemResult = projectSyncService.syncProjectItems(session.scopeId(), project);
                    totalItemsSynced += itemResult.count();
                    if (itemResult.count() > 0) {
                        projectsWithItems++;
                    }

                    // If rate limited, stop processing more projects
                    if (itemResult.status() == SyncResult.Status.ABORTED_RATE_LIMIT) {
                        log.info(
                                "Stopping project items sync: reason=rateLimited, scopeId={}, projectsProcessed={}",
                                session.scopeId(),
                                projectsWithItems);
                        reportWarning(handle);
                        break;
                    }
                } catch (InstallationNotFoundException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn(
                            "Failed to sync project items: projectId={}, scopeId={}, error={}",
                            project.getId(),
                            session.scopeId(),
                            e.getMessage());
                    reportWarning(handle);
                    // Continue with next project on error
                }
            }

            log.debug(
                    "Completed projects sync: scopeId={}, orgLogin={}, projectCount={}, itemsSynced={}",
                    session.scopeId(),
                    safeAccountLogin,
                    projects.size(),
                    totalItemsSynced);
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync projects: scopeId={}, scopeSlug={}", session.scopeId(), session.slug(), e);
            reportWarning(handle);
        }
    }

    private static void reportWarning(@Nullable SyncExecutionHandle handle) {
        if (handle != null) {
            handle.reportWarnings();
        }
    }
}
