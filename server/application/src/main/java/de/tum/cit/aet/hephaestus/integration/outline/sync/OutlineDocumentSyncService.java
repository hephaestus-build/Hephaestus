package de.tum.cit.aet.hephaestus.integration.outline.sync;

import de.tum.cit.aet.hephaestus.agent.documentation.DocumentReviewTrigger;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.outline.OutlineProperties;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineContentClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineRateLimitedException;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineCollectionModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.SyncStatus;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot;
import de.tum.cit.aet.hephaestus.integration.outline.domain.signal.OutlineDocumentSignalRecorder;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineWebhookRegistrar;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Reconciles a workspace's ENABLED {@code outline_collection} rows into the {@code outline_document} mirror.
 * Four entry paths share one upsert core: {@link #syncWorkspace} (authoritative full reconcile),
 * {@link #syncPendingCollections} (catch-up tick), {@link #syncCollection} (targeted), and
 * {@link #refreshDocument} / {@link #refreshCollectionCatalog} (webhook).
 *
 * <p><b>Nothing here is transactional.</b> Enumeration and export are blocking HTTP; holding a transaction
 * across them would pin a pooled connection and leave a Postgres backend {@code idle in transaction} for as
 * long as Outline is slow. Each write is its own short {@code REQUIRES_NEW} transaction via
 * {@link OutlineMirrorWriter}, so progress commits incrementally and a pass that dies mid-way keeps what it
 * already wrote.
 *
 * <p>The diff runs on {@link OutlineDocumentSnapshot}s, never entities — bodies are unbounded {@code text}
 * and the diff needs only timestamps, hashes and body presence. Only rows actually written are loaded whole.
 *
 * <p>This class only ever enumerates and upserts. Everything that <em>removes</em> mirrored content —
 * tombstoning, the deletion-by-absence sweep, and the body-size eviction — lives in
 * {@link OutlineMirrorRetentionService}; this class decides <em>whether</em> those may run, and the rules
 * below are exactly that decision.
 *
 * <p><b>Invariants.</b> Tombstone-by-absence needs BOTH guards to hold. It runs only on a clean pass (full
 * enumeration, nothing skipped for budget): losing visibility of a collection, a truncated enumeration
 * ({@link OutlineApiClient} throws rather than returning a short list), and an HTTP 429 all skip the sweep
 * instead of deleting documents. And it runs only on a {@link SyncJobType#RECONCILIATION} pass — a mirror
 * still being populated by an {@code INITIAL} job has nothing stale in it, and every document not fetched
 * yet would read as an upstream deletion to a set difference. That is the same rule the SCM deletion sweeps
 * obey. Archived documents are never tombstoned — see {@link #syncArchivedDocuments}.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineDocumentSyncService {

    private static final Logger log = LoggerFactory.getLogger(OutlineDocumentSyncService.class);

    /** Events that mean "the mirrored body must go away" — no API round-trip needed. */
    private static final Set<String> TOMBSTONE_EVENTS = Set.of("documents.delete", "documents.permanent_delete");

    /** Archive is soft: it stamps {@code archivedAt} and never wipes body/hash/authors. */
    private static final String ARCHIVE_EVENT = "documents.archive";

    private static final int MAX_ERROR_LENGTH = 2048;

    /** Matches {@code outline_collection.description}'s column width. */
    private static final int MAX_DESCRIPTION_LENGTH = 2048;

    private final ConnectionService connectionService;
    private final OutlineContentClient outlineApiClient;
    private final OutlineDocumentRepository documentRepository;
    private final OutlineCollectionRepository collectionRepository;
    private final OutlineWebhookRegistrar webhookRegistrar;
    private final OutlineProperties properties;
    private final OutlineMirrorWriter mirrorWriter;
    private final OutlineMirrorRetentionService retention;
    private final OutlineDocumentSignalRecorder signalRecorder;

    /** Optional because the agent subsystem is absent in the worker and webhook roles. */
    private final ObjectProvider<DocumentReviewTrigger> reviewTrigger;

    public OutlineDocumentSyncService(
            ConnectionService connectionService,
            OutlineContentClient outlineApiClient,
            OutlineDocumentRepository documentRepository,
            OutlineCollectionRepository collectionRepository,
            OutlineWebhookRegistrar webhookRegistrar,
            OutlineProperties properties,
            OutlineMirrorWriter mirrorWriter,
            OutlineMirrorRetentionService retention,
            OutlineDocumentSignalRecorder signalRecorder,
            ObjectProvider<DocumentReviewTrigger> reviewTrigger) {
        this.connectionService = connectionService;
        this.outlineApiClient = outlineApiClient;
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.webhookRegistrar = webhookRegistrar;
        this.properties = properties;
        this.mirrorWriter = mirrorWriter;
        this.retention = retention;
        this.signalRecorder = signalRecorder;
        this.reviewTrigger = reviewTrigger;
    }

    /**
     * Must run outside any transaction of ours: submission opens one of its own and locks the workspace
     * inside it. A failed offer never fails the sync — the ledger row already written is what a later
     * pass re-offers.
     */
    private void recordAndOffer(
            long workspaceId,
            @Nullable OutlineDocumentSnapshot document,
            String eventName,
            Instant occurredAt,
            DiscoveredVia discoveredVia) {
        Optional<SignalKey> recorded =
                signalRecorder.record(workspaceId, document, eventName, occurredAt, discoveredVia);
        if (recorded.isEmpty()) {
            return;
        }
        DocumentReviewTrigger trigger = reviewTrigger.getIfAvailable();
        if (trigger == null) {
            log.debug("No document review trigger on this node; signal recorded only: workspaceId={}", workspaceId);
            return;
        }
        try {
            trigger.onDocumentSignal(recorded.get(), discoveredVia);
        } catch (RuntimeException e) {
            log.warn(
                    "Offering a document signal for review failed (signal recorded): workspaceId={}, signal={}",
                    workspaceId,
                    recorded.get().signalName(),
                    e);
        }
    }

    /**
     * Full reconcile of the workspace's mirror against upstream Outline. A no-op when the workspace has
     * no ACTIVE Outline Connection, no server URL, no resolvable token — or no registered collections.
     */
    public void syncWorkspace(long workspaceId) {
        syncWorkspace(workspaceId, null, SyncJobType.RECONCILIATION);
    }

    /**
     * Same full reconcile as {@link #syncWorkspace(long)}, additionally reporting per-collection progress
     * and checking cooperative cancellation between collections through {@code handle} — the port the
     * unified sync-job runner threads its job handle through. {@code null} behaves exactly like
     * {@link #syncWorkspace(long)}.
     *
     * <p>{@code type} is forwarded rather than dropped: it is what decides whether a clean enumeration ends
     * by tombstoning that collection's vanished documents, and it is the only difference between an
     * {@code INITIAL} and a {@code RECONCILIATION} run here — every other step only ever upserts.
     */
    public void syncWorkspace(long workspaceId, @Nullable SyncExecutionHandle handle, SyncJobType type) {
        SyncContext ctx = resolveContext(workspaceId).orElse(null);
        if (ctx == null) {
            reportWarning(handle);
            return;
        }
        Instant now = Instant.now();
        BooleanSupplier cancelled = cancellationOf(handle);
        try {
            Map<String, OutlineCollectionModel> live = refreshCatalog(ctx);
            List<OutlineCollection> collections =
                    collectionRepository.findForSync(workspaceId, ctx.connectionId(), MirrorState.ENABLED);
            ExportBudget budget = new ExportBudget(properties.sync().exportBudget());
            Map<String, OutlineDocumentSnapshot> existing = loadExisting(ctx);
            int synced = 0;
            int total = collections.size();
            int done = 0;
            for (OutlineCollection collection : collections) {
                if (handle != null && handle.isCancellationRequested()) {
                    break;
                }
                if (!live.containsKey(collection.getCollectionId())) {
                    // Visibility loss ≠ deletion: never tombstone documents we merely cannot see.
                    recordCollectionError(ctx, collection, "Collection is no longer visible to the integration token");
                    reportWarning(handle);
                    done++;
                    reportCollectionDone(handle, done, total, collection.getName());
                    continue;
                }
                if (syncOneCollectionRecordingError(ctx, collection, existing, budget, now, type, cancelled)) {
                    synced++;
                } else {
                    reportWarning(handle);
                }
                done++;
                reportCollectionDone(handle, done, total, collection.getName());
            }
            // Self-heal the change-notification subscription each reconcile (Outline auto-disables a
            // subscription after repeated delivery failures); best-effort, never throws.
            webhookRegistrar.ensureSubscription(workspaceId);
            retention.enforceSizeCap(workspaceId);
            long stale = mirrorWriter.dropStaleTombstones(workspaceId, now.minus(properties.staleness()));
            log.info(
                    "outline.sync: workspaceId={} collections={} synced={} budgetLeft={} staleDropped={}",
                    workspaceId,
                    collections.size(),
                    synced,
                    budget.remaining(),
                    stale);
        } catch (OutlineRateLimitedException e) {
            logRateLimited(workspaceId, e);
            reportWarning(handle);
        }
    }

    /**
     * Syncs only ENABLED collections still {@code PENDING} — the resume path after a budget-exhausted pass.
     * A fully caught-up workspace makes zero API calls.
     */
    public void syncPendingCollections(long workspaceId) {
        syncPendingCollections(workspaceId, null);
    }

    /**
     * Same resume pass as {@link #syncPendingCollections(long)}, additionally reporting per-collection
     * progress and checking cooperative cancellation between collections through {@code handle} — the
     * port the catch-up sync-job runner threads its job handle through. {@code null} behaves exactly
     * like {@link #syncPendingCollections(long)}.
     */
    public void syncPendingCollections(long workspaceId, @Nullable SyncExecutionHandle handle) {
        SyncContext ctx = resolveContext(workspaceId).orElse(null);
        if (ctx == null) {
            reportWarning(handle);
            return;
        }
        List<OutlineCollection> pending =
                collectionRepository
                        .findByWorkspaceIdAndStateAndSyncStatus(workspaceId, MirrorState.ENABLED, SyncStatus.PENDING)
                        .stream()
                        .filter(c -> c.getConnectionId() == ctx.connectionId())
                        .toList();
        if (pending.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        BooleanSupplier cancelled = cancellationOf(handle);
        ExportBudget budget = new ExportBudget(properties.sync().exportBudget());
        Map<String, OutlineDocumentSnapshot> existing = loadExisting(ctx);
        int total = pending.size();
        int done = 0;
        try {
            for (OutlineCollection collection : pending) {
                if (handle != null && handle.isCancellationRequested()) {
                    break;
                }
                // The catch-up tick resumes a collection left PENDING by a budget-exhausted reconcile; it is
                // itself a RECONCILIATION job, so a clean pass here may sweep.
                if (!syncOneCollectionRecordingError(
                        ctx, collection, existing, budget, now, SyncJobType.RECONCILIATION, cancelled)) {
                    reportWarning(handle);
                }
                done++;
                reportCollectionDone(handle, done, total, collection.getName());
            }
        } catch (OutlineRateLimitedException e) {
            logRateLimited(workspaceId, e);
            reportWarning(handle);
        }
        // The catch-up tick exports bodies too, so it must also enforce the cap — including after a
        // rate-limited abort, since bodies exported before the pause still count against it.
        retention.enforceSizeCap(workspaceId);
    }

    /**
     * The cooperative-cancellation port for the document-level loops, derived from the enclosing job's
     * handle. The unhandled (scheduled / webhook / targeted-kick) paths get a supplier that never trips.
     */
    private static BooleanSupplier cancellationOf(@Nullable SyncExecutionHandle handle) {
        return handle == null ? () -> false : handle::isCancellationRequested;
    }

    /** Marks the enclosing job partially successful; no-op on the unhandled (scheduled/webhook) paths. */
    private static void reportWarning(@Nullable SyncExecutionHandle handle) {
        if (handle != null) {
            handle.reportWarnings();
        }
    }

    /** Reports one collection's finished sync attempt (success or recorded error) to the enclosing job. */
    private static void reportCollectionDone(
            @Nullable SyncExecutionHandle handle, int done, int total, @Nullable String collectionName) {
        if (handle != null) {
            handle.progress(
                    done,
                    total,
                    // Just the collection — "N of M" is already the progress bar's own reading
                    // (unitsCompleted/unitsTotal travel on the same record).
                    SyncProgress.ofResource(
                            SyncPhase.COLLECTIONS,
                            collectionName == null ? "Syncing collection" : "Syncing " + collectionName,
                            collectionName,
                            done,
                            total));
        }
    }

    /** Targeted sync of one registered collection (the admin-registration kick). No-op unless ENABLED. */
    public void syncCollection(long workspaceId, String collectionId) {
        SyncContext ctx = resolveContext(workspaceId).orElse(null);
        if (ctx == null) {
            return;
        }
        Optional<OutlineCollection> collection = collectionRepository.findByWorkspaceIdAndConnectionIdAndCollectionId(
                workspaceId, ctx.connectionId(), collectionId);
        if (collection.isEmpty() || !collection.get().isEnabled()) {
            return;
        }
        try {
            syncOneCollectionRecordingError(
                    ctx,
                    collection.get(),
                    loadExisting(ctx),
                    new ExportBudget(properties.sync().exportBudget()),
                    Instant.now(),
                    // A targeted single-collection pass is a full enumeration of that collection — reconciling
                    // it, not populating a fresh mirror.
                    SyncJobType.RECONCILIATION,
                    // No job owns the targeted kick (fire-and-forget off the admin request thread), so there is
                    // nothing to cancel it from.
                    cancellationOf(null));
        } catch (OutlineRateLimitedException e) {
            logRateLimited(workspaceId, e);
        }
    }

    /**
     * Webhook targeted refresh of one document (≤2 API calls) — no pre-fetched metadata available.
     * Equivalent to {@link #refreshDocument(long, String, String, OutlineDocumentModel)}
     * with a {@code null} {@code prefetchedMeta}.
     */
    public void refreshDocument(long workspaceId, String eventName, String documentId) {
        refreshDocument(workspaceId, eventName, documentId, null);
    }

    /**
     * Webhook targeted refresh of one document. Delete-shaped events tombstone and {@link #ARCHIVE_EVENT}
     * stamps {@code archivedAt}, both without an API call; anything else upserts if the document's
     * collection is mirrored, and a vanished document tombstones.
     *
     * <p>{@code prefetchedMeta} is the payload's own {@code model}, authenticated by the envelope's HMAC, so
     * a usable one is trusted and the {@code documents.info} round-trip is skipped; the body still exports,
     * since the payload never carries content.
     */
    public void refreshDocument(
            long workspaceId, String eventName, String documentId, @Nullable OutlineDocumentModel prefetchedMeta) {
        SyncContext ctx = resolveContext(workspaceId).orElse(null);
        if (ctx == null || documentId == null || documentId.isBlank()) {
            return;
        }
        Optional<OutlineDocumentSnapshot> mirrored =
                documentRepository.findSnapshotByDocumentId(workspaceId, ctx.connectionId(), documentId);
        if (TOMBSTONE_EVENTS.contains(eventName)) {
            mirrored.filter(d -> !d.isDeleted())
                    .ifPresent(d -> retention.tombstone(
                            ctx.workspaceId(), ctx.connectionId(), documentId, Instant.now(), null));
            return;
        }
        if (ARCHIVE_EVENT.equals(eventName)) {
            Instant archivedAt = Instant.now();
            mirrored.filter(d -> !d.isDeleted()).ifPresent(d -> {
                mirrorWriter.updateDocument(
                        workspaceId, ctx.connectionId(), documentId, doc -> doc.setArchivedAt(archivedAt));
                recordAndOffer(workspaceId, d, eventName, archivedAt, DiscoveredVia.EVENT);
            });
            return;
        }
        try {
            OutlineDocumentModel meta;
            if (prefetchedMeta != null && prefetchedMeta.getId() != null && prefetchedMeta.getCollectionId() != null) {
                meta = prefetchedMeta;
            } else {
                Optional<OutlineDocumentModel> info =
                        outlineApiClient.getDocumentInfo(ctx.serverUrl(), ctx.token(), documentId);
                if (info.isEmpty()) {
                    mirrored.filter(d -> !d.isDeleted())
                            .ifPresent(d -> retention.tombstone(
                                    ctx.workspaceId(), ctx.connectionId(), documentId, Instant.now(), null));
                    return;
                }
                meta = info.get();
            }
            if (meta.getCollectionId() == null) {
                return;
            }
            Optional<OutlineCollection> collection =
                    collectionRepository.findByWorkspaceIdAndConnectionIdAndCollectionId(
                            workspaceId, ctx.connectionId(), meta.getCollectionId());
            if (collection.isEmpty() || !collection.get().isEnabled()) {
                return; // the document lives outside the mirrored collections — not ours to ingest
            }
            Map<String, OutlineDocumentSnapshot> existing = new HashMap<>();
            mirrored.ifPresent(d -> existing.put(documentId, d));
            Instant now = Instant.now();
            upsert(ctx, collection.get(), null, meta, existing, /* budget: single doc */ null, now);
            // Read the row back rather than recording from `mirrored`: a content-shaped signal is keyed on
            // the document's hash, and the whole point of this signal is that the content just changed.
            documentRepository
                    .findSnapshotByDocumentId(workspaceId, ctx.connectionId(), documentId)
                    .ifPresent(
                            refreshed -> recordAndOffer(workspaceId, refreshed, eventName, now, DiscoveredVia.EVENT));
        } catch (OutlineRateLimitedException e) {
            logRateLimited(workspaceId, e);
        }
    }

    /**
     * Webhook collection-event refresh. {@code collections.delete} tombstones the mirrored collection's
     * documents and records the deletion on the row (the row itself stays — removing it is the admin's
     * call); create/update refresh the mirrored rows' catalog fields with one {@code collections.list}.
     */
    public void refreshCollectionCatalog(long workspaceId, String eventName, @Nullable String collectionId) {
        SyncContext ctx = resolveContext(workspaceId).orElse(null);
        if (ctx == null) {
            return;
        }
        if ("collections.delete".equals(eventName)) {
            if (collectionId == null || collectionId.isBlank()) {
                return;
            }
            collectionRepository
                    .findByWorkspaceIdAndConnectionIdAndCollectionId(workspaceId, ctx.connectionId(), collectionId)
                    .ifPresent(collection -> {
                        Instant now = Instant.now();
                        for (OutlineDocumentSnapshot doc : documentRepository.findSnapshotsByCollectionId(
                                workspaceId, ctx.connectionId(), collection.getCollectionId())) {
                            if (!doc.isDeleted()) {
                                retention.tombstone(ctx.workspaceId(), ctx.connectionId(), doc.documentId(), now, null);
                            }
                        }
                        recordCollectionError(ctx, collection, "Collection was deleted in Outline");
                    });
            return;
        }
        try {
            refreshCatalog(ctx);
        } catch (OutlineRateLimitedException e) {
            logRateLimited(workspaceId, e);
        } catch (OutlineApiException e) {
            // Catalog fields are cosmetic; the periodic reconcile refreshes them anyway.
            log.warn("outline.sync: catalog refresh failed for workspaceId={}: {}", workspaceId, e.toString());
        }
    }

    /**
     * One {@code collections.list} call: refresh every mirrored row's human-facing catalog fields and
     * return the live collections by id (the visibility signal for the reconcile).
     */
    private Map<String, OutlineCollectionModel> refreshCatalog(SyncContext ctx) {
        Map<String, OutlineCollectionModel> live = new LinkedHashMap<>();
        for (OutlineCollectionModel collection : outlineApiClient.listCollections(ctx.serverUrl(), ctx.token())) {
            if (collection.getId() != null) {
                live.put(collection.getId(), collection);
            }
        }
        for (OutlineCollection row : mirroredCollections(ctx)) {
            OutlineCollectionModel upstream = live.get(row.getCollectionId());
            if (upstream == null) {
                continue;
            }
            writeCollection(ctx, row, target -> {
                target.setName(upstream.getName());
                target.setUrlId(upstream.getUrlId());
                target.setColor(upstream.getColor());
                target.setIcon(upstream.getIcon());
                target.setDescription(OutlineSyncMapping.truncate(upstream.getDescription(), MAX_DESCRIPTION_LENGTH));
            });
        }
        return live;
    }

    /**
     * Syncs one collection, recording a per-collection API failure as {@code lastSyncError} so the remaining
     * collections still run. Rate limits propagate and abort the whole pass.
     *
     * @return whether the collection synced without an API failure
     */
    private boolean syncOneCollectionRecordingError(
            SyncContext ctx,
            OutlineCollection collection,
            Map<String, OutlineDocumentSnapshot> existing,
            ExportBudget budget,
            Instant now,
            SyncJobType type,
            BooleanSupplier cancelled) {
        try {
            syncOneCollection(ctx, collection, existing, budget, now, type, cancelled);
            return true;
        } catch (OutlineRateLimitedException e) {
            throw e;
        } catch (OutlineApiException e) {
            String message = e.getMessage() == null ? "Outline API call failed" : e.getMessage();
            // A real gap in the mirror — log for ops in addition to the lastSyncError the admin surface reads.
            log.warn(
                    "outline.sync: collection sync failed for workspaceId={}, collectionId={}: {}",
                    ctx.workspaceId(),
                    collection.getCollectionId(),
                    message);
            recordCollectionError(ctx, collection, message);
            return false;
        }
    }

    /**
     * Enumerate + upsert one collection under the shared export budget. A CLEAN pass (full enumeration,
     * no export skipped for budget) marks the row COMPLETE, and — on a {@code RECONCILIATION} pass —
     * tombstones this collection's vanished rows; a budget-exhausted pass leaves the row PENDING so nothing
     * is ever skipped silently.
     *
     * <p>{@code cancelled} is polled per document, not just per collection. A single collection may hold
     * hundreds of documents and each export is its own round trip, so a cancel checked only between
     * collections could leave a job hammering Outline for the whole export budget after the admin asked it
     * to stop. A cancelled pass takes the same exit as a budget-exhausted one: leave the row PENDING and
     * write nothing derived from the partial enumeration — in particular never the COMPLETE stamp and never
     * the tombstone sweep, whose {@code seen} set is the one thing an aborted enumeration renders unsafe.
     */
    private void syncOneCollection(
            SyncContext ctx,
            OutlineCollection collection,
            Map<String, OutlineDocumentSnapshot> existing,
            ExportBudget budget,
            Instant now,
            SyncJobType type,
            BooleanSupplier cancelled) {
        String collectionId = collection.getCollectionId();
        // documents.list is newest-first, so the budget is spent on the most recently edited documents.
        // A truncated enumeration throws rather than returning a short list — see OutlineApiClient.
        List<OutlineDocumentModel> metas = outlineApiClient.listDocuments(ctx.serverUrl(), ctx.token(), collectionId);
        Map<String, OutlineSyncMapping.FlatNode> nodeById = new LinkedHashMap<>();
        List<OutlineSyncMapping.FlatNode> flat = new ArrayList<>();
        OutlineSyncMapping.flatten(
                outlineApiClient.listCollectionDocuments(ctx.serverUrl(), ctx.token(), collectionId), null, flat);
        for (OutlineSyncMapping.FlatNode node : flat) {
            nodeById.put(node.id(), node);
        }

        Set<String> seen = new HashSet<>();
        int skippedForBudget = 0;
        for (OutlineDocumentModel meta : metas) {
            if (cancelled.getAsBoolean()) {
                abortIncomplete(ctx, collection, collectionId);
                return;
            }
            if (meta.getId() == null) {
                continue;
            }
            seen.add(meta.getId());
            if (upsert(ctx, collection, nodeById.get(meta.getId()), meta, existing, budget, now)
                    == UpsertOutcome.SKIPPED_FOR_BUDGET) {
                skippedForBudget++;
            }
        }
        // Tree-only nodes (present in the structure but absent from documents.list) still count as seen
        // so a clean pass does not tombstone them.
        for (OutlineSyncMapping.FlatNode node : flat) {
            if (cancelled.getAsBoolean()) {
                abortIncomplete(ctx, collection, collectionId);
                return;
            }
            if (!seen.add(node.id())) {
                continue;
            }
            if (upsert(ctx, collection, node, null, existing, budget, now) == UpsertOutcome.SKIPPED_FOR_BUDGET) {
                skippedForBudget++;
            }
        }

        skippedForBudget += syncArchivedDocuments(ctx, collection, existing, seen, budget, now, cancelled);
        if (cancelled.getAsBoolean()) {
            // The archived pass may have stopped part-way, so `seen` is short by an unknown number of
            // archived documents — exactly the rows the sweep must never touch.
            abortIncomplete(ctx, collection, collectionId);
            return;
        }

        // Coverage counters are written on EVERY full enumeration (clean or budget-exhausted): the seen
        // set is complete either way — only exports were skipped, never the enumeration itself.
        int documentsUpstream = seen.size();
        int skipped = skippedForBudget;
        if (skipped > 0) {
            writeCollection(ctx, collection, target -> {
                target.setDocumentsUpstream(documentsUpstream);
                target.setExportsSkippedForBudget(skipped);
                target.setSyncStatus(SyncStatus.PENDING);
            });
            return;
        }
        // Tombstone-by-absence — RECONCILIATION only, the same rule the SCM deletion sweeps obey, and the
        // one thing that makes RECONCILIATION different from INITIAL here. Everything above only upserts,
        // so an INITIAL run still populates the mirror fully; it just never infers a deletion from a mirror
        // it is only half-way through building. The budget-exhausted return above is the other, independent
        // guard: an incomplete enumeration deletes nothing regardless of job type.
        int tombstoned = type == SyncJobType.RECONCILIATION
                ? retention.tombstoneVanished(ctx.workspaceId(), ctx.connectionId(), collectionId, existing, seen, now)
                : 0;
        if (tombstoned > 0) {
            log.info(
                    "outline.sync: tombstoned {} vanished document(s) in collection {} (workspaceId={})",
                    tombstoned,
                    collectionId,
                    ctx.workspaceId());
        }
        writeCollection(ctx, collection, target -> {
            target.setDocumentsUpstream(documentsUpstream);
            target.setExportsSkippedForBudget(skipped);
            target.setDocumentsSyncedAt(now);
            target.setSyncStatus(SyncStatus.COMPLETE);
            target.setLastSyncError(null);
        });
    }

    /**
     * The exit an aborted enumeration takes: mark the collection PENDING and write nothing else.
     *
     * <p>Deliberately writes no coverage counters and no {@code documentsSyncedAt} — both describe a complete
     * enumeration, and this one is not. PENDING is what makes the catch-up tick pick the collection back up,
     * so a cancelled job leaves the mirror converging rather than frozen mid-collection.
     */
    private void abortIncomplete(SyncContext ctx, OutlineCollection collection, String collectionId) {
        log.info(
                "outline.sync: collection sync cancelled for workspaceId={}, collectionId={} — left PENDING",
                ctx.workspaceId(),
                collectionId);
        writeCollection(ctx, collection, target -> target.setSyncStatus(SyncStatus.PENDING));
    }

    /**
     * Upserts one document. An unchanged document (body current and every denormalized field already equal to
     * what this pass would write) touches no row at all, so a stable wiki does zero writes per reconcile; a
     * current body with shifted metadata rewrites without re-exporting. With the budget exhausted the document
     * is left entirely untouched — a partially-written row would masquerade as an eviction placeholder.
     *
     * <p>The export happens before the write transaction opens, so the body crosses the wire with no JDBC
     * connection held.
     */
    private UpsertOutcome upsert(
            SyncContext ctx,
            OutlineCollection collection,
            OutlineSyncMapping.@Nullable FlatNode node,
            @Nullable OutlineDocumentModel meta,
            Map<String, OutlineDocumentSnapshot> existing,
            @Nullable ExportBudget budget,
            Instant now) {
        String documentId = node != null ? node.id() : (meta == null ? null : meta.getId());
        if (documentId == null) {
            return UpsertOutcome.UNCHANGED;
        }
        Instant upstreamUpdatedAt = meta == null ? null : meta.getUpdatedAt();
        OutlineDocumentSnapshot existingRow = existing.get(documentId);

        // Computed once so the skip decision compares against exactly what the mutator would write.
        String desiredCollectionId = collection.getCollectionId();
        String desiredCollectionSlug = OutlineSyncMapping.collectionSlug(collection);
        String desiredParentDocumentId = node != null && node.parentId() != null
                ? node.parentId()
                : (meta == null ? null : meta.getParentDocumentId());
        String desiredTitle =
                node != null && node.title() != null ? node.title() : (meta == null ? null : meta.getTitle());
        String desiredSlug = OutlineSyncMapping.resolveSlug(node, meta);
        Instant desiredArchivedAt = meta == null ? null : meta.getArchivedAt();

        boolean bodyUnchanged = existingRow != null
                && !existingRow.isDeleted()
                && existingRow.contentHash() != null
                && existingRow.outlineUpdatedAt() != null
                && upstreamUpdatedAt != null
                && existingRow.outlineUpdatedAt().equals(upstreamUpdatedAt);

        // Nothing to persist: skip the write transaction and the full-entity load it would incur.
        if (bodyUnchanged
                && existingRow != null
                && Objects.equals(existingRow.collectionId(), desiredCollectionId)
                && Objects.equals(existingRow.collectionSlug(), desiredCollectionSlug)
                && Objects.equals(existingRow.parentDocumentId(), desiredParentDocumentId)
                && Objects.equals(existingRow.title(), desiredTitle)
                && Objects.equals(existingRow.slug(), desiredSlug)
                && Objects.equals(existingRow.archivedAt(), desiredArchivedAt)) {
            return UpsertOutcome.UNCHANGED;
        }

        if (!bodyUnchanged && budget != null && !budget.tryConsume()) {
            return UpsertOutcome.SKIPPED_FOR_BUDGET;
        }

        String body = bodyUnchanged ? null : outlineApiClient.exportDocument(ctx.serverUrl(), ctx.token(), documentId);
        String contentHash = body == null ? null : OutlineSyncMapping.sha256Hex(body);

        OutlineDocumentSnapshot written =
                mirrorWriter.upsertDocument(ctx.workspaceId(), ctx.connectionId(), documentId, doc -> {
                    doc.setCollectionId(desiredCollectionId);
                    doc.setCollectionSlug(desiredCollectionSlug);
                    doc.setParentDocumentId(desiredParentDocumentId);
                    doc.setTitle(desiredTitle);
                    doc.setSlug(desiredSlug);
                    // Listings exclude archived documents, so a document reaching this path is live — clear a
                    // stale archivedAt. A null meta (tree-only node) leaves it untouched.
                    if (meta != null) {
                        doc.setArchivedAt(desiredArchivedAt);
                    }
                    if (bodyUnchanged) {
                        // Metadata shifted (renamed/moved) but the body is current — do not re-export.
                        return;
                    }
                    doc.setBodyMarkdown(body);
                    doc.setContentHash(contentHash);
                    doc.setBodyEvictedAt(null); // the body is back in the mirror — the eviction marker must not linger
                    doc.setOutlineUpdatedAt(upstreamUpdatedAt);
                    if (meta != null) {
                        OutlineSyncMapping.applyAuthorship(doc, meta);
                    }
                    doc.setDeletedAt(null); // revive a previously tombstoned document that reappeared upstream
                    doc.setLastMaterializedAt(now);
                });
        if (written != null) {
            existing.put(documentId, written);
        }
        return bodyUnchanged ? UpsertOutcome.UNCHANGED : UpsertOutcome.EXPORTED;
    }

    /**
     * Enumerates a collection's archived documents (Outline's default listing excludes them) and adds each to
     * {@code seen}, so the tombstone-by-absence sweep never touches them — archive is recoverable, not a delete.
     *
     * <p>Stops early when {@code cancelled} trips — each archived document may cost its own export, so this
     * loop is as interruptible as the live one. The caller detects the short {@code seen} set and suppresses
     * the sweep.
     *
     * @return exports skipped for budget among the archived documents enumerated this pass
     */
    private int syncArchivedDocuments(
            SyncContext ctx,
            OutlineCollection collection,
            Map<String, OutlineDocumentSnapshot> existing,
            Set<String> seen,
            ExportBudget budget,
            Instant now,
            BooleanSupplier cancelled) {
        int skipped = 0;
        for (OutlineDocumentModel meta :
                outlineApiClient.listArchivedDocuments(ctx.serverUrl(), ctx.token(), collection.getCollectionId())) {
            if (cancelled.getAsBoolean()) {
                return skipped;
            }
            if (meta.getId() == null) {
                continue;
            }
            seen.add(meta.getId());
            if (!upsertArchived(ctx, collection, meta, existing, budget, now)) {
                skipped++;
            }
        }
        return skipped;
    }

    /**
     * Upsert one archived document. Never re-exports a body just because {@code updatedAt} moved — an
     * archived document is not being edited, so that would waste the shared budget for no behavioral gain
     * — the ONE exception being a row that carries no body at all (never captured, or evicted), which
     * exports once to backfill it.
     *
     * @return {@code false} when an export was needed but the budget denied it (the row is left untouched)
     */
    private boolean upsertArchived(
            SyncContext ctx,
            OutlineCollection collection,
            OutlineDocumentModel meta,
            Map<String, OutlineDocumentSnapshot> existing,
            ExportBudget budget,
            Instant now) {
        String documentId = Objects.requireNonNull(meta.getId(), "Outline document must have an id");
        OutlineDocumentSnapshot snapshot = existing.get(documentId);
        boolean needsExport = snapshot == null || snapshot.isDeleted() || !snapshot.hasBody();
        if (needsExport && !budget.tryConsume()) {
            return false;
        }
        String body = needsExport ? outlineApiClient.exportDocument(ctx.serverUrl(), ctx.token(), documentId) : null;
        String contentHash = body == null ? null : OutlineSyncMapping.sha256Hex(body);

        OutlineDocumentSnapshot written =
                mirrorWriter.upsertDocument(ctx.workspaceId(), ctx.connectionId(), documentId, doc -> {
                    doc.setCollectionId(collection.getCollectionId());
                    doc.setCollectionSlug(OutlineSyncMapping.collectionSlug(collection));
                    doc.setParentDocumentId(meta.getParentDocumentId());
                    doc.setTitle(meta.getTitle());
                    doc.setSlug(OutlineSyncMapping.resolveSlug(null, meta));
                    doc.setArchivedAt(meta.getArchivedAt() != null ? meta.getArchivedAt() : now);
                    doc.setDeletedAt(null); // archived is not a tombstone — clear any stale marker
                    if (!needsExport) {
                        return;
                    }
                    doc.setBodyMarkdown(body);
                    doc.setContentHash(contentHash);
                    doc.setBodyEvictedAt(null);
                    doc.setOutlineUpdatedAt(meta.getUpdatedAt());
                    OutlineSyncMapping.applyAuthorship(doc, meta);
                    doc.setLastMaterializedAt(now);
                });
        if (written != null) {
            existing.put(documentId, written);
        }
        return true;
    }

    /** Resolve the ACTIVE Outline connection, its server URL, and a decrypted token — or empty (logged). */
    private Optional<SyncContext> resolveContext(long workspaceId) {
        Optional<Connection> active = connectionService.findActive(workspaceId, IntegrationKind.OUTLINE);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Connection connection = active.get();
        if (!(connection.getConfig() instanceof ConnectionConfig.OutlineConfig config)) {
            log.warn("outline.sync: connection {} is not an OutlineConfig — skipping", connection.getId());
            return Optional.empty();
        }
        String serverUrl = config.serverUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            log.warn("outline.sync: no server URL on connection {} — skipping", connection.getId());
            return Optional.empty();
        }
        Optional<BearerToken> bearer = connectionService.findActiveBearerToken(workspaceId, IntegrationKind.OUTLINE);
        if (bearer.isEmpty()) {
            log.warn("outline.sync: no resolvable token for workspaceId={} — skipping", workspaceId);
            return Optional.empty();
        }
        return Optional.of(new SyncContext(
                workspaceId, connection.getId(), serverUrl, bearer.get().token()));
    }

    /**
     * The mirror rows the reconcile diffs against, keyed by document id (spans all collections). Bodies
     * are deliberately NOT loaded — see {@link OutlineDocumentSnapshot}.
     */
    private Map<String, OutlineDocumentSnapshot> loadExisting(SyncContext ctx) {
        Map<String, OutlineDocumentSnapshot> existing = new HashMap<>();
        for (OutlineDocumentSnapshot doc :
                documentRepository.findSnapshotsByWorkspaceIdAndConnectionId(ctx.workspaceId(), ctx.connectionId())) {
            existing.put(doc.documentId(), doc);
        }
        return existing;
    }

    /** Mirrored collection rows for this install (all states — catalog fields refresh on PAUSED too). */
    private List<OutlineCollection> mirroredCollections(SyncContext ctx) {
        return collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(ctx.workspaceId()).stream()
                .filter(c -> c.getConnectionId() == ctx.connectionId())
                .toList();
    }

    /**
     * Persist a mutation of a registered collection's bookkeeping row in its own short transaction. The
     * mutator runs against the row as re-read inside that transaction, never against the possibly-stale
     * copy this pass is iterating — which is also what makes it safely replayable on a lock conflict.
     */
    private void writeCollection(SyncContext ctx, OutlineCollection collection, Consumer<OutlineCollection> mutator) {
        mirrorWriter.updateCollection(ctx.workspaceId(), ctx.connectionId(), collection.getCollectionId(), mutator);
    }

    /** Record a per-collection failure on the admin-facing {@code lastSyncError}, clamped to the column width. */
    private void recordCollectionError(SyncContext ctx, OutlineCollection collection, String message) {
        String clamped = OutlineSyncMapping.truncate(message, MAX_ERROR_LENGTH);
        writeCollection(ctx, collection, target -> target.setLastSyncError(clamped));
    }

    private static void logRateLimited(long workspaceId, OutlineRateLimitedException e) {
        // Pause, don't abort: progress committed so far is kept and the remainder resumes next tick. The
        // tombstone pass is intentionally skipped so a document we did not reach is never dropped.
        log.warn(
                "outline.sync: rate-limited for workspaceId={} — pausing this pass (retryAfter={})",
                workspaceId,
                e.getRetryAfter());
    }

    /** The resolved per-pass call context: workspace, install, host, and a decrypted token. */
    private record SyncContext(long workspaceId, long connectionId, String serverUrl, String token) {}

    private enum UpsertOutcome {
        EXPORTED,
        UNCHANGED,
        SKIPPED_FOR_BUDGET,
    }

    /** Mutable countdown of exports one pass may spend; shared across a pass's collections. */
    private static final class ExportBudget {

        private int remaining;

        ExportBudget(int budget) {
            this.remaining = budget;
        }

        boolean tryConsume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        int remaining() {
            return remaining;
        }
    }
}
