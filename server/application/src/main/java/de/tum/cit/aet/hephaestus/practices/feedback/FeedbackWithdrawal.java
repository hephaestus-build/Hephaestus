package de.tum.cit.aet.hephaestus.practices.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A card taken off a developer's practice page because its words were wrong while its observations stay valid; the
 * feedback row itself never changes. Restoring completes this row rather than deleting it, and at most one per
 * feedback is open ({@code uk_feedback_withdrawal_active}).
 */
@Entity
@Table(
        name = "feedback_withdrawal",
        indexes = {@Index(name = "idx_feedback_withdrawal_feedback", columnList = "feedback_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedbackWithdrawal {

    public static final int MAX_REASON_LENGTH = 500;

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** Keyed with the workspace by {@code sfk_feedback_withdrawal_feedback}, so it can only name its own feedback. */
    @Column(name = "feedback_id", nullable = false, columnDefinition = "UUID")
    private UUID feedbackId;

    @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "withdrawn_by_account_id", nullable = false)
    private Long withdrawnByAccountId;

    @Column(name = "withdrawn_at", nullable = false)
    private Instant withdrawnAt;

    @Column(name = "restoration_reason", length = MAX_REASON_LENGTH)
    private @Nullable String restorationReason;

    @Column(name = "restored_by_account_id")
    private @Nullable Long restoredByAccountId;

    @Column(name = "restored_at")
    private @Nullable Instant restoredAt;

    public FeedbackWithdrawal(Feedback feedback, long accountId, String reason, Instant at) {
        this.id = UUID.randomUUID();
        this.workspaceId = feedback.getWorkspaceId();
        this.feedbackId = feedback.getId();
        this.reason = reason;
        this.withdrawnByAccountId = accountId;
        this.withdrawnAt = at;
    }

    public void restore(long accountId, String reason, Instant at) {
        this.restorationReason = reason;
        this.restoredByAccountId = accountId;
        this.restoredAt = at;
    }
}
