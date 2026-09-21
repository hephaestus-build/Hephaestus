package de.tum.cit.aet.hephaestus.practices.observation.reaction;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of a developer's response to delivered {@link Feedback}. The newest row is the current
 * response; a row with neither optional dimension is a deletion marker. The legacy table name is retained for
 * migration compatibility.
 */
@Entity
@Immutable
@Table(
        name = "reaction",
        indexes = {
            @Index(name = "idx_reaction_reactor_created", columnList = "reactor_user_id, created_at DESC"),
            @Index(
                    name = "idx_reaction_feedback_reactor",
                    columnList = "feedback_id, reactor_user_id, created_at DESC"),
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reaction {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feedback_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reaction_feedback"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Feedback feedback;

    @Column(name = "feedback_id", nullable = false, insertable = false, updatable = false, columnDefinition = "UUID")
    private UUID feedbackId;

    @NotNull
    @Column(name = "reactor_user_id", nullable = false)
    private Long reactorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "usefulness", length = 16)
    private @Nullable FeedbackUsefulness usefulness;

    /** What the recipient decided to do. The legacy column name is mapped at the persistence boundary. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16)
    private @Nullable FeedbackResolution resolution;

    /** Optional explanation; the database requires one for {@link FeedbackResolution#DISPUTED}. */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    /** Read-only mapping of a historical column; response decisions use exact bound observations. */
    @Deprecated(forRemoval = true)
    @Getter(AccessLevel.NONE)
    @Column(name = "recurrence_key", length = 64, insertable = false, updatable = false)
    private String recurrenceKey;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
