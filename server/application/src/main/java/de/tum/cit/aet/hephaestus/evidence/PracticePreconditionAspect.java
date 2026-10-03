package de.tum.cit.aet.hephaestus.evidence;

/** Mechanically decidable facts supported by practice preconditions. */
public enum PracticePreconditionAspect {
    /** At least one path the change touches matches one of the declared globs. */
    CHANGED_PATH,

    /** The change's diff contains one of the declared literal strings, on any side of any hunk. */
    DIFF_TEXT,

    /** A named collection inside one captured source holds at least one entry. */
    EVIDENCE_ITEMS,
}
