package de.tum.cit.aet.hephaestus.integration.outline.sync;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.ProviderStateVersion;
import de.tum.cit.aet.hephaestus.integration.outline.OutlineProperties;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The mirror's erasure half: the operations that <em>remove</em> mirrored content, as opposed to the
 * enumerate-and-upsert half that {@link OutlineDocumentSyncService} performs.
 *
 * <p>Removal is a different concern from ingestion and carries different risk. An upsert that goes wrong
 * is corrected by the next pass; a body or an author field dropped here is gone — the mirror is the only
 * copy Hephaestus holds, and Outline offers no way to ask for it back once the upstream document is
 * really deleted. Keeping the three erasure paths together makes that risk one readable surface instead
 * of three clauses buried in a thousand-line reconcile:
 *
 * <ul>
 *   <li>{@link #tombstone} — an upstream deletion: drop the body, its hash and every person-bearing
 *       field, keeping only the structural marker;</li>
 *   <li>{@link #tombstoneVanished} — deletion <em>inferred</em> from absence in an enumeration;</li>
 *   <li>{@link #enforceSizeCap} — capacity eviction, which drops bodies but keeps the rows and their
 *       hashes so the next reconcile can tell "evicted" from "changed".</li>
 * </ul>
 *
 * <p><b>This class does not decide when to erase.</b> The fail-closed rules live with the enumeration
 * that alone can know whether it was complete: {@link #tombstoneVanished} deletes exactly what its
 * caller's {@code seen} set says is gone, so a caller must pass a set from a full, uncancelled,
 * un-rate-limited enumeration on a {@link de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType#RECONCILIATION}
 * pass, and must not call it at all otherwise. Nothing here is transactional; each write is its own short
 * {@code REQUIRES_NEW} unit via {@link OutlineMirrorWriter}, matching the sync service's connection
 * discipline.
 *
 * <p>The event log ({@code outline_document_event}) is deliberately out of scope for every path here: it
 * is the audit trail and erases with the workspace/connection, not with a document.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineMirrorRetentionService {

    private static final Logger log = LoggerFactory.getLogger(OutlineMirrorRetentionService.class);

    /** Postgres caps a statement at 65 535 bind parameters, so eviction pages its id list at this width. */
    static final int EVICTION_BATCH_SIZE = 1000;

    /** The eviction loop terminates on its own; this only bounds a pathological no-op {@code UPDATE}. */
    private static final int MAX_EVICTION_ROUNDS = 100;

    private final OutlineDocumentRepository documentRepository;
    private final OutlineMirrorWriter mirrorWriter;
    private final OutlineProperties properties;

    public OutlineMirrorRetentionService(
            OutlineDocumentRepository documentRepository,
            OutlineMirrorWriter mirrorWriter,
            OutlineProperties properties) {
        this.documentRepository = documentRepository;
        this.mirrorWriter = mirrorWriter;
        this.properties = properties;
    }

    /**
     * Tombstone the mirrored rows of {@code collectionId} that {@code seen} does not contain — deletion
     * inferred from absence.
     *
     * <p>The inference is only as good as {@code seen}: a short set reads as upstream deletions. The caller
     * owns that guarantee (full enumeration, nothing skipped, RECONCILIATION only) and must simply not call
     * this method when it cannot make it.
     *
     * @param existing the caller's diff map, kept current with every row written here
     * @return how many rows were tombstoned
     */
    public int tombstoneVanished(
            long workspaceId,
            long connectionId,
            String collectionId,
            Map<String, OutlineDocumentSnapshot> existing,
            Set<String> seen,
            Instant now) {
        int count = 0;
        for (OutlineDocumentSnapshot doc : List.copyOf(existing.values())) {
            if (!collectionId.equals(doc.collectionId()) || seen.contains(doc.documentId()) || doc.isDeleted()) {
                continue;
            }
            tombstone(workspaceId, connectionId, doc.documentId(), now, existing);
            count++;
        }
        return count;
    }

    /**
     * Drop everything person- or content-bearing: the body, its hash, and the author/collaborator
     * fields share the same PII posture — a document that no longer exists upstream keeps only its
     * structural marker.
     *
     * @param existing the caller's diff map to keep current, or {@code null} when the caller holds none
     */
    public void tombstone(
            long workspaceId,
            long connectionId,
            String documentId,
            Instant now,
            @Nullable Map<String, OutlineDocumentSnapshot> existing) {
        tombstone(workspaceId, connectionId, documentId, now, existing, null);
    }

    /** A dated deletion cannot erase a document that advanced while its provider read was in flight. */
    public void tombstone(
            long workspaceId,
            long connectionId,
            String documentId,
            Instant now,
            @Nullable Map<String, OutlineDocumentSnapshot> existing,
            @Nullable Instant sourceUpdatedAt) {
        OutlineDocumentSnapshot written = mirrorWriter.updateDocument(workspaceId, connectionId, documentId, doc -> {
            if (sourceUpdatedAt != null && ProviderStateVersion.isOlder(sourceUpdatedAt, doc.getOutlineUpdatedAt()))
                return;
            if (sourceUpdatedAt != null) doc.setOutlineUpdatedAt(sourceUpdatedAt);
            doc.setDeletedAt(now);
            doc.setBodyMarkdown(null);
            // unlike an eviction, a tombstone drops the hash too (enforced by ck_outline_document_tombstone)
            doc.setContentHash(null);
            doc.setCreatedBySubject(null);
            doc.setCreatedByName(null);
            doc.setUpdatedBySubject(null);
            doc.setUpdatedByName(null);
            doc.setCollaboratorSubjects(null);
        });
        if (existing != null && written != null) {
            existing.put(documentId, written);
        }
    }

    /**
     * Enforce the per-workspace body-size cap by nulling the least-recently-materialized bodies until the
     * mirror is back under the cap. Size is measured in bytes ({@code octet_length}) against the byte cap,
     * so multibyte content counts at its true on-disk weight rather than its character count.
     *
     * <p>Candidates are paged and evicted in batches of {@link #EVICTION_BATCH_SIZE}: an evicted row drops
     * out of the candidate query, so each round makes progress. One unbounded {@code IN (:ids)} over a
     * large over-cap mirror would blow past Postgres's 65 535 bind-parameter ceiling long before the
     * planner gave up on it.
     */
    public void enforceSizeCap(long workspaceId) {
        long capBytes = (long) properties.cache().maxSizeMb() * 1024L * 1024L;
        long remaining = documentRepository.sumBodySizeByWorkspaceId(workspaceId);
        if (remaining <= capBytes) {
            return;
        }
        int evicted = 0;
        for (int round = 0; round < MAX_EVICTION_ROUNDS && remaining > capBytes; round++) {
            List<Object[]> candidates = documentRepository.findEvictionCandidates(workspaceId, EVICTION_BATCH_SIZE);
            if (candidates.isEmpty()) {
                break;
            }
            List<Long> batch = new ArrayList<>();
            for (Object[] row : candidates) {
                if (remaining <= capBytes) {
                    break;
                }
                batch.add(((Number) row[0]).longValue());
                remaining -= ((Number) row[1]).longValue();
            }
            if (batch.isEmpty()) {
                break;
            }
            documentRepository.evictBodies(workspaceId, batch);
            evicted += batch.size();
        }
        if (evicted > 0) {
            log.info(
                    "outline.sync: evicted {} body(ies) for workspaceId={} to honor {}MB cap",
                    evicted,
                    workspaceId,
                    properties.cache().maxSizeMb());
        }
    }
}
