package de.tum.cit.aet.hephaestus.evidence;

/**
 * Result of evaluating one precondition clause. {@link #NOT_FOUND} requires complete evidence;
 * {@link #UNDECIDABLE} keeps the practice eligible.
 */
public enum PracticePreconditionResult {
    /** The subject is in the work; the practice is asked. */
    FOUND,

    /** The subject is provably not in the work. */
    NOT_FOUND,

    /** Nothing was established either way. */
    UNDECIDABLE,
}
