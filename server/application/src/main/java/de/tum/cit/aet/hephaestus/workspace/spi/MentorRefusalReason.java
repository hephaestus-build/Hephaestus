package de.tum.cit.aet.hephaestus.workspace.spi;

/** Why Heph declines a member in a workspace; Heph's admission words each one for the member. */
public enum MentorRefusalReason {
    /** The member's personal data was erased, so Heph does not process it. */
    PERSON_ERASED,
    /** The member chose No AI. */
    NO_AI,
    /** The workspace asks for an AI choice, and the member has not made one. */
    CHOICE_REQUIRED,
    /** No ready Heph model fits the member's AI choice, or the absence of one. */
    UNAVAILABLE
}
