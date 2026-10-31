package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * Why Hephaestus processes nothing of one member's with AI in a workspace, for a conversation or for a practice
 * review. Each surface words the reason for the member.
 */
public enum AiRefusalReason {
    /** The member's personal data was erased, so Hephaestus does not process it. */
    PERSON_ERASED,
    /** The member chose No AI. */
    NO_AI,
    /** The workspace asks for an AI choice, and the member has not made one. */
    CHOICE_REQUIRED,
    /** No ready model fits the member's AI choice, or the absence of one. */
    UNAVAILABLE
}
