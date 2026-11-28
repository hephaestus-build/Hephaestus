package de.tum.cit.aet.hephaestus.practices.spi;

/** What a review pinned (ADR 0167 decision 2). */
public enum ReviewVersionKind {
    /** A repository at a commit, with the evidence pinned beside it. A scan reviews one. */
    REPOSITORY_COMMIT,
    /** An immutable version that a team submitted (ADR 0165). */
    SUBMITTED_VERSION,
    /** An item at a revision, such as a pull request, an issue, a conversation thread or a document. */
    ITEM_REVISION;

    /**
     * The kind of a review that names no other: a review created before reviews stored one, and a new review that no
     * submitted version starts.
     */
    public static ReviewVersionKind ofOlder(ReviewKind kind) {
        return kind == ReviewKind.SCAN ? REPOSITORY_COMMIT : ITEM_REVISION;
    }
}
