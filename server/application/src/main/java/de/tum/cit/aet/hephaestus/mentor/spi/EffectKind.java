package de.tum.cit.aet.hephaestus.mentor.spi;

/** What an effect writes. A request has at most one terminal effect: an answer, an app continuation or a failure. */
public enum EffectKind {
    /** The fixed line that the request was received. */
    RECEIPT,
    /** The fixed retention and export notice, once per person and binding. */
    RETENTION_NOTICE,
    /** The substantive answer, with the fixed app continuation in the same post when one is eligible. */
    ANSWER,
    /** The fixed app-only pointer. */
    CONTINUATION,
    /** The fixed line that the request ended without an answer. */
    TERMINAL_FAILURE;

    /** Whether the effect ends its request. */
    public boolean terminal() {
        return this == ANSWER || this == CONTINUATION || this == TERMINAL_FAILURE;
    }
}
