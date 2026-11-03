package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drives the connect-time initial sync for GitHub <b>PAT</b> connections off the committed
 * {@code Activated} boundary: PAT connections have no installation webhook to trigger an initial sync,
 * unlike App installs. Keying on the AFTER_COMMIT ACTIVE transition guarantees the sync runs against a
 * committed, ACTIVE Connection and is therefore recorded as an {@code INITIAL}/{@code LIFECYCLE}
 * {@code SyncJob} through {@code GitHubWorkspaceDataSyncTrigger}.
 *
 * <p><b>App installations are deliberately excluded.</b> GitHub App installs already drive their initial
 * sync from the installation webhook ({@code GitHubWorkspaceProvisioningAdapter.triggerInitialSync}),
 * which is ordered to run <em>after</em> the repository monitors are materialised from the installation
 * metadata. The {@code Activated} event, by contrast, fires at connection-upsert commit — before those
 * monitors exist — so syncing an App connection here would race an empty monitor set and would also
 * double-fire against the webhook-driven trigger. Restricting this listener to connections whose active
 * config is a {@code GitHubPatConfig} keeps exactly one initial sync per activation for each path.
 *
 * <p>Best-effort: a failure here can only cost freshness (the periodic reconcile is the safety net) and is
 * never rethrown off the async thread.
 */
@Component
@ConditionalOnServerRole
public class GitHubConnectionStateListener {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnectionStateListener.class);

    private final ConnectionService connectionService;
    private final GitHubWorkspaceDataSyncTrigger dataSyncTrigger;
    private final AsyncTaskExecutor monitoringExecutor;

    public GitHubConnectionStateListener(
            ConnectionService connectionService,
            GitHubWorkspaceDataSyncTrigger dataSyncTrigger,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.connectionService = connectionService;
        this.dataSyncTrigger = dataSyncTrigger;
        this.monitoringExecutor = monitoringExecutor;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(ConnectionLifecycleEvent.Activated event) {
        if (event.kind() != IntegrationKind.GITHUB) {
            return;
        }
        long workspaceId = event.workspaceId();
        // App connections own their own (monitor-ordered) initial-sync trigger — only PAT connections
        // reach this path.
        if (connectionService.findActiveGitHubPatConfig(workspaceId).isEmpty()) {
            return;
        }
        monitoringExecutor.execute(() -> {
            try {
                dataSyncTrigger.syncAllRepositories(workspaceId);
            } catch (Exception e) {
                log.error("github.lifecycle: connect-time PAT sync failed for workspaceId={}", workspaceId, e);
            }
        });
    }
}
