package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to a GitLab {@link de.tum.cit.aet.hephaestus.integration.core.connection.Connection} crossing
 * the ACTIVE boundary (published by {@code ConnectionService.transition}) — the direct SCM analogue of
 * {@code OutlineConnectionStateListener}.
 *
 * <p><b>Activation</b> drives the connect-time initial sync off the {@code Activated} AFTER_COMMIT event
 * rather than off {@code WorkspaceCreatedEvent}. Keying on the committed ACTIVE transition guarantees the
 * Connection is resolvable and ACTIVE by the time the sync body runs, so the sync is always recorded as an
 * {@code INITIAL}/{@code LIFECYCLE} {@code SyncJob} (via {@code GitLabWorkspaceDataSyncTrigger} inside
 * {@link GitLabWorkspaceInitializationService#initializeAsync}) instead of racing the activation and
 * falling to an unrecorded body. {@code initializeAsync} confirms the NATS scope consumer for the discovered
 * repositories before it registers the webhook, and runs the full sync after both.
 *
 * <p><b>Deactivation</b> tears the group webhook down (best-effort). An admin disconnect deletes it
 * earlier, in {@code GitlabConnectionStrategy#revokeProvider}, while the connection still holds its PAT;
 * this AFTER_COMMIT hook is the symmetric guard mirroring
 * {@code OutlineConnectionStateListener.onDeactivated} and covers a torn-down row by connection id.
 *
 * <p>Every path is wrapped and logged, never rethrown — a failure here can only cost freshness, and the
 * periodic reconcile is the safety net. Work is dispatched to the monitoring executor so the transition
 * thread never waits on GitLab.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabConnectionStateListener {

    private static final Logger log = LoggerFactory.getLogger(GitLabConnectionStateListener.class);

    private final GitLabWorkspaceInitializationService initService;
    private final GitLabWebhookService webhookService;
    private final AsyncTaskExecutor monitoringExecutor;

    public GitLabConnectionStateListener(
            GitLabWorkspaceInitializationService initService,
            GitLabWebhookService webhookService,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.initService = initService;
        this.webhookService = webhookService;
        this.monitoringExecutor = monitoringExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(ConnectionLifecycleEvent.Activated event) {
        if (event.kind() != IntegrationKind.GITLAB) {
            return;
        }
        // initializeAsync already dispatches to the monitoring executor and wraps every phase — call it
        // directly so the commit thread returns immediately. It records the INITIAL/LIFECYCLE SyncJob and
        // starts the NATS consumer.
        try {
            initService.initializeAsync(event.workspaceId());
        } catch (RuntimeException e) {
            log.warn(
                    "gitlab.lifecycle: connect-time initialization dispatch failed for workspaceId={}: {}",
                    event.workspaceId(),
                    e.toString());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeactivated(ConnectionLifecycleEvent.Deactivated event) {
        if (event.kind() != IntegrationKind.GITLAB) {
            return;
        }
        long workspaceId = event.workspaceId();
        long connectionId = event.connectionId();
        // Off the transition thread: the deregister performs a blocking GitLab HTTP call.
        monitoringExecutor.execute(() -> {
            try {
                webhookService.deregisterWebhookForConnection(workspaceId, connectionId);
            } catch (RuntimeException e) {
                log.warn(
                        "gitlab.lifecycle: deactivation webhook teardown failed for connectionId={}: {}",
                        connectionId,
                        e.toString());
            }
        });
    }
}
