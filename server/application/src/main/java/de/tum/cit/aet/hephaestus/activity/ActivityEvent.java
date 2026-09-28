package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One thing that happened in a workspace: who did what to which target, and when.
 *
 * <h3>Idempotency</h3>
 * <p>Deduplicated by {@code (workspace_id, event_key)}.
 *
 * <h3>Indexes</h3>
 * <ul>
 *   <li>workspace_id + occurred_at: workspace activity with a time range</li>
 *   <li>actor_id + occurred_at: one person's activity feed</li>
 *   <li>workspace_id + actor_id + occurred_at: one member's activity in a workspace</li>
 *   <li>workspace_id + target_type + target_id: events about one target</li>
 * </ul>
 *
 * <h3>Corrections</h3>
 * <p>{@code @Immutable} keeps Hibernate from updating a row. Attribution is corrected only by maintenance SQL
 * such as {@link ActivityEventRepository#backfillCommitActors}.
 */
@Entity
@Immutable
@Table(
        name = "activity_event",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_activity_event_workspace_key",
                    columnNames = {"workspace_id", "event_key"}),
        },
        indexes = {
            @Index(name = "idx_activity_event_workspace_occurred", columnList = "workspace_id, occurred_at DESC"),
            @Index(name = "idx_activity_event_actor_occurred", columnList = "actor_id, occurred_at DESC"),
            @Index(
                    name = "idx_activity_event_workspace_actor_occurred",
                    columnList = "workspace_id, actor_id, occurred_at DESC"),
            @Index(name = "idx_activity_event_workspace_target", columnList = "workspace_id, target_type, target_id"),
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityEvent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** Deterministic key for idempotent upserts: {type}:{target_id}:{timestamp_ms} */
    @NotNull
    @Column(name = "event_key", nullable = false)
    private String eventKey;

    /** What happened: pr.opened, review.submitted, comment.created, etc. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 64, nullable = false)
    private ActivityEventType eventType;

    /** When the event actually happened (source timestamp) */
    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** The user who performed the action (nullable for system events) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private @Nullable User actor;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private Repository repository;

    /** What was acted on: pull_request, issue, review, notification, etc. */
    @Column(name = "target_type", length = 32)
    private String targetType;

    /** ID of the target object */
    @Column(name = "target_id")
    private Long targetId;

    /** When we persisted to the database */
    @NotNull
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (ingestedAt == null) {
            ingestedAt = Instant.now();
        }
    }

    /**
     * Build a deterministic event key for idempotent upserts.
     *
     * <p>The key format is {@code {event_type}:{target_id}:{timestamp_ms}}.
     * This ensures that duplicate events (same action on same entity at same time)
     * are deduplicated at the database level via unique constraint.
     *
     * @param type the event type (e.g., PULL_REQUEST_OPENED)
     * @param targetId the ID of the target entity
     * @param timestamp when the event occurred
     * @return a deterministic key for idempotency checking
     */
    public static String buildKey(ActivityEventType type, Long targetId, Instant timestamp) {
        return String.format("%s:%d:%d", type.getValue(), targetId, timestamp.toEpochMilli());
    }
}
