package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * Whether the text that an earlier citation quotes is still in the work, as the capture of a newer review holds it. Only
 * a capture that holds the cited source whole, with the cited path in it, can say {@link Presence#GONE}: anything it
 * did not capture shows nothing.
 */
@FunctionalInterface
public interface CapturedTextProbe {
    /** Knows no capture: every quote is {@link Presence#UNKNOWN}. */
    CapturedTextProbe NONE = cited -> Presence.UNKNOWN;

    Presence presence(EvidenceCitation cited);

    enum Presence {
        /** The quoted text is still in the cited source. */
        PRESENT,
        /** The cited path is in the capture, and the quoted text is in none of the source's texts. */
        GONE,
        /** The capture cannot say: the source or the path is not in it, or it was cut. */
        UNKNOWN
    }
}
