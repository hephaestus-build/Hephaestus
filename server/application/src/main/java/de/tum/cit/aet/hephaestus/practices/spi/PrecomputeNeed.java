package de.tum.cit.aet.hephaestus.practices.spi;

/** How much a precompute script needs one model, as its {@code meta.models} declares it. */
public enum PrecomputeNeed {
    /** Without a bound model the script does not run. */
    REQUIRED,
    /** The script runs without the model and reports what it could not rate. */
    OPTIONAL
}
