package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
 * <p>Deactivation is durable. Suspended connections lose their upstream group hook under the lifecycle lock;
 * disconnected connections use {@code ProviderTeardown}. A periodic reconcile repairs missing deactivation events.
 *
 * <p>Activation dispatches initialization to the monitoring executor. Deactivation runs through the event
 * publication registry, so a failed removal remains pending and is retried.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabConnectionStateListener {

    private static final Logger log = LoggerFactory.getLogger(GitLabConnectionStateListener.class);

    private final GitLabWorkspaceInitializationService initService;
    private final GitLabSuspendedWebhookCleanup cleanup;

    public GitLabConnectionStateListener(
            GitLabWorkspaceInitializationService initService, GitLabSuspendedWebhookCleanup cleanup) {
        this.initService = initService;
        this.cleanup = cleanup;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onDeactivated(ConnectionLifecycleEvent.Deactivated event) {
        if (event.kind() != IntegrationKind.GITLAB) {
            return;
        }
        cleanup.remove(event.workspaceId(), event.connectionId());
    }
}
