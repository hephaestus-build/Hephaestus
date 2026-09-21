package de.tum.cit.aet.hephaestus.practices.feedback;

/** What the recipient decided to do with a delivered piece of feedback. */
public enum FeedbackResolution {
    /** The recipient acted, or intends to act, on the guidance. */
    ADDRESSED,
    /** The recipient rejects the observation with a reasoned explanation. */
    DISPUTED,
    /** The observation may be sound but does not apply in this context. */
    NOT_APPLICABLE;

    /**
     * Whether this answer resolves the feedback — the developer's own way to close a card, beside the work
     * coming back clean ({@code docs/contributor/practice-review-glossary.mdx} § How feedback resolves).
     * Acting on it or ruling it out both close it; a dispute asks for a reply and leaves it open.
     */
    public boolean resolves() {
        return this != DISPUTED;
    }
}
