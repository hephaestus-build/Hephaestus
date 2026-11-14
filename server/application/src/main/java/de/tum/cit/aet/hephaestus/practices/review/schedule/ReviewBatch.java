package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
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
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "review_batch",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_review_batch_workspace_id",
                    columnNames = {"id", "workspace_id"})
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewBatch {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Default
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private long version = 0L;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'BACKFILL'")
    @Column(name = "origin", nullable = false, length = 16)
    private ReviewBatchOrigin origin = ReviewBatchOrigin.BACKFILL;

    @Column(name = "schedule_id")
    private @Nullable UUID scheduleId;

    @Column(name = "from_at")
    private @Nullable Instant fromAt;

    @Column(name = "to_at")
    private @Nullable Instant toAt;

    @Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'AWAITING_CONFIRMATION'")
    @Setter(AccessLevel.NONE)
    @Column(name = "status", nullable = false, length = 24)
    private ReviewBatchStatus status = ReviewBatchStatus.AWAITING_CONFIRMATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 32)
    private @Nullable ReviewBatchPauseReason pauseReason;

    @Column(name = "budget_usd", precision = 18, scale = 6)
    private @Nullable BigDecimal budgetUsd;

    @Default
    @ColumnDefault("0")
    @Column(name = "estimated_items", nullable = false)
    private int estimatedItems = 0;

    @Column(name = "estimated_cost_usd", precision = 12, scale = 4)
    private @Nullable BigDecimal estimatedCostUsd;

    @Column(name = "cursor_item_id")
    private @Nullable Long cursorItemId;

    @Default
    @ColumnDefault("0")
    @Column(name = "submitted_count", nullable = false)
    private int submittedCount = 0;

    @Default
    @ColumnDefault("0")
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount = 0;

    @Default
    @ColumnDefault("0")
    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    @Column(name = "requested_by_account_id")
    private @Nullable Long requestedByAccountId;

    @Column(name = "confirmed_by_account_id")
    private @Nullable Long confirmedByAccountId;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Offers work again: an admin's confirmation or resume, or a driver tick that finds nothing in the way. */
    public void resume() {
        moveTo(ReviewBatchStatus.RUNNING);
        pauseReason = null;
    }

    public void pause(ReviewBatchPauseReason reason) {
        moveTo(ReviewBatchStatus.PAUSED);
        pauseReason = reason;
    }

    /** Every selected target was offered. */
    public void complete(Instant now) {
        end(ReviewBatchStatus.COMPLETED, now);
    }

    /** A scheduled batch spent its verified fixed cap: a normal end, not a pause. */
    public void exhaustBudget(Instant now) {
        end(ReviewBatchStatus.BUDGET_EXHAUSTED, now);
    }

    public void cancel(Instant now) {
        end(ReviewBatchStatus.CANCELLED, now);
    }

    private void end(ReviewBatchStatus terminal, Instant now) {
        moveTo(terminal);
        pauseReason = null;
        finishedAt = now;
    }

    private void moveTo(ReviewBatchStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("A review batch cannot move from " + status + " to " + next);
        }
        status = next;
    }
}
