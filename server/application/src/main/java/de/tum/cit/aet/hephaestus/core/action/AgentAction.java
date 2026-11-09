package de.tum.cit.aet.hephaestus.core.action;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One effect on the outside world and the state of its delivery. The mapping states the schema; every write is a
 * compare-and-set in {@link ActionStore}, so nothing changes a row through this entity.
 *
 * <p>An action keeps its {@code payload} only while it can still run: the payload is cleared when the action ends, and
 * the erasure of the person it addresses deletes the action.
 */
@Entity
@Table(
        name = "agent_action",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_agent_action_idempotency_key", columnNames = "idempotency_key"),
        indexes = {
            @Index(name = "idx_agent_action_lane", columnList = "lane_key, id"),
            @Index(name = "idx_agent_action_subject_account", columnList = "subject_account_id")
        })
@Getter
@NoArgsConstructor
public class AgentAction {

    /** Time ordered, so the order of ids is the order of a lane. */
    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    /** NULL only for an {@link ActionKind#instanceLevel()} kind, such as an email to an account. */
    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private ActionKind kind;

    /** Actions with the same key run one at a time, in the order they were enqueued. */
    @Column(name = "lane_key", nullable = false, length = 255)
    private String laneKey;

    /** Enqueuing the same effect twice yields one action. */
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ActionState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private @Nullable JsonNode payload;

    /** The account the effect addresses; the erasure of that account deletes the action. */
    @Column(name = "subject_account_id")
    private @Nullable Long subjectAccountId;

    @ColumnDefault("0")
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** An action that has not started by then ends {@link ActionStopReason#EXPIRED}. */
    @Column(name = "expires_at")
    private @Nullable Instant expiresAt;

    /** Rises with every claim. A write that names an older epoch changes nothing. */
    @ColumnDefault("0")
    @Column(name = "lease_epoch", nullable = false)
    private long leaseEpoch;

    @Column(name = "leased_until")
    private @Nullable Instant leasedUntil;

    /** Set, under the lease, just before the provider call: from then on a write may exist. */
    @Column(name = "write_started_at")
    private @Nullable Instant writeStartedAt;

    /** The provider's name for the effect, such as a Message-ID or a comment id. */
    @Column(name = "readback_ref", length = 998)
    private @Nullable String readbackRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 32)
    private @Nullable ActionStopReason stopReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;
}
