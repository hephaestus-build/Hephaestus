package de.tum.cit.aet.hephaestus.integration.outline.sync;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Retry-once façade over {@link OutlineMirrorTransactions} — the seam the mirror is written through.
 *
 * <p>Both mirror rows carry a {@code @Version} column because they have concurrent writers that nothing
 * serializes per workspace (a webhook refresh versus a mid-flight reconcile; an admin PATCH versus a sync kick).
 * Without it, the loser's full-column save silently clobbers the winner.
 *
 * <p>The retry re-runs the whole unit of work in a <em>fresh</em> transaction rather than re-saving inside the
 * failed one: an optimistic-lock failure marks its transaction rollback-only, so a same-transaction retry could
 * never commit and would quietly lose the write. Re-running re-reads the row at its current version and re-applies
 * the mutation; the mutations are pure functions of already-fetched payload, so a replay costs no extra API call.
 *
 * <p>A broker delivery retries after a second conflict so its unfinished step cannot be marked complete.
 * A sync pass logs and skips that row; the next reconcile re-diffs and converges.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineMirrorWriter {

    private static final Logger log = LoggerFactory.getLogger(OutlineMirrorWriter.class);

    private final OutlineMirrorTransactions transactions;

    public OutlineMirrorWriter(OutlineMirrorTransactions transactions) {
        this.transactions = transactions;
    }

    /**
     * Upsert one mirrored document. See {@link OutlineMirrorTransactions#upsertDocument}.
     *
     * @return the written or previously committed snapshot, or {@code null} when that row was erased
     *         or a sync pass lost the optimistic-lock race twice
     */
    public @Nullable OutlineDocumentSnapshot upsertDocument(
            long workspaceId,
            long connectionId,
            String documentId,
            @Nullable Instant sourceUpdatedAt,
            Consumer<OutlineDocument> mutator) {
        return retryOnce(
                () -> transactions.upsertDocument(workspaceId, connectionId, documentId, sourceUpdatedAt, mutator),
                "document",
                documentId);
    }

    /**
     * Update an existing mirrored document (tombstone / archive stamp). See
     * {@link OutlineMirrorTransactions#updateDocument}.
     *
     * @return the snapshot of the written row, or {@code null} when the row was already gone or the write
     *         lost the optimistic-lock race twice during a sync pass
     */
    public @Nullable OutlineDocumentSnapshot updateDocument(
            long workspaceId, long connectionId, String documentId, Consumer<OutlineDocument> mutator) {
        return retryOnce(
                () -> transactions.updateDocument(workspaceId, connectionId, documentId, mutator),
                "document",
                documentId);
    }

    /** Updates lifecycle state only when the provider version still advances the mirror. */
    public @Nullable OutlineDocumentSnapshot updateDocumentAtVersion(
            long workspaceId,
            long connectionId,
            String documentId,
            Instant sourceUpdatedAt,
            Consumer<OutlineDocument> mutator) {
        return retryOnce(
                () -> transactions.updateDocumentAtVersion(
                        workspaceId, connectionId, documentId, sourceUpdatedAt, mutator),
                "document",
                documentId);
    }

    /** Update a registered collection's bookkeeping. See {@link OutlineMirrorTransactions#updateCollection}. */
    public void updateCollection(
            long workspaceId,
            long connectionId,
            String collectionId,
            String purpose,
            Consumer<OutlineCollection> mutator) {
        retryOnce(
                () -> {
                    transactions.updateCollection(workspaceId, connectionId, collectionId, purpose, mutator);
                    return null;
                },
                "collection",
                collectionId);
    }

    /** The staleness drop. See {@link OutlineMirrorTransactions#dropStaleTombstones}. */
    public long dropStaleTombstones(long workspaceId, Instant cutoff) {
        return transactions.dropStaleTombstones(workspaceId, cutoff);
    }

    /**
     * Runs {@code write} — one whole {@code REQUIRES_NEW} transaction — retrying it exactly once in a new
     * transaction when a concurrent writer won the optimistic-lock race. Broker deliveries propagate a
     * second conflict; sync passes skip that row and continue with their remaining rows.
     */
    private <T> @Nullable T retryOnce(Supplier<T> write, String kind, String identifier) {
        try {
            return write.get();
        } catch (ObjectOptimisticLockingFailureException conflict) {
            log.debug("outline.sync: concurrent update racing {} {} — retrying once", kind, identifier);
        }
        try {
            return write.get();
        } catch (ObjectOptimisticLockingFailureException stillContended) {
            if (DeliveryInbox.isDispatching()) throw stillContended;
            log.warn(
                    "outline.sync: {} {} lost the optimistic-lock retry too — skipping, the next reconcile fixes it",
                    kind,
                    identifier);
            return null;
        }
    }
}
