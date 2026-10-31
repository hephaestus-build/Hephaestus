package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * How an occasion of one event name reaches its readiness edge, the moment the server derives which practices run
 * (ADR 0069). The item kind owns it, so practice authors only choose which events a practice reviews on.
 */
public enum OccasionKind {
    /** The first or a renewed moment for feedback: it joins the quiet window and measures every practice it selects. */
    MILESTONE,
    /** It joins the quiet window and measures only the practices it selects that have no current result. */
    CONTINUING,
    /** It never joins a window. It is a readiness edge at once and measures as a continuing occasion does. */
    IMMEDIATE;

    public boolean joinsWindow() {
        return this != IMMEDIATE;
    }
}
