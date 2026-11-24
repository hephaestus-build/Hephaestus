package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * The kinds of record that {@code basis_edge} links, as a source or as a record derived from it (ADR 0128). A kind that
 * can be removed writes a {@code source_tombstone} row in the transaction that removes it. A new kind adds its constant
 * here, its value to the checks of {@code basis_edge} and {@code source_tombstone}, and its removal trigger, in one
 * change set.
 */
public enum BasisKind {
    /**
     * A {@code conversation_entry}. A redaction removes it: its trigger writes the tombstone. An entry whose row goes
     * takes its tombstone and the provenance references of its own basis with it.
     */
    ENTRY
}
