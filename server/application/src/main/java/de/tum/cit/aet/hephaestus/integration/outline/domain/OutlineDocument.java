package de.tum.cit.aet.hephaestus.integration.outline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A local copy of one Outline document. Workspace-scoped and tied to the {@code connectionId} of the install it
 * came from, so a workspace can mirror several Outline instances without document-id collisions. The mirror is
 * what the agent reads — a sandbox run never calls Outline.
 *
 * <p>{@code bodyMarkdown} is {@code null} in two states that both render as a placeholder rather than a missing
 * file: removed upstream ({@code deletedAt}) and size-cap evicted ({@code bodyEvictedAt}). An evicted row keeps
 * its {@code contentHash}, so the body returns only when upstream actually changes — never in an
 * evict-re-export thrash loop.
 */
@Entity
@Table(
        name = "outline_document",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_outline_document",
                        columnNames = {"workspace_id", "connection_id", "document_id"}))
@Getter
@Setter
@NoArgsConstructor
public class OutlineDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic-lock guard. A webhook refresh and a mid-flight reconcile can write the same row concurrently;
     * without a version column the loser's full-column save would silently clobber the winner. A losing write is
     * retried once against the row's current version.
     */
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    /** Outline document id (UUID). */
    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    /** Outline collection id (UUID) the document belongs to. */
    @Column(name = "collection_id", nullable = false, length = 64)
    private String collectionId;

    /** Collection slug, used to lay the document out under a stable directory. */
    @Column(name = "collection_slug", length = 512)
    private @Nullable String collectionSlug;

    /** Parent document id (UUID) for a nested document, or {@code null} at the collection root. */
    @Column(name = "parent_document_id", length = 64)
    private @Nullable String parentDocumentId;

    @Column(name = "title", length = 1024)
    private @Nullable String title;

    /** Document slug, used as the materialized file name. */
    @Column(name = "slug", length = 512)
    private @Nullable String slug;

    @Column(name = "body_markdown", columnDefinition = "text")
    private @Nullable String bodyMarkdown;

    /** Hash of the exported body; lets a sync skip re-exporting an unchanged document. */
    @Column(name = "content_hash", length = 64)
    private @Nullable String contentHash;

    /** Upstream {@code updatedAt}; the incremental cursor a sync diffs against. */
    @Column(name = "outline_updated_at")
    private @Nullable Instant outlineUpdatedAt;

    /** Upstream {@code createdAt} — the document-age half of the up-to-dateness signal. */
    @Column(name = "outline_created_at")
    private @Nullable Instant outlineCreatedAt;

    /**
     * Outline user ids (UUIDs) of everyone who edited the document ({@code Document.collaboratorIds})
     * — the middle editors the creator/last-editor pair misses. Same lazy-join, PII posture as
     * {@link #createdBySubject}; {@code null} (never an empty array) when upstream reports none.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collaborator_subjects", columnDefinition = "jsonb")
    private @Nullable List<String> collaboratorSubjects;

    /**
     * Outline user id (UUID) of the document's creator — authorship substrate. Subjects join
     * {@code identity_link} lazily at projection time; no member id is stamped here.
     *
     * <p>Self-service account deletion severs identity links. Instance-admin person erasure clears
     * this subject and its display-name snapshot and removes the selected local content copies.
     * Sync can mirror records that remain upstream; permanent exact-identity controls exclude them
     * from further AI processing.
     */
    @Column(name = "created_by_subject", length = 64)
    private @Nullable String createdBySubject;

    /** Creator display name (fallback when the subject has no linked account). */
    @Column(name = "created_by_name", length = 255)
    private @Nullable String createdByName;

    /** Outline user id (UUID) of the last editor — authorship substrate, same lazy-join contract. */
    @Column(name = "updated_by_subject", length = 64)
    private @Nullable String updatedBySubject;

    /** Last editor display name. */
    @Column(name = "updated_by_name", length = 255)
    private @Nullable String updatedByName;

    /** Set when the document no longer exists upstream; the body is dropped and a placeholder is served. */
    @Column(name = "deleted_at")
    private @Nullable Instant deletedAt;

    /**
     * Set when the document is archived in Outline — soft and recoverable, unlike {@link #deletedAt}: the
     * body, hash, and author/collaborator fields are all KEPT. Cleared the moment the document is seen live
     * again (a normal {@code documents.list}/{@code collections.documents} enumeration, both of which exclude
     * archived documents by default, or an {@code documents.unarchive} refresh).
     */
    @Column(name = "archived_at")
    private @Nullable Instant archivedAt;

    /** When the document body was last exported from Outline into the mirror; drives least-recently-exported eviction. */
    @Column(name = "last_materialized_at")
    private @Nullable Instant lastMaterializedAt;

    /**
     * Set when the size cap evicted the body ({@code bodyMarkdown} nulled, {@code contentHash} kept),
     * cleared when a sync re-exports the body. Distinguishes "evicted" from "tombstoned" explicitly.
     */
    @Column(name = "body_evicted_at")
    private @Nullable Instant bodyEvictedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether the document has been tombstoned (removed upstream). */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Whether the document is archived in Outline (soft, recoverable — content is still present). */
    public boolean isArchived() {
        return archivedAt != null;
    }
}
