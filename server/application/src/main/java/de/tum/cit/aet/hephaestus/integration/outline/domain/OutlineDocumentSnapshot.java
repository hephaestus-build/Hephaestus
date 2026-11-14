package de.tum.cit.aet.hephaestus.integration.outline.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The body-free view of a mirrored {@link OutlineDocument} the reconcile diffs against.
 *
 * <p>This exists for one reason: {@code bodyMarkdown} is an unbounded eagerly-fetched {@code text}
 * column (the build has no bytecode enhancement, so {@code @Basic(LAZY)} would be silently ignored),
 * and the per-workspace mirror is capped at hundreds of megabytes. Loading the whole mirror as entities
 * to diff it would materialize every Markdown body as a UTF-16 {@code String} plus Hibernate's
 * dirty-check snapshot of it — a multi-hundred-megabyte spike in the application-server JVM on every
 * six-hourly reconcile, next to the conversation.
 *
 * <p>The diff never needs a body: it decides on {@code outlineUpdatedAt} + {@code contentHash} (the
 * unchanged fast path), {@code deletedAt} (revive vs. tombstone), mere body <em>presence</em> (the
 * archived-document backfill), and the denormalized metadata columns (collection, parent, title, slug,
 * archive stamp) so a present-and-unchanged document can be recognised and left untouched without loading
 * the row at all. Only rows that are actually written are re-read as full entities, inside the short write
 * transaction that mutates them.
 *
 * @param bodyLength length of the mirrored body in characters, or {@code null} when there is no body
 *                   (never exported, size-cap evicted, or tombstoned) — see {@link #hasBody()}
 */
public record OutlineDocumentSnapshot(
        Long id,
        String documentId,
        String collectionId,
        @Nullable String collectionSlug,
        @Nullable String parentDocumentId,
        @Nullable String title,
        @Nullable String slug,
        @Nullable Instant archivedAt,
        @Nullable Instant outlineUpdatedAt,
        @Nullable String contentHash,
        @Nullable Instant deletedAt,
        @Nullable Integer bodyLength,
        @Nullable Long version,
        @Nullable String createdBySubject) {
    /** Whether the document has been tombstoned (removed upstream). Mirrors {@link OutlineDocument#isDeleted()}. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Whether the row still carries a mirrored body — the archived-document backfill's only body question. */
    public boolean hasBody() {
        return bodyLength != null;
    }

    /** The snapshot of a full entity, taken right after a write so the caller's diff map stays current. */
    public static OutlineDocumentSnapshot of(OutlineDocument doc) {
        return new OutlineDocumentSnapshot(
                doc.getId(),
                doc.getDocumentId(),
                doc.getCollectionId(),
                doc.getCollectionSlug(),
                doc.getParentDocumentId(),
                doc.getTitle(),
                doc.getSlug(),
                doc.getArchivedAt(),
                doc.getOutlineUpdatedAt(),
                doc.getContentHash(),
                doc.getDeletedAt(),
                doc.getBodyMarkdown() == null ? null : doc.getBodyMarkdown().length(),
                doc.getVersion(),
                doc.getCreatedBySubject());
    }
}
