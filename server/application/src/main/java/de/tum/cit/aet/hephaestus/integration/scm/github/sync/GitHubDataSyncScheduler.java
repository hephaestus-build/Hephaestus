package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncStatistics;
import de.tum.cit.aet.hephaestus.integration.core.sync.ScopeSyncFanOut;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * Iterates over all active scopes, records each scope's run as a sync job, and has
 * {@link GitHubScopeSync} sync its repositories via GraphQL.
 *
 * <h2>Architecture</h2>
 * Uses SPI interfaces to remain decoupled from consuming modules:
 * <ul>
 *   <li>{@link SyncTargetProvider} - provides scope/repository info to sync</li>
 *   <li>{@link SyncContextProvider} - manages context for logging and isolation, set by {@link GitHubScopeSync}</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * This class is thread-safe by design:
 * <ul>
 *   <li>Spring's default scheduling uses a single-threaded executor, so
 *       {@code syncDataCron()} will not run concurrently with itself</li>
 *   <li>Each scope sync is isolated - no shared mutable state between scopes</li>
 *   <li>Context is set/cleared per scope via {@code SyncContextProvider} in {@link GitHubScopeSync}</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Spring creates this component at startup (after {@code ProviderEventConsumer}
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
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final Executor monitoringExecutor;
    private final ConnectionQueries connectionQueries;
    private final SyncJobService syncJobService;
    private final GitHubScopeSync scopeSync;

    public GitHubDataSyncScheduler(
            SyncTargetProvider syncTargetProvider,
            SyncSchedulerProperties syncSchedulerProperties,
            @Qualifier("monitoringExecutor") Executor monitoringExecutor,
            ConnectionQueries connectionQueries,
            SyncJobService syncJobService,
            GitHubScopeSync scopeSync) {
        this.syncTargetProvider = syncTargetProvider;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.monitoringExecutor = monitoringExecutor;
        this.connectionQueries = connectionQueries;
        this.syncJobService = syncJobService;
        this.scopeSync = scopeSync;
    }

    /**
     * Logs the sync configuration at startup for visibility.
     * Clarifies the difference between incremental sync and historical backfill.
     */
    @PostConstruct
    void logSyncConfiguration() {
        log.info(
                "Incremental sync config: runOnStartup={}, cron={}, timeframeDays={}, cooldownMinutes={} "
                        + "(syncs repos, issues, PRs, labels, milestones, and teams)",
                syncSchedulerProperties.runOnStartup(),
                syncSchedulerProperties.cron(),
                syncSchedulerProperties.timeframeDays(),
                syncSchedulerProperties.cooldownMinutes());
        log.info(
                "Historical backfill config: enabled={}, batchSize={}, intervalSeconds={} "
                        + "(backfills old issues/PRs)",
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
        // them in parallel (virtual threads via monitoringExecutor) is safe; one workspace's failure
        // can't block the rest. ScopeSyncFanOut limits how many run at once, for the database pool.
        //
        // No global timeout: large repositories (10k+ issues) combined with rate limits can
        // legitimately take many hours. Individual GraphQL calls have their own timeouts for transient
        // failures.
        ScopeSyncFanOut.Outcome outcome =
                ScopeSyncFanOut.run(sessions, this::syncScope, monitoringExecutor, syncJobService::isRunning);
        log.info(
                "Completed scheduled sync: scopeCount={}, successful={}, failed={}, notStarted={}, interrupted={}",
                sessions.size(),
                outcome.succeeded(),
                outcome.failed(),
                outcome.notStarted(),
                Thread.currentThread().isInterrupted());
    }

    /**
     * Resolves the workspace's ACTIVE GitHub {@link ConnectionQueries.ConnectionSnapshot} (if any) and wraps the scope sync in
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
        Optional<ConnectionQueries.ConnectionSnapshot> connection =
                connectionQueries.findActive(session.scopeId(), IntegrationKind.GITHUB);
        if (connection.isEmpty()) {
            scopeSync.run(session, null, SyncJobType.RECONCILIATION);
            return;
        }

        try {
            syncJobService.run(
                    new SyncJobRequest(
                            session.scopeId(),
                            connection.get().id(),
                            IntegrationKind.GITHUB,
                            SyncJobType.RECONCILIATION,
                            SyncJobTrigger.SCHEDULED,
                            null),
                    handle -> scopeSync.run(session, handle, SyncJobType.RECONCILIATION));
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped scheduled sync: reason=manualSyncAlreadyRunning, scopeId={}, activeJobId={}",
                    session.scopeId(),
                    e.activeJob().getId());
        }
    }
}
