package de.tum.cit.aet.hephaestus.mentor.spi;

/** What a verified provider event is. Only a new message opens a request. */
public enum IngressKind {
    MESSAGE,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    CONTROL_STOP
}
