package de.tum.cit.aet.hephaestus.practices.review.model;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Entity
@Table(
        name = "review",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_review_request",
                    columnNames = {"workspace_id", "request_key"}),
            @UniqueConstraint(
                    name = "uk_review_workspace",
                    columnNames = {"id", "workspace_id"})
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    @Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ITEM'")
    @Column(name = "kind", nullable = false, length = 8)
    private ReviewKind kind = ReviewKind.ITEM;

    @Column(name = "item_version", columnDefinition = "TEXT")
    private @Nullable String itemVersion;

    @Column(name = "base_version", columnDefinition = "TEXT")
    private @Nullable String baseVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private ReviewOrigin origin;

    @Column(name = "event_id")
    private @Nullable UUID eventId;

    @Column(name = "batch_id")
    private @Nullable UUID batchId;

    @Column(name = "requested_by_id")
    private @Nullable Long requestedById;

    @Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'QUEUED'")
    @Column(name = "status", nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 48)
    private @Nullable ReviewStopReason stopReason;

    @Column(name = "budget_usd", precision = 18, scale = 6)
    private @Nullable BigDecimal budgetUsd;

    @Default
    @ColumnDefault("0")
    @Column(name = "rollout_revision", nullable = false)
    private long rolloutRevision = 0L;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "admitted_at")
    private @Nullable Instant admittedAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Column(name = "public_review_at")
    private @Nullable Instant publicReviewAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report", columnDefinition = "jsonb")
    private @Nullable JsonNode report;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inventory_receipt", columnDefinition = "jsonb")
    private @Nullable JsonNode inventoryReceipt;

    @Column(name = "request_key")
    private @Nullable UUID requestKey;

    @Column(name = "request_digest", length = 64)
    private @Nullable String requestDigest;

    /** Someone asked to cancel the review; it ends CANCELLED once no invocation of it runs. */
    @Default
    @ColumnDefault("false")
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested = false;

    @Default
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("'{}'")
    @Column(name = "checks", nullable = false, columnDefinition = "jsonb")
    private JsonNode checks = JsonNodeFactory.instance.objectNode();
}
