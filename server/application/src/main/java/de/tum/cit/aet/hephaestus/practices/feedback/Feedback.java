package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackChecks;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
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
        name = "feedback",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_feedback_workspace_id",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_feedback_developer",
                    columnNames = {"id", "developer_id", "workspace_id"})
        },
        indexes = {
            @Index(
                    name = "idx_feedback_recipient",
                    columnList = "workspace_id, developer_id, destination, created_at DESC, id DESC"),
            @Index(name = "idx_feedback_review", columnList = "workspace_id, review_id, destination, developer_id")
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "review_id", nullable = false, columnDefinition = "UUID")
    private UUID reviewId;

    @Column(name = "item_id", nullable = true)
    private @Nullable Long itemId;

    @Column(name = "developer_id", nullable = false)
    private long developerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false, length = 24)
    private FeedbackDestination destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private FeedbackState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", nullable = true, length = 48)
    private @Nullable FeedbackStopReason stopReason;

    @Column(name = "body", nullable = true, columnDefinition = "TEXT")
    private @Nullable String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Default
    @ColumnDefault("'{}'")
    @Column(name = "checks", nullable = false, columnDefinition = "jsonb")
    @Setter(AccessLevel.PACKAGE)
    private Map<FeedbackCheckStage, FeedbackChecks> checks = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Default
    @ColumnDefault("'{}'")
    @Column(name = "composition", nullable = false, columnDefinition = "jsonb")
    @Setter(AccessLevel.PACKAGE)
    private JsonNode composition = JsonNodeFactory.instance.objectNode();

    @Column(name = "replaces_id", nullable = true, columnDefinition = "UUID")
    private @Nullable UUID replacesId;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at", nullable = true)
    private @Nullable Instant deliveredAt;
}
