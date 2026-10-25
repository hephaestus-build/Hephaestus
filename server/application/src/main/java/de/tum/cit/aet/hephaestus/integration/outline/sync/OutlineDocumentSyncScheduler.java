package de.tum.cit.aet.hephaestus.integration.outline.sync;

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
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import java.util.List;
import java.util.function.Consumer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduling fan-out for the Outline sync: a six-hour full reconcile over every workspace with an ACTIVE
 * connection, and a fast catch-up tick over only those with a collection still awaiting a clean pass (normally
 * none, so zero API calls). Each workspace is delegated to {@link OutlineDocumentSyncService} across a real proxy
 * hop, so one workspace's failure is isolated. This bean owns scheduling and cross-pod locking only.
 *
 * <p><b>Tenancy.</b> {@link WorkspaceAgnostic} sits on the fan-out methods alone: they enumerate the fleet before
 * any tenant is bound, so their SQL cannot carry a {@code workspace_id} predicate. Putting it on the type would
 * also blanket the single-workspace {@code *Now} pass-throughs, disabling {@code WorkspaceStatementInspector} on
 * exactly the paths that take vendor-supplied ids off a webhook — those stay scoped and keep the safety net.
 */
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class OutlineDocumentSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutlineDocumentSyncScheduler.class);

    private final ConnectionService connectionService;
    private final OutlineDocumentSyncService syncService;
    private final OutlineCollectionRepository collectionRepository;
    private final SyncJobService syncJobService;

    public OutlineDocumentSyncScheduler(
            ConnectionService connectionService,
            OutlineDocumentSyncService syncService,
            OutlineCollectionRepository collectionRepository,
            SyncJobService syncJobService) {
        this.connectionService = connectionService;
        this.syncService = syncService;
        this.collectionRepository = collectionRepository;
        this.syncJobService = syncJobService;
    }

    @Scheduled(cron = "${hephaestus.integration.outline.sync.cron}")
    @SchedulerLock(name = "outline-document-sync", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    @WorkspaceAgnostic("Cron fan-out: enumerates every workspace with an ACTIVE Outline connection")
    public void syncAll() {
        syncAllNow();
    }

    /**
     * Catch-up tick: resume every collection still awaiting a clean pass (freshly registered, or a
     * reconcile ran out of export budget). Workspaces without pending collections cost nothing — the
     * fan-out enumeration itself is the "has pending work" check, so a workspace only ever appears here
     * when a {@code RECONCILIATION}/{@code SYSTEM} job is warranted; nothing is recorded otherwise.
     */
    @Scheduled(fixedDelayString = "${hephaestus.integration.outline.sync.catch-up-delay}")
    @SchedulerLock(name = "outline-sync-catch-up", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10S")
    @WorkspaceAgnostic("Catch-up fan-out: enumerates the workspaces with a collection still awaiting a clean pass")
    public void catchUp() {
        for (Long workspaceId : collectionRepository.findDistinctWorkspaceIdsWithPendingSync()) {
            try {
                runReconcileJob(workspaceId, SyncJobTrigger.SYSTEM, handle -> {
                    syncService.syncPendingCollections(workspaceId, handle);
                    if (handle != null && handle.isCancellationRequested()) {
                        handle.reportCancelled();
                    }
                });
            } catch (RuntimeException e) {
                // Isolate a poisoned workspace — log and keep catching up the rest.
                log.warn("outline.sync: catch-up failed for workspaceId={}: {}", workspaceId, e.toString());
            }
        }
    }

    /**
     * Run the full reconcile immediately across every workspace with an ACTIVE Outline Connection.
     * Exposed (not only cron-driven) so callers and integration tests can drive it deterministically.
     *
     * @return the number of workspaces the reconcile was attempted for
     */
    @WorkspaceAgnostic("Fleet fan-out: enumerates every workspace with an ACTIVE Outline connection")
    public int syncAllNow() {
        List<Long> workspaceIds = connectionService.findWorkspaceIdsWithActiveConnection(IntegrationKind.OUTLINE);
        for (Long workspaceId : workspaceIds) {
            try {
                runReconcileJob(workspaceId, SyncJobTrigger.SCHEDULED, handle -> {
                    syncService.syncWorkspace(workspaceId, handle, SyncJobType.RECONCILIATION);
                    if (handle != null && handle.isCancellationRequested()) {
                        handle.reportCancelled();
                    }
                });
            } catch (RuntimeException e) {
                // Isolate a poisoned workspace — log and keep reconciling the rest.
                log.warn("outline.sync: reconcile failed for workspaceId={}: {}", workspaceId, e.toString());
            }
        }
        if (!workspaceIds.isEmpty()) {
            log.info("outline.sync: reconciled {} workspace(s)", workspaceIds.size());
        }
        return workspaceIds.size();
    }

    /**
     * Runs {@code body} for one workspace through the shared {@link SyncJobService} template, recording a
     * {@code RECONCILIATION} job whose one-active-job-per-connection guard is this pass's dedup. A workspace
     * whose ACTIVE connection vanished between enumeration and this call (deactivated concurrently) is
     * silently skipped: nothing to record a job against. A connection that already has an active job is also
     * skipped — the running job IS the record of this pass.
     */
    private void runReconcileJob(long workspaceId, SyncJobTrigger trigger, Consumer<SyncExecutionHandle> body) {
        connectionService
                .findActive(workspaceId, IntegrationKind.OUTLINE)
                .ifPresent(connection -> runJob(workspaceId, connection, trigger, body));
    }

    private void runJob(
            long workspaceId, Connection connection, SyncJobTrigger trigger, Consumer<SyncExecutionHandle> body) {
        try {
            syncJobService.run(
                    new SyncJobRequest(
                            workspaceId,
                            connection.getId(),
                            IntegrationKind.OUTLINE,
                            SyncJobType.RECONCILIATION,
                            trigger,
                            null),
                    body);
        } catch (SyncJobConflictException e) {
            log.debug(
                    "outline.sync: reconcile skipped for workspaceId={} — job {} already active",
                    workspaceId,
                    e.activeJob().getId());
        }
    }

    /**
     * Full reconcile of a single workspace immediately — the entry point for webhook consumers and async
     * listeners, which reach the sync service through this bean. Explicitly workspace-SCOPED: it carries no
     * tenancy bypass, so {@code WorkspaceStatementInspector} stays armed for the whole call (see the class
     * javadoc — these are the paths fed by vendor-supplied ids).
     */
    public void syncWorkspaceNow(long workspaceId) {
        syncService.syncWorkspace(workspaceId);
    }

    /**
     * Same pass as {@link #syncWorkspaceNow(long)}, threading the job's {@link SyncExecutionHandle} so it
     * sees per-collection progress and can cancel cooperatively — the shape the manual-trigger
     * {@code OutlineIntegrationSyncRunner} uses. Workspace-scoped, no bypass — see
     * {@link #syncWorkspaceNow(long)}.
     *
     * <p>{@code type} is forwarded rather than dropped, exactly as the SCM schedulers do: it decides whether
     * a clean enumeration ends by tombstoning that collection's vanished documents. An {@code INITIAL} pass
     * (connect-time) still populates the mirror in full but infers no deletions.
     */
    public void syncWorkspaceNow(long workspaceId, @Nullable SyncExecutionHandle handle, SyncJobType type) {
        syncService.syncWorkspace(workspaceId, handle, type);
    }

    /** Targeted single-collection sync; workspace-scoped, no bypass — see {@link #syncWorkspaceNow}. */
    public void syncCollectionNow(long workspaceId, String collectionId) {
        syncService.syncCollection(workspaceId, collectionId);
    }

    /** Webhook targeted document refresh; workspace-scoped, no bypass — see {@link #syncWorkspaceNow}. */
    public void refreshDocumentNow(long workspaceId, String eventName, String documentId) {
        syncService.refreshDocument(workspaceId, eventName, documentId);
    }

    /**
     * Webhook targeted document refresh carrying the delivery's pre-parsed {@code payload.model} (already
     * authenticated by the envelope's HMAC), letting the sync service skip its own {@code documents.info}
     * round-trip when the model is usable. Workspace-scoped, no bypass — see {@link #syncWorkspaceNow}.
     */
    public void refreshDocumentNow(
            long workspaceId, String eventName, String documentId, @Nullable OutlineDocumentModel prefetchedMeta) {
        syncService.refreshDocument(workspaceId, eventName, documentId, prefetchedMeta);
    }

    /** Webhook collection-event catalog refresh; workspace-scoped, no bypass — see {@link #syncWorkspaceNow}. */
    public void refreshCollectionCatalogNow(long workspaceId, String eventName, @Nullable String collectionId) {
        syncService.refreshCollectionCatalog(workspaceId, eventName, collectionId);
    }
}
