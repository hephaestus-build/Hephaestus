package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.outline.sync.OutlineDocumentSyncScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to a Connection crossing the ACTIVE boundary (published by {@code ConnectionService.transition}):
 * activation registers the change-notification subscription and kicks an initial recency sync the moment
 * an admin connects — instead of waiting for the next scheduler tick — and deactivation tears the
 * upstream subscription down.
 *
 * <p>Runs async AFTER_COMMIT (model: {@code CommitEnrichmentEventListener}), so the connect transaction
 * never waits on Outline and a failure here can only cost freshness — every path is wrapped and logged,
 * never rethrown. Sync work is routed through {@link OutlineDocumentSyncScheduler} because the async
 * event thread carries no tenancy-bypass scope of its own. The initial sync is recorded as an
 * {@code INITIAL}/{@code LIFECYCLE} job through {@link SyncJobService} — the same job-tracking template
 * every other trigger path uses.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineConnectionStateListener {

    private static final Logger log = LoggerFactory.getLogger(OutlineConnectionStateListener.class);

    private final OutlineWebhookRegistrar webhookRegistrar;
    private final OutlineDocumentSyncScheduler syncScheduler;
    private final SyncJobService syncJobService;

    public OutlineConnectionStateListener(
            OutlineWebhookRegistrar webhookRegistrar,
            OutlineDocumentSyncScheduler syncScheduler,
            SyncJobService syncJobService) {
        this.webhookRegistrar = webhookRegistrar;
        this.syncScheduler = syncScheduler;
        this.syncJobService = syncJobService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(ConnectionLifecycleEvent.Activated event) {
        if (event.kind() != IntegrationKind.OUTLINE) {
            return;
        }
        try {
            webhookRegistrar.ensureSubscription(event.workspaceId());
            // Initial recency sync — a harmless no-op while no collections are registered yet.
            runInitialSyncJob(event);
        } catch (RuntimeException e) {
            // Async listener: rethrowing reaches nobody. The periodic reconcile is the safety net.
            log.warn(
                    "outline.lifecycle: connect-time setup failed for workspaceId={}: {}",
                    event.workspaceId(),
                    e.toString());
        }
    }

    private void runInitialSyncJob(ConnectionLifecycleEvent.Activated event) {
        try {
            syncJobService.run(
                    new SyncJobRequest(
                            event.workspaceId(),
                            event.connectionId(),
                            IntegrationKind.OUTLINE,
                            SyncJobType.INITIAL,
                            SyncJobTrigger.LIFECYCLE,
                            null),
                    handle -> {
                        // INITIAL, threaded through: a mirror being populated for the first time must not infer
                        // upstream deletions from its own half-filled state (parity with the SCM initial syncs).
                        syncScheduler.syncWorkspaceNow(event.workspaceId(), handle, SyncJobType.INITIAL);
                        // Cancelling the initial connect-time sync must record CANCELLED, not SUCCEEDED — mirror the
                        // reportCancelled tail every other Outline entry point (catchUp/syncAllNow/runner) already has.
                        if (handle.isCancellationRequested()) {
                            handle.reportCancelled();
                        }
                    });
        } catch (SyncJobConflictException e) {
            log.debug(
                    "outline.lifecycle: initial sync skipped for workspaceId={} — job {} already active",
                    event.workspaceId(),
                    e.activeJob().getId());
        }
    }
}
