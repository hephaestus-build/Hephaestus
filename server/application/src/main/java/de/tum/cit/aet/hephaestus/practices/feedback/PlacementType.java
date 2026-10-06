package de.tum.cit.aet.hephaestus.practices.feedback;

/**
 * The delivery-surface slot a {@code FeedbackPlacement} occupies — the axis of <em>where</em> a
 * feedback unit renders. Persisted as the NOT NULL {@code placement_type}, value-constrained by
 * {@code chk_feedback_placement_placement}.
 *
 * <ul>
 *   <li>{@link #SUMMARY} — the PR/MR/issue summary body (one per feedback unit, typically).</li>
 *   <li>{@link #INLINE} — anchored to a specific diff location (file + line/range).</li>
 *   <li>{@link #CONVERSATION_TURN} — a turn in a mentor conversation thread.</li>
 *   <li>{@link #LOCATION_COMMENT} — an ordinary comment on the work that links to a file + line/range.</li>
 * </ul>
 *
 * <p>A proposal is always placed {@link #INLINE}; {@link #LOCATION_COMMENT} records only where a delivered copy
 * actually appeared.
 */
public enum PlacementType {
    /** Rendered in the PR/MR/issue summary body. */
    SUMMARY,
    /** Anchored to a specific diff location. */
    INLINE,
    /** A turn in a conversation thread. */
    CONVERSATION_TURN,
    /**
     * An ordinary comment on the work, attached to no line, that links to its file and lines at the reviewed
     * commit. Carries the same anchor coordinates as {@link #INLINE}.
     */
    LOCATION_COMMENT,
}
