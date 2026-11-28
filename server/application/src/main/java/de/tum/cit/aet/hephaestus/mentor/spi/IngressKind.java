package de.tum.cit.aet.hephaestus.mentor.spi;

/**
 * What an admitted mailbox event is. A verified provider event is a message, an edit, a delete or a stop, and only a new
 * message opens a request. {@link #REVIEW_SEALED} comes from a seal, never from a provider: it is an outcome to act
 * on, and it is never coalesced (ADR 0158 decision 7).
 */
public enum IngressKind {
    MESSAGE,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    CONTROL_STOP,
    REVIEW_SEALED
}
