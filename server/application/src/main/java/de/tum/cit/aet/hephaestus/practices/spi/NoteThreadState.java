package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * The provider thread of a line note or file note, as Hephaestus's mirror of the provider last recorded it. The mirror
 * follows the provider's thread events and syncs, so it can lag behind the provider.
 */
public enum NoteThreadState {
    /** The mirror holds the thread, unresolved. */
    OPEN,
    /** The mirror holds the thread, resolved by a person or by Hephaestus. */
    RESOLVED,
    /** The mirror holds no thread for the note. */
    UNKNOWN
}
