package de.tum.cit.aet.hephaestus.practices.observation.trend;

/** Evidence-bounded direction of change for a practice or practice group. */
public enum TrendDirection {
    IMPROVING,
    DECLINING,
    UNCERTAIN,
    INSUFFICIENT_EVIDENCE;

    /**
     * Whether this direction points somewhere, rather than being one of the two ways of saying the evidence
     * does not: {@code UNCERTAIN} compared the two stretches and could not call it, {@code INSUFFICIENT_EVIDENCE}
     * had too little to compare.
     */
    public boolean isDirectional() {
        return this == IMPROVING || this == DECLINING;
    }
}
