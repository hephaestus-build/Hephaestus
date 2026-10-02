package de.tum.cit.aet.hephaestus.practices.feedback;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * Immutable record of one concrete placement of a synthesized {@code Feedback} unit on a
 * delivery surface — a summary block, an inline diff anchor, or a conversation turn.
 *
 * <p>A single feedback unit can have multiple placements (1:N): for example a SUMMARY block
 * plus several INLINE anchors. Each placement carries the diff-anchor coordinates (path, line,
 * side) and the external comment/note id so re-delivery can be reconciled per anchor.
 *
 * <p>Append-only / {@code @Immutable}: posting outcomes that change over time are represented by
 * writing a new placement row rather than mutating an existing one through the ORM. This is the
 * <em>selector</em> edge (where a body of commentary is anchored), orthogonal to the
 * {@link FeedbackObservation} <em>target</em> edge.
 *
 * <p>The diff-anchor columns are a coupled group: populated only for {@code INLINE} placements, so all
 * are nullable and NULL means "this surface carries no diff coordinate".
 *
 * @see Feedback for the feedback unit being placed
 * @see PlacementType for SUMMARY/INLINE/CONVERSATION_TURN
 */
@Entity
@Immutable
@Table(
        name = "feedback_placement",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_feedback_placement_feedback_ref",
                        columnNames = {"feedback_id", "posted_comment_ref"}),
        indexes = {
            @Index(name = "idx_feedback_placement_feedback", columnList = "feedback_id"),
            @Index(name = "idx_feedback_placement_external_ref", columnList = "posted_comment_ref"),
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackPlacement {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * The feedback unit this placement renders. Uses DB-level {@code ON DELETE CASCADE} so
     * deleting a feedback unit cleans up its immutable placement rows.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "feedback_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_feedback_placement_feedback"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Feedback feedback;

    /**
     * Direct access to the feedback ID without triggering a lazy load on the {@link #feedback} proxy.
     * Read-only: mapped to the same column as the {@code @ManyToOne} relationship.
     *
     * @implNote {@code insertable=false/updatable=false}: a builder-set {@code .feedbackId(...)} is NOT
     *     persisted. Callers MUST set {@link #feedback}; a row built with only {@code feedbackId} would
     *     persist a NULL {@code feedback_id} and violate the NOT NULL FK.
     */
    @Column(name = "feedback_id", nullable = false, insertable = false, updatable = false, columnDefinition = "UUID")
    private UUID feedbackId;

    /** Where this placement renders: SUMMARY, INLINE, or CONVERSATION_TURN. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "placement_type", length = 32, nullable = false)
    private PlacementType placementType;

    // --- Diff anchor coordinates (all nullable: only INLINE placements anchor to a diff) ---

    /** Granularity of the anchor: LINE / RANGE / FILE / IMAGE. NULL for non-INLINE placements. */
    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_kind", length = 16)
    private PlacementAnchorKind anchorKind;

    /** Head-side path of the anchored file. NULL when the placement carries no diff coordinate. */
    @Column(name = "anchor_path", columnDefinition = "TEXT")
    private String anchorPath;

    /** First anchored line (1-based). */
    @Column(name = "anchor_start_line")
    private Integer anchorStartLine;

    /** Last anchored line (1-based); equals start for a single-line anchor. */
    @Column(name = "anchor_end_line")
    private @Nullable Integer anchorEndLine;

    /**
     * Diff side of the anchor end / single line (OLD or NEW). Writers emit {@code NEW} (head side);
     * the column carries OLD/NEW to support reviewer-side / binary anchors.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_side", length = 8)
    private PlacementAnchorSide anchorSide;

    // --- External delivery reconciliation ---

    /**
     * Channel-native id of the posted comment/note for this placement (one per row — never one
     * overloaded scalar per unit). NULL when the placement was not posted to an external surface.
     * Indexed for reconciling a posted id back to its placement.
     */
    @Column(name = "posted_comment_ref", columnDefinition = "TEXT")
    private @Nullable String postedCommentRef;

    /** Provider-returned permalink; absent for deliveries recorded before URL capture. */
    @Column(name = "posted_comment_url", columnDefinition = "TEXT")
    private @Nullable String postedCommentUrl;

    /**
     * Link to the mentor assistant {@code chat_message} that delivered this placement — set only for a
     * {@code CONVERSATION_TURN} placement, NULL otherwise. Kept as a raw scalar (not a {@code @ManyToOne})
     * because {@code chat_message} lives in the {@code mentor} module; the DB carries a scalar FK
     * (ADR 0017 {@code sfk_*} naming) with {@code ON DELETE SET NULL}, so the placement survives the
     * message being deleted.
     */
    @Column(name = "chat_message_id", columnDefinition = "UUID")
    private UUID chatMessageId;

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
