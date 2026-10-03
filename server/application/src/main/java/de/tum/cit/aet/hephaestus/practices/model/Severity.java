package de.tum.cit.aet.hephaestus.practices.model;

/** Impact of a NOT_MET outcome. Other outcomes carry no severity. */
public enum Severity {
    /** Must be acted on now — e.g. a leaked secret or a security vulnerability. */
    CRITICAL(0),
    /** A real problem that should be fixed before the work is considered done. */
    MAJOR(1),
    /** A minor issue or style nit; worth raising but not blocking. */
    MINOR(2),
    /** Informational only — a low-stakes note, no action expected. */
    INFO(3);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    /**
     * Position in severity order, {@code 0} for the most severe. Declared rather than taken from the
     * constant order, so reordering the constants cannot reorder feedback.
     */
    public int rank() {
        return rank;
    }
}
