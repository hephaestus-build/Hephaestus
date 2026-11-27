package de.tum.cit.aet.hephaestus.practices.spi;

/** Why an advice write wrote nothing (PRACTICE-ADVICE table A). The caller records each as a deliberate silence. */
public enum AdviceRefusal {
    /**
     * Advice writes are not set up: the workspace is not on the instance's advice allowlist, the review producer port
     * is absent, or the advice erasure listener is absent and the instance does not allow writes without it. Only a
     * synthetic proof environment allows that. Feedback mode Off is a {@link #GATE_REFUSED}.
     */
    NOT_ENABLED,
    /** The developer is erased, unclaimed, or no longer an active member. */
    OWNER_UNAVAILABLE,
    /** The review's producer is not the mentor. */
    NOT_MENTOR_REVIEW,
    /** This review already made another decision about this entry. */
    REVIEW_ALREADY_WROTE,
    /** The entry changed after the writer read it. Read again and decide again. */
    VERSION_MOVED,
    /** A reference is missing, belongs to someone else, or no citation is of this practice. */
    EVIDENCE_INELIGIBLE,
    /** The feedback gate refuses the citations, or a source is marked incorrect. */
    GATE_REFUSED,
    /**
     * The developer dismissed this advice, and no citation is of an item outside the dismissed items that was
     * observed after the dismissal.
     */
    OWNER_DISMISSED,
    /** The text is empty, too long, counts work or claims a pattern. */
    TEXT_INVALID
}
