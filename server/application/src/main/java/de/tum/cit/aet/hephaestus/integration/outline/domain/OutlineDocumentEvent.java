package de.tum.cit.aet.hephaestus.integration.outline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One append-only row per Outline {@code documents.*} webhook event — the longitudinal editing-activity
 * signal (who touched which document when) the point-in-time mirror cannot answer, and the source of
 * the middle editors that {@code createdBy}/{@code updatedBy} miss. Written by the webhook consumer
 * from the delivery envelope only ({@code actorId} + {@code createdAt}); webhook-forward by design —
 * there is no {@code events.list} backfill and no {@code revisions.list} body capture.
 *
 * <p><strong>Immutable by construction.</strong> {@link Immutable} plus an insert-and-read-only
 * repository: no update or delete path per row, so the trail cannot be rewritten. A document
 * tombstone does NOT touch these rows (they are the audit trail of the mirror); the whole log is
 * erased with its workspace/connection ({@code actor_subject} is personal data, so teardown is the
 * GDPR erase point). Workspace-scoped (scalar {@code workspaceId}) like every Outline table.
 */
@Entity
@Immutable
@Table(name = "outline_document_event")
@Getter
@NoArgsConstructor
public class OutlineDocumentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    /** Outline document id (UUID) the event is about — the envelope's {@code payload.id}. */
    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    /** Full upstream event name, e.g. {@code documents.update} or {@code documents.delete}. */
    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    /** Outline user id (UUID) of the actor — the envelope's {@code actorId}; null when absent. */
    @Column(name = "actor_subject", length = 64)
    private @Nullable String actorSubject;

    /** Upstream event time — the envelope's {@code createdAt}; null when absent/unparsable. */
    @Column(name = "occurred_at")
    private @Nullable Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    public OutlineDocumentEvent(
            Long workspaceId,
            Long connectionId,
            String documentId,
            String eventName,
            @Nullable String actorSubject,
            @Nullable Instant occurredAt) {
        this.workspaceId = workspaceId;
        this.connectionId = connectionId;
        this.documentId = documentId;
        this.eventName = eventName;
        this.actorSubject = actorSubject;
        this.occurredAt = occurredAt;
    }
}
