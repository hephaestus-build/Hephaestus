package de.tum.cit.aet.hephaestus.practices.feedback;

/**
 * The granularity of a diff anchor for an {@link PlacementType#INLINE} or {@link PlacementType#LOCATION_COMMENT}
 * placement.
 *
 * <ul>
 *   <li>{@link #LINE} — a single line.</li>
 *   <li>{@link #RANGE} — a contiguous span of lines (start + end).</li>
 *   <li>{@link #FILE} — file-level, no line anchor.</li>
 *   <li>{@link #IMAGE} — an image/binary file region.</li>
 * </ul>
 */
public enum PlacementAnchorKind {
    /** A single line. */
    LINE,
    /** A contiguous span of lines. */
    RANGE,
    /** File-level, no line anchor. */
    FILE,
    /** An image/binary file region. */
    IMAGE,
}
