package de.tum.cit.aet.hephaestus.practices.review.model;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serializable;
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

@Entity
@RawObservationAccess
@Table(
        name = "review_practice",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_review_practice_tenant",
                    columnNames = {"review_id", "practice_id", "workspace_id"})
        })
@IdClass(ReviewPractice.Id.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewPractice {

    @jakarta.persistence.Id
    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @jakarta.persistence.Id
    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "practice_version_id")
    private @Nullable Long practiceVersionId;

    @Default
    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'QUEUED'")
    @Column(name = "status", nullable = false, length = 16)
    private ReviewPracticeStatus status = ReviewPracticeStatus.QUEUED;

    @RawObservationAccess
    @Column(name = "reason", length = 48)
    private @Nullable String reason;

    @Column(name = "items_listed")
    private @Nullable Integer itemsListed;

    @Column(name = "listing_complete")
    private @Nullable Boolean listingComplete;

    @RawObservationAccess
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capture_decision", columnDefinition = "jsonb")
    private @Nullable JsonNode captureDecision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "method", columnDefinition = "jsonb")
    private @Nullable ReviewMethod method;

    /**
     * The practice's effective feedback mode when its first observation of this review was admitted. Standing reads it,
     * so an observation sealed in {@link FeedbackMode#OFF} never counts, even after the practice goes live (ADR 0118).
     * Null until an observation is admitted.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sealed_feedback_mode", length = 16)
    private @Nullable FeedbackMode sealedFeedbackMode;

    /** Capture decided the practice: Ready to run, or Withheld or Failed with its reason. */
    public void decideCapture(ReviewPracticeStatus decision, @Nullable String decisionReason) {
        if (status == decision) return;
        move(decision);
        reason = decisionReason;
    }

    public void start() {
        move(ReviewPracticeStatus.RUNNING);
    }

    public void finish() {
        move(ReviewPracticeStatus.DONE);
    }

    /** Withholds the practice. One that is withheld already keeps the reason it was withheld for first. */
    public void withhold(String withheldReason) {
        if (status == ReviewPracticeStatus.WITHHELD) return;
        move(ReviewPracticeStatus.WITHHELD);
        reason = withheldReason;
    }

    /**
     * A move to the state the practice is in changes nothing and keeps its first reason: closure and capture may both
     * reach a practice that an earlier stage already decided. A review job's move refuses that instead.
     */
    private void move(ReviewPracticeStatus next) {
        if (status == next) return;
        if (!status.canTransitionTo(next))
            throw new IllegalStateException("A " + status + " practice cannot become " + next + ".");
        status = next;
    }

    public record Id(UUID reviewId, long practiceId) implements Serializable {}
}
