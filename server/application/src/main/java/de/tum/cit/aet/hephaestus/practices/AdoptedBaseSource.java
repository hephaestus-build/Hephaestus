package de.tum.cit.aet.hephaestus.practices;

/** How much of an adopted base can be established for a definition created before snapshots existed. */
public enum AdoptedBaseSource {
    EXACT_ADOPTION,
    BUNDLED_DIGEST_MATCH,
    BUNDLED_FINGERPRINT_MATCH,
    CURRENT_DEFINITION
}
