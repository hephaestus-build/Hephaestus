package de.tum.cit.aet.hephaestus.integration.slack.sync;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.sync.status.SlackIntegrationSyncRunner;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Nightly Slack reconciliation, the Slack counterpart of the SCM data-sync schedulers: refresh channel metadata
 * (names, archived/kicked detection), then replay {@code conversations.history} for ACTIVE channels through the
 * consent-gated ingest stack ({@link SlackChannelHistorySyncService}). Workspaces run sequentially — unlike the SCM
 * sync there is no per-workspace fan-out benefit, because the clamped history methods are paced to ~1 request/minute
 * per workspace token anyway and the whole run is bounded by per-workspace request budgets.
 *
 * <p>{@link WorkspaceAgnostic} because the workspace enumeration is inherently fleet-wide (unscoped native query);
 * everything after enumeration is workspace-scoped. Locked cross-pod like the retention sweeper.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceAgnostic("Fleet-wide nightly Slack reconciliation; enumerates workspaces before scoping to each")
public class SlackDataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlackDataSyncScheduler.class);

    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackChannelMetadataRefresher metadataRefresher;
    private final SlackChannelHistorySyncService historySyncService;
    private final ConnectionService connectionService;
    private final SyncJobService syncJobService;
    private final SlackSyncProperties properties;
    private final AsyncTaskExecutor taskExecutor;

    public SlackDataSyncScheduler(
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackChannelMetadataRefresher metadataRefresher,
            SlackChannelHistorySyncService historySyncService,
            ConnectionService connectionService,
            SyncJobService syncJobService,
            SlackSyncProperties properties,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.metadataRefresher = metadataRefresher;
        this.historySyncService = historySyncService;
        this.connectionService = connectionService;
        this.syncJobService = syncJobService;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    @Scheduled(cron = "${hephaestus.sync.slack.cron}")
    @SchedulerLock(name = "slack-data-sync", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void syncDataCron() {
        List<Long> workspaceIds = monitoredChannelRepository.findDistinctWorkspaceIdsByConsentState("ACTIVE");
        if (workspaceIds.isEmpty()) {
            return;
        }
        log.info("slack.sync: starting nightly reconciliation for {} workspace(s)", workspaceIds.size());
        int failed = 0;
        for (Long workspaceId : workspaceIds) {
            try {
                syncWorkspaceRecordingJob(workspaceId);
            } catch (SyncJobConflictException e) {
                // Another sync (manual trigger, or a previous tick that hasn't finished) already holds the
                // one-active-job slot for this connection — skip this tick rather than treat it as a failure.
                log.info("slack.sync: workspace {} already has an active sync job, skipping tick", workspaceId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("slack.sync: workspace {} failed: {}", workspaceId, e.toString());
            }
        }
        log.info("slack.sync: nightly reconciliation done: workspaces={}, failed={}", workspaceIds.size(), failed);
    }

    /**
     * Wraps one workspace's nightly reconciliation in the shared {@link SyncJobService} template
     * so it shows up in job history — but only when the workspace has an ACTIVE Slack
     * {@link Connection} to record it against. A workspace whose channels are still {@code ACTIVE} in the
     * allow-list table but whose Connection already left ACTIVE (uninstall race — the normal window
     * {@link #syncWorkspaceNow}'s own guard covers) is skipped here without ever touching Slack,
     * unrecorded, since there is no Connection row to attach a {@code SyncJob} to.
     */
    private void syncWorkspaceRecordingJob(long workspaceId) {
        Optional<Connection> connection = connectionService.findActive(workspaceId, IntegrationKind.SLACK);
        if (connection.isEmpty()) {
            log.debug(
                    "slack.sync: workspaceId={} has no ACTIVE Slack connection — skipping tick (unrecorded)",
                    workspaceId);
            return;
        }
        syncJobService.run(
                new SyncJobRequest(
                        workspaceId,
                        connection.get().getId(),
                        IntegrationKind.SLACK,
                        SyncJobType.RECONCILIATION,
                        SyncJobTrigger.SCHEDULED,
                        null),
                handle -> {
                    SlackChannelHistorySyncService.WorkspaceSyncSummary summary = syncWorkspaceNow(workspaceId, handle);
                    // Same reporting as the manual runner — a scheduled reconcile and a hand-triggered one
                    // are the same work, so they must not tell the admin different stories about it.
                    handle.progress(
                            summary.synced() + summary.skipped(),
                            summary.channels(),
                            SlackIntegrationSyncRunner.progressDetail(summary));
                    if (summary.failed() > 0 || summary.budgetExhausted()) {
                        handle.reportWarnings();
                    }
                    if (handle.isCancellationRequested()) {
                        handle.reportCancelled();
                    }
                });
    }

    /**
     * Kicks the newly consented channel's first history sync once {@link SlackChannelConsentService}'s
     * transaction has committed — the Slack counterpart of Outline's add-resource kick, and the reason a
     * freshly consented channel no longer waits up to 24h for the {@code 0 0 4 * * *} tick to notice it.
     *
     * <p>After commit, and off the request thread, for the same two reasons Outline's is: the async pass must
     * be guaranteed to read the ACTIVE row it is meant to converge (called inline it would still see the
     * pre-transition state and no-op), and the admin's PATCH must not block on Slack round trips.
     *
     * <p>Unguarded against pile-up, like Outline's: {@link SlackChannelConsentService#transition} is
     * state-gated (a second PATCH to the same state is an idempotent no-op that publishes nothing), so there
     * is no double-click path that fires two kicks for one channel. A kick that fails is logged and dropped —
     * the transition itself already succeeded, and the nightly pass is the retry net.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChannelConsentActivated(SlackChannelConsentService.SlackChannelActivatedEvent event) {
        taskExecutor.execute(() -> {
            try {
                syncChannelNow(event.workspaceId(), event.slackChannelId());
            } catch (RuntimeException e) {
                log.warn(
                        "slack.sync: initial history sync kick failed for workspaceId={} channelId={}: {}",
                        event.workspaceId(),
                        event.slackChannelId(),
                        e.toString());
            }
        });
    }

    /**
     * Targeted single-channel history sync. Exists as a pass-through on this {@link WorkspaceAgnostic} class
     * so the executor thread — which carries no request tenancy scope — crosses the bypass hop, the same
     * routing {@code OutlineDocumentSyncScheduler#syncCollectionNow} exists for.
     *
     * <p>Skips silently when the workspace has no ACTIVE Slack connection, for the reason
     * {@link #syncWorkspaceNow} documents: every call would fail on token resolution and spend the pacing
     * budget doing it.
     */
    public SlackChannelHistorySyncService.WorkspaceSyncSummary syncChannelNow(long workspaceId, String channelId) {
        if (connectionService.findActive(workspaceId, IntegrationKind.SLACK).isEmpty()) {
            log.debug(
                    "slack.sync: workspaceId={} has no ACTIVE Slack connection — skipping initial channel sync",
                    workspaceId);
            return SlackChannelHistorySyncService.WorkspaceSyncSummary.notConnected();
        }
        SlackChannelHistorySyncService.WorkspaceSyncSummary summary =
                historySyncService.syncChannel(workspaceId, channelId);
        log.info(
                "slack.sync: initial channel sync workspaceId={} channelId={} synced={} ingested={} requests={}",
                workspaceId,
                channelId,
                summary.synced(),
                summary.ingested(),
                summary.requestsUsed());
        return summary;
    }

    /**
     * Run one workspace's reconciliation immediately (tests and operational replays).
     *
     * <p>Returns an empty summary without touching Slack when the workspace has no ACTIVE Slack connection: every
     * call would fail on token resolution anyway, and attempting them would spend the rate-limit budget (and its
     * pacing sleep) plus log one warning per channel for a workspace that simply is not connected. Monitored
     * channels normally disappear with the connection on uninstall; this covers the window in between.
     */
    public SlackChannelHistorySyncService.WorkspaceSyncSummary syncWorkspaceNow(long workspaceId) {
        return syncWorkspaceNow(workspaceId, null);
    }

    public SlackChannelHistorySyncService.WorkspaceSyncSummary syncWorkspaceNow(
            long workspaceId, @Nullable SyncExecutionHandle handle) {
        if (connectionService.findActive(workspaceId, IntegrationKind.SLACK).isEmpty()) {
            log.debug(
                    "slack.sync: workspaceId={} has no ACTIVE Slack connection — skipping reconciliation", workspaceId);
            return SlackChannelHistorySyncService.WorkspaceSyncSummary.notConnected();
        }
        // One BooleanSupplier for both halves of the pass: the metadata refresh runs first and is itself
        // one Slack round trip per channel, so a handle that only reached the history sync left Cancel
        // looking inert for the whole first half.
        BooleanSupplier cancelled = handle == null ? () -> false : handle::isCancellationRequested;
        if (properties.metadataEnabled()) {
            metadataRefresher.refreshWorkspace(workspaceId, cancelled);
        }
        SlackChannelHistorySyncService.WorkspaceSyncSummary summary =
                historySyncService.syncWorkspace(workspaceId, cancelled);
        log.info(
                "slack.sync: workspaceId={} channels={} synced={} skipped={} failed={} ingested={} requests={} budgetExhausted={}",
                workspaceId,
                summary.channels(),
                summary.synced(),
                summary.skipped(),
                summary.failed(),
                summary.ingested(),
                summary.requestsUsed(),
                summary.budgetExhausted());
        return summary;
    }
}
