package de.tum.cit.aet.hephaestus.practices.spi;

/** Why an advice revision lost its text. The conversation side decides per cause whether answers that quoted it go. */
public enum AdviceStaleCause {
    /** A person's erasure deleted the evidence. */
    ERASURE,
    /** A source withdrawal (a disconnect, an uninstall or a channel erasure) deleted the evidence. */
    WITHDRAWAL,
    /** The evidence reached the end of its retention. An answer keeps its own retention (A2), so it does not go. */
    RETENTION
}
