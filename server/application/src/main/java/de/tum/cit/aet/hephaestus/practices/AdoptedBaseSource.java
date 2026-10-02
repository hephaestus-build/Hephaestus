package de.tum.cit.aet.hephaestus.practices;

/** How much of an adopted base can be established for a definition created before snapshots existed. */
public enum AdoptedBaseSource {
    EXACT_ADOPTION,
    BUNDLED_DIGEST_MATCH,
    BUNDLED_FINGERPRINT_MATCH,
    /**
     * The earliest recorded revision of the copy matched by its saved source fingerprint, as that revision recorded
     * it; used when no bundled entry matches that fingerprint, as after a scheme change.
     */
    REVISION_FINGERPRINT_MATCH,
    CURRENT_DEFINITION
}
