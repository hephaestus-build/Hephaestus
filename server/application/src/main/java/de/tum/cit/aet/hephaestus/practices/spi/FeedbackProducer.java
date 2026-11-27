package de.tum.cit.aet.hephaestus.practices.spi;

/** Which producer a sealed review hands its outcomes to, chosen once at seal (ADR 0160). */
public enum FeedbackProducer {
    /** The review composes feedback as before the mentor. */
    LEGACY,
    /** The mentor decides the work comment, the advice and any contact. */
    MENTOR,
    /** Only the work comment: the author is unlinked or did not consent to the mentor. Never advice. */
    WORK_PLACE
}
