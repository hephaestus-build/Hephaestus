package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * The relation a person can have to an artifact, and therefore the relations an observation about
 * that artifact may be attributed to.
 *
 * <p>Core owns this enum because consent and delivery depend on it: feedback is always addressed to a
 * developer in one of these relations, never to a mentor or grader. An artifact kind that cannot
 * identify a role must not declare it — an attribution to a role we cannot resolve is an observation
 * with nobody to deliver it to.
 */
public enum ActorRole {
    /** Produced the artifact. */
    AUTHOR,

    /**
     * Holds the artifact. This is the relation the review gate checks a permission against, so a kind
     * with no notion of assignment cannot be gated that way.
     */
    ASSIGNEE,

    /** Responded to the artifact — the only relation a practice about reviewing can be about. */
    REVIEWER,
    /**
     * Landed the artifact — the relation a practice about the act of merging is about. A review is
     * filed against one person; a MERGER practice is reviewed in an author's review when the author is
     * the one who merged, and withheld otherwise, so a lapse at the merge is never filed against someone
     * who did not merge.
     */
    MERGER,
}
