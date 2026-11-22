package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for periodic GitLab data synchronization across all GitLab scopes.
 *
 * <h2>Purpose</h2>
 * Provides eventual consistency for GitLab data by running scheduled re-sync jobs.
 * This closes the critical gap where missed webhooks (restarts, provider inbox outages,
 * GitLab's 40-failure auto-disable) cause permanent data drift.
 *
 * <h2>Architecture</h2>
 * Mirrors {@link GitHubDataSyncScheduler}: it records each scope's run as a sync job, and
 * {@link GitLabScopeSync} runs the GitLab-specific sync services.
 * Uses SPI interfaces to remain decoupled from consuming modules:
 * <ul>
 *   <li>{@link SyncTargetProvider} - provides scope/repository info to sync</li>
 *   <li>{@link SyncContextProvider} - manages context for logging and isolation, set by {@link GitLabScopeSync}</li>
 * </ul>
 * Runs on the same cron schedule, processing all active GitLab workspaces in parallel.
 * Each workspace syncs: group projects, memberships, labels, milestones, issues,
 * merge requests, and teams.
 *
 * <h2>Thread Safety</h2>
 * Same guarantees as {@link GitHubDataSyncScheduler}: single-threaded scheduling,
 * parallel workspace processing via virtual threads, per-workspace error isolation.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabDataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitLabDataSyncScheduler.class);

    private final SyncTargetProvider syncTargetProvider;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final Executor monitoringExecutor;
    private final ConnectionQueries connectionQueries;
    private final SyncJobService syncJobService;
    private final GitLabScopeSync scopeSync;

    public GitLabDataSyncScheduler(
            SyncTargetProvider syncTargetProvider,
            SyncSchedulerProperties syncSchedulerProperties,
            @Qualifier("monitoringExecutor") Executor monitoringExecutor,
            ConnectionQueries connectionQueries,
            SyncJobService syncJobService,
            GitLabScopeSync scopeSync) {
        this.syncTargetProvider = syncTargetProvider;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.monitoringExecutor = monitoringExecutor;
        this.connectionQueries = connectionQueries;
        this.syncJobService = syncJobService;
        this.scopeSync = scopeSync;
    }

    @PostConstruct
    void logSyncConfiguration() {
        log.info(
                "GitLab incremental sync config: cron={} (syncs groups, memberships, labels, milestones, issues, MRs, teams)",
                syncSchedulerProperties.cron());
    }

    /**
     * Scheduled job to sync GitLab data for all active GitLab workspaces.
     * Runs on the same cron as GitHub sync (default: daily at 3 AM).
     */
    @Scheduled(cron = "${hephaestus.sync.cron}")
    @SchedulerLock(name = "gitlab-data-sync", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void syncDataCron() {
        List<SyncSession> sessions = syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB);

        if (sessions.isEmpty()) {
            log.debug("No active GitLab workspaces to sync");
            return;
        }

        log.info("Starting scheduled GitLab sync: workspaceCount={}", sessions.size());

        ScopeSyncFanOut.Outcome outcome = ScopeSyncFanOut.run(
                sessions, this::syncScopeWithJobRecording, monitoringExecutor, syncJobService::isRunning);
        log.info(
                "Completed scheduled GitLab sync: workspaceCount={}, successful={}, failed={}, notStarted={},"
                        + " interrupted={}",
                sessions.size(),
                outcome.succeeded(),
                outcome.failed(),
                outcome.notStarted(),
                Thread.currentThread().isInterrupted());
    }

    /**
     * Wraps {@link GitLabScopeSync#run} in the {@code SyncJobService} template when the scope has an ACTIVE
     * GitLab {@link ConnectionQueries.ConnectionSnapshot} — the cron's per-scope fan-out is the {@code SCHEDULED}/
     * {@code RECONCILIATION} trigger path. A scope without an ACTIVE
     * connection (e.g. a stale sync target left over from a disconnected workspace) still syncs,
     * just without a job row — the sync-target enumeration and the connection registry are not
     * kept aligned, and skipping would leave that target unsynced.
     *
     * <p>A {@link SyncJobConflictException} (this connection already has an active job — most
     * plausibly a manual "Sync now" the admin triggered) skips this scope's cron run entirely rather
     * than racing it; the manual job's own completion will bring watermarks current.
     */
    private void syncScopeWithJobRecording(SyncSession session) {
        Optional<ConnectionQueries.ConnectionSnapshot> connection =
                connectionQueries.findActive(session.scopeId(), IntegrationKind.GITLAB);
        if (connection.isEmpty()) {
            syncScope(session);
            return;
        }

        try {
            syncJobService.run(
                    new SyncJobRequest(
                            session.scopeId(),
                            connection.get().id(),
                            IntegrationKind.GITLAB,
                            SyncJobType.RECONCILIATION,
                            SyncJobTrigger.SCHEDULED,
                            null),
                    handle -> scopeSync.run(session, handle, SyncJobType.RECONCILIATION));
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped scheduled GitLab sync, already an active sync job: scopeId={}, connectionId={}",
                    session.scopeId(),
                    connection.get().id());
        }
    }

    /** Unrecorded fallback (no ACTIVE connection to attach a job to): run to completion, no handle. */
    private void syncScope(SyncSession session) {
        // The unrecorded cron fallback is still a reconciliation pass — sweep deletions like the recorded one.
        scopeSync.run(session, null, SyncJobType.RECONCILIATION);
    }
}
