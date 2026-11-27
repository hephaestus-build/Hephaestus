package de.tum.cit.aet.hephaestus.practices.spi;

/** Which producer a sealed review hands its outcomes to, chosen once at seal (ADR 0160). */
public enum FeedbackProducer {
    /** The review composes feedback as before the mentor. */
    LEGACY,
    /** The mentor decides the work comment, the advice and any contact. */
    MENTOR,
    /**
     * Only the work comment, never advice or contact. The seal records why, for example an unlinked or
     * non-consenting author, or an owner binding that an erasure or a write fence refused.
     */
    WORK_PLACE,
    /**
     * The team's mentor composes the feedback for a team-owned item (ADR 0162 decision 3). Only the team's current
     * members read it.
     */
    TEAM,
    /**
     * Only the sealed observations, with no composed feedback. The seal records why, for example a current member whose
     * AI choice does not admit the mentor.
     */
    OBSERVATIONS
}
