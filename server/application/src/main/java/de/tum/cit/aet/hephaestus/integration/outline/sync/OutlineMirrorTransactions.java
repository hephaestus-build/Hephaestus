package de.tum.cit.aet.hephaestus.integration.outline.sync;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.ProviderStateVersion;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot;
import de.tum.cit.aet.hephaestus.integration.outline.identity.OutlineRosterIdentityResolver;
import java.time.Instant;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Outline mirror's only write transactions: short {@code REQUIRES_NEW} units that touch nothing but the
 * database, so {@link OutlineDocumentSyncService} can run its enumeration and HTTP calls holding no pooled
 * connection. Holding one across the wire turns a slow-but-alive Outline into pool starvation and a Postgres
 * backend {@code idle in transaction}, blocking vacuum on the mirror.
 *
 * <p>These live apart from {@link OutlineMirrorWriter} because {@code @Transactional} only takes effect across a
 * proxy hop, and the retry layered on top must run outside any transaction — a failed optimistic-lock flush marks
 * its transaction rollback-only, so retrying inside it could never commit.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineMirrorTransactions {

    private static final Logger log = LoggerFactory.getLogger(OutlineMirrorTransactions.class);

    private final OutlineDocumentRepository documentRepository;
    private final OutlineCollectionRepository collectionRepository;
    private final OutlineRosterIdentityResolver people;

    public OutlineMirrorTransactions(
            OutlineDocumentRepository documentRepository,
            OutlineCollectionRepository collectionRepository,
            OutlineRosterIdentityResolver people) {
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.people = people;
    }

    /**
     * Upserts one mirrored document. The row is re-read inside this transaction rather than carried in from the
     * caller's diff, so the write applies to current state and the {@code @Version} window stays narrow.
     *
     * @return the body-free snapshot of the row as written, so the caller's diff map stays current
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable OutlineDocumentSnapshot upsertDocument(
            long workspaceId,
            long connectionId,
            String documentId,
            @Nullable Instant sourceUpdatedAt,
            Consumer<OutlineDocument> mutator) {
        boolean claimed = DeliveryInbox.claim("outline.document.upsert." + connectionId + "." + documentId);
        var existing =
                documentRepository.findByWorkspaceIdAndConnectionIdAndDocumentId(workspaceId, connectionId, documentId);
        if (!claimed) return existing.map(OutlineDocumentSnapshot::of).orElse(null);
        OutlineDocument doc = existing.orElseGet(() -> {
            OutlineDocument fresh = new OutlineDocument();
            fresh.setWorkspaceId(workspaceId);
            fresh.setConnectionId(connectionId);
            fresh.setDocumentId(documentId);
            return fresh;
        });
        if (ProviderStateVersion.isOlder(sourceUpdatedAt, doc.getOutlineUpdatedAt())) {
            return OutlineDocumentSnapshot.of(doc);
        }

        mutator.accept(doc);
        people.recordAuthors(doc);
        return OutlineDocumentSnapshot.of(documentRepository.saveAndFlush(doc));
    }

    /**
     * Update an <em>existing</em> mirrored document — the tombstone and archive-stamp paths, which must
     * never conjure a row for a document the mirror does not have.
     *
     * @return the snapshot as written, or {@code null} when the row is gone (hard-deleted by a concurrent
     *         collection erase); the caller treats that as "nothing left to do"
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable OutlineDocumentSnapshot updateDocument(
            long workspaceId, long connectionId, String documentId, Consumer<OutlineDocument> mutator) {
        return updateDocument(workspaceId, connectionId, documentId, null, mutator);
    }

    /** A lifecycle payload applies only while its provider version is newer than the current snapshot. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable OutlineDocumentSnapshot updateDocumentAtVersion(
            long workspaceId,
            long connectionId,
            String documentId,
            Instant sourceUpdatedAt,
            Consumer<OutlineDocument> mutator) {
        return updateDocument(workspaceId, connectionId, documentId, sourceUpdatedAt, mutator);
    }

    private @Nullable OutlineDocumentSnapshot updateDocument(
            long workspaceId,
            long connectionId,
            String documentId,
            @Nullable Instant sourceUpdatedAt,
            Consumer<OutlineDocument> mutator) {
        return documentRepository
                .findByWorkspaceIdAndConnectionIdAndDocumentId(workspaceId, connectionId, documentId)
                .filter(doc -> sourceUpdatedAt == null
                        || !ProviderStateVersion.cannotReplace(sourceUpdatedAt, doc.getOutlineUpdatedAt()))
                .map(doc -> {
                    if (!DeliveryInbox.claim("outline.document.update." + connectionId + "." + documentId)) {
                        return OutlineDocumentSnapshot.of(doc);
                    }
                    mutator.accept(doc);
                    return OutlineDocumentSnapshot.of(documentRepository.saveAndFlush(doc));
                })
                .orElse(null);
    }

    /**
     * Update a registered collection's bookkeeping (catalog fields, watermarks, sync status, last error).
     * A row that vanished mid-pass (the admin removed the collection from the mirror while the reconcile
     * was walking it) is skipped: there is nothing left to record progress on.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateCollection(
            long workspaceId,
            long connectionId,
            String collectionId,
            String purpose,
            Consumer<OutlineCollection> mutator) {
        if (!DeliveryInbox.claim("outline.collection." + purpose + "." + connectionId + "." + collectionId)) return;
        collectionRepository
                .findByWorkspaceIdAndConnectionIdAndCollectionId(workspaceId, connectionId, collectionId)
                .ifPresentOrElse(
                        row -> {
                            mutator.accept(row);
                            collectionRepository.saveAndFlush(row);
                        },
                        () -> log.debug(
                                "outline.sync: collection {} vanished from the registry mid-pass (workspaceId={}) — skipping its bookkeeping write",
                                collectionId,
                                workspaceId));
    }

    /**
     * The staleness drop: hard-delete tombstoned rows older than {@code cutoff}. Its own read-write
     * transaction — a derived delete inherits Spring Data's {@code readOnly = true} default otherwise and
     * would silently never flush.
     *
     * @return rows deleted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long dropStaleTombstones(long workspaceId, Instant cutoff) {
        if (!DeliveryInbox.claim("outline.tombstones.prune." + workspaceId)) return 0;
        return documentRepository.deleteByWorkspaceIdAndDeletedAtBefore(workspaceId, cutoff);
    }
}
