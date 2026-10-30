package de.tum.cit.aet.hephaestus.practices.review.model;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serializable;
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

@Entity
@Table(
        name = "review_item",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_review_item_workspace",
                    columnNames = {"review_id", "practice_id", "item_id", "workspace_id"})
        })
@IdClass(ReviewItem.Id.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewItem {
    public static final String LEGACY_AMBIGUOUS_RESULTS = "LEGACY_AMBIGUOUS_RESULTS";

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @jakarta.persistence.Id
    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @jakarta.persistence.Id
    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @jakarta.persistence.Id
    @Column(name = "item_id", nullable = false)
    private long itemId;

    @Column(name = "item_version", columnDefinition = "TEXT")
    private @Nullable String itemVersion;

    @Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'LISTED'")
    @Column(name = "state", nullable = false, length = 16)
    private ReviewItemState state = ReviewItemState.LISTED;

    @Column(name = "result_id")
    private @Nullable UUID resultId;

    @Column(name = "job_id")
    private @Nullable UUID jobId;

    @Column(name = "attempted_at")
    private @Nullable Instant attemptedAt;

    @Column(name = "reason", length = 128)
    private @Nullable String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private @Nullable JsonNode detail;

    @Column(name = "staged_attempt")
    private @Nullable Integer stagedAttempt;

    public record Id(UUID reviewId, long practiceId, long itemId) implements Serializable {}
}
