package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * Whether a practice of a review reviews the item itself or each listed member of it (ADR 0167 decision 5). The
 * observations of one level never add up to an observation of the other.
 */
public enum PracticeLevel {
    /** The reviewed item itself: the repository at its commit, the submitted version or the pull request. */
    ITEM,
    /** Each listed member of the reviewed item, such as a file of the repository. */
    MEMBER;

    /** The level of a practice in a review created before practices stored one: a scan reviews files. */
    public static PracticeLevel ofOlder(ReviewKind kind) {
        return kind == ReviewKind.SCAN ? MEMBER : ITEM;
    }
}
