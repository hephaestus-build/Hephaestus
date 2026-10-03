package de.tum.cit.aet.hephaestus.practices.feedback.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

@Entity
@Immutable
@Table(name = "feedback_approval")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackApproval {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "feedback_id", nullable = false, unique = true, columnDefinition = "UUID")
    private UUID feedbackId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "actor_account_id")
    private @Nullable Long actorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private FeedbackApprovalDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 32)
    private FeedbackRejectionReason rejectionReason;

    @Column(name = "rejection_note", length = 500)
    private @Nullable String rejectionNote;

    @Column(name = "content_digest", nullable = false, length = 64)
    private String contentDigest;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (decidedAt == null) decidedAt = Instant.now();
    }
}
