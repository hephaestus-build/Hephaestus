package de.tum.cit.aet.hephaestus.integration.core.spi;

/** Why an indexer refused a whole snapshot. A refusal is never an observation about the work. */
public enum SnapshotRefusal {
    UNSUPPORTED_VERSION,
    TOO_LARGE,
    /** A key that the supported version does not publish. */
    UNKNOWN_FIELD,
    /** Sound input of a type that the indexer does not read yet. */
    UNSUPPORTED_CONTENT,
    CONTAINS_ASSESSMENTS,
    CONTAINS_CREDENTIAL,
    MALFORMED
}
