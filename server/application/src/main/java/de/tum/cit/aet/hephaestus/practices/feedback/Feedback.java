package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Immutable
@Table(
        name = "feedback",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_feedback_unit",
                    columnNames = {"agent_job_id", "position"}),
            @UniqueConstraint(
                    name = "uk_feedback_workspace_id",
                    columnNames = {"workspace_id", "id"}),
        },
        indexes = {
            @Index(name = "idx_feedback_agent_job", columnList = "agent_job_id"),
            @Index(name = "idx_feedback_workspace", columnList = "workspace_id"),
            @Index(name = "idx_feedback_workspace_created", columnList = "workspace_id, created_at DESC, id DESC"),
            @Index(name = "idx_feedback_recipient_created", columnList = "recipient_user_id, created_at DESC"),
            @Index(name = "idx_feedback_target", columnList = "artifact_kind, artifact_id"),
            @Index(name = "idx_feedback_continuity", columnList = "thread_key"),
            @Index(name = "idx_feedback_replaces", columnList = "replaces_id"),
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotNull
    @Column(name = "agent_job_id", nullable = false, columnDefinition = "UUID")
    private UUID agentJobId;

    @NotNull
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "artifact_kind", length = ArtifactKind.MAX_LENGTH)
    private ArtifactKind artifactKind;

    @Column(name = "artifact_id")
    private Long artifactId;

    @NotNull
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    /** The person whose work the message addresses; may be the recipient. */
    @NotNull
    @Column(name = "about_user_id", nullable = false)
    private Long aboutUserId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private FeedbackChannel channel;

    @NotNull
    @Column(name = "position", nullable = false)
    private Integer position;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false, length = 32)
    private FeedbackDeliveryState deliveryState;

    @Enumerated(EnumType.STRING)
    @Column(name = "suppression_reason", length = 32)
    private @Nullable FeedbackSuppressionReason suppressionReason;

    @Column(name = "body", columnDefinition = "TEXT")
    private @Nullable String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_placements", nullable = false, columnDefinition = "jsonb")
    @Default
    private List<ProposedPlacement> proposedPlacements = List.of();

    @Column(name = "reviewed_revision", length = 64)
    private @Nullable String reviewedRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_practice_slugs", nullable = false, columnDefinition = "jsonb")
    @Default
    private List<String> proposedPracticeSlugs = List.of();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private FeedbackSource source;

    @Column(name = "replaces_id", columnDefinition = "UUID")
    private @Nullable UUID replacesId;

    /** Cross-run identity for successive versions of the same feedback. */
    @Column(name = "thread_key", length = 64)
    private @Nullable String threadKey;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
