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
import tools.jackson.databind.node.ObjectNode;

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
    /** History only: several migrated results for one assignment, and none chosen. No code writes it. */
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
    @Setter(AccessLevel.NONE)
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

    /** The attempt holds a candidate result for this membership until admission decides. */
    public void stage() {
        moveTo(ReviewItemState.STAGED);
    }

    /** Admission wrote this membership's result. */
    public void judge(UUID resultId) {
        moveTo(ReviewItemState.JUDGED);
        this.resultId = resultId;
    }

    /** An earlier result of the same frozen item and practice answers this membership. */
    public void reuse(UUID resultId) {
        moveTo(ReviewItemState.REUSED);
        this.resultId = resultId;
    }

    /** Ends this membership without a result, for a recorded reason. */
    public void withhold(String reason) {
        moveTo(ReviewItemState.WITHHELD);
        this.reason = reason;
    }

    /** Ends this membership without a result, for a recorded reason that the message explains. */
    public void withhold(String reason, String message, Instant at) {
        withhold(reason);
        var kept = detail instanceof ObjectNode node ? node.deepCopy() : JsonNodeFactory.instance.objectNode();
        kept.put("withholdingMessage", message);
        detail = kept;
        attemptedAt = at;
    }

    private void moveTo(ReviewItemState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("A review item cannot move from " + state + " to " + next);
        }
        state = next;
    }

    public record Id(UUID reviewId, long practiceId, long itemId) implements Serializable {}
}
