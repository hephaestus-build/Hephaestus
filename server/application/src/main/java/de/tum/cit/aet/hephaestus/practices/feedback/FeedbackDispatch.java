package de.tum.cit.aet.hephaestus.practices.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@Table(
        name = "feedback_dispatch",
        uniqueConstraints = @UniqueConstraint(name = "uk_feedback_dispatch_key", columnNames = "destination_key"),
        indexes = {
            @Index(name = "idx_feedback_dispatch_recovery", columnList = "state, lease_expires_at, updated_at"),
            @Index(name = "idx_feedback_dispatch_workspace", columnList = "workspace_id, created_at DESC"),
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackDispatch {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotNull
    @Column(name = "destination_key", nullable = false, length = 96)
    private String destinationKey;

    @NotNull
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @NotNull
    @Column(name = "agent_job_id", nullable = false, columnDefinition = "UUID")
    private UUID agentJobId;

    @Column(name = "feedback_id", columnDefinition = "UUID")
    private @Nullable UUID feedbackId;

    public UUID approvedFeedbackId() {
        return java.util.Objects.requireNonNull(feedbackId, "an approved dispatch always names its feedback");
    }

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false, length = 40)
    private FeedbackDispatchDestination destination;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private FeedbackDispatchState state;

    @NotNull
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "practice_slugs", nullable = false, columnDefinition = "jsonb")
    private JsonNode practiceSlugs;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "package_content", nullable = false, columnDefinition = "jsonb")
    private JsonNode packageContent;

    public JsonNode packageContent() {
        return packageContent;
    }

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivered_placements", nullable = false, columnDefinition = "jsonb")
    private JsonNode deliveredPlacements;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "write_started", nullable = false)
    private Boolean writeStarted;

    /**
     * When a provider write of this package, summary or inline, may first have begun; what an unconfirmed write is
     * timed from. It says nothing about which stage began: {@link #writeStarted} is the summary's fact and
     * {@link #inlineWriteStarted} the inline notes'.
     */
    @Column(name = "write_started_at")
    private @Nullable Instant writeStartedAt;

    /**
     * Whether an inline note of this package may have been requested from the provider: set before the first inline
     * request leaves. Null on a dispatch recorded before this was tracked, where it is unknown.
     */
    @Column(name = "inline_write_started")
    private @Nullable Boolean inlineWriteStarted;

    /**
     * Whether an inline write may have begun. An untracked older dispatch may have, unless its package names no
     * inline notes at all; the rule {@code FeedbackDispatchRepository.INLINE_WRITE_MAY_HAVE_STARTED} reads the same.
     */
    public boolean inlineWriteMayHaveStarted() {
        if (inlineWriteStarted != null) {
            return inlineWriteStarted;
        }
        JsonNode notes = packageContent.get("diffNotes");
        return notes == null || !notes.isArray() || !notes.isEmpty();
    }

    @Column(name = "delivered_external_ref", length = 255)
    private @Nullable String deliveredExternalRef;

    @Column(name = "lease_owner", length = 64)
    private @Nullable String leaseOwner;

    @Column(name = "lease_expires_at")
    private @Nullable Instant leaseExpiresAt;

    @NotNull
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @NotNull
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "suppression_reason", length = 48)
    private @Nullable String suppressionReason;

    @Column(name = "last_error", length = 512)
    private @Nullable String lastError;

    @Column(name = "projected_at")
    private @Nullable Instant projectedAt;

    @Column(name = "projection_owner", length = 64)
    private @Nullable String projectionOwner;

    @Column(name = "projection_expires_at")
    private @Nullable Instant projectionExpiresAt;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
