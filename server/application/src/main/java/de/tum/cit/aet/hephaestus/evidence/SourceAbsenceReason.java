package de.tum.cit.aet.hephaestus.evidence;

/**
 * Why a source is not present in a capture — an availability state records <em>that</em> a source is absent,
 * this records <em>why</em>, which is what makes the absence actionable for an operator, an auditor, or the
 * review prompt.
 */
public enum SourceAbsenceReason {
    /** {@code NOT_COLLECTED}: no unexpired use decision permits this source for this purpose. */
    GOVERNANCE_NOT_EFFECTIVE,
    /** The existing workspace or integration access check refuses this area or repository. */
    ACCESS_NOT_PERMITTED,
    /** {@code NOT_COLLECTED}: collection is switched off for this source in this deployment. */
    DISABLED,
    /** {@code UNAVAILABLE}: this deployment ships no collector for the source. */
    NO_PROVIDER,
    /** {@code UNAVAILABLE}: the collector reported empty for a source whose contract forbids empty. */
    EMPTY_NOT_VALID,
    /** {@code UNAVAILABLE}: this repository has no working copy, so nothing could be read at any commit. */
    NO_WORKING_COPY,
    /** {@code UNAVAILABLE}: the authorized upstream could not supply repository Git objects. */
    REPOSITORY_FETCH_FAILED,
    /** {@code UNAVAILABLE}: the pinned base or head cannot be read from the repository. */
    REVIEW_VERSION_UNAVAILABLE,
    /** {@code UNAVAILABLE}: the requested conversation or item does not exist upstream. */
    NOT_FOUND,
    /** The item exists but its content was evicted; increasing the cap can restore it. */
    CONTENT_EVICTED,
    /** {@code REDACTED}: the people in it have not granted, or have withdrawn, consent to read it. */
    CONSENT_NOT_ACTIVE,
    /**
     * {@code UNAVAILABLE}: erasure recovery erased a message's earlier words after a restore, and no history read has
     * stored its current words yet.
     */
    WORDS_ERASED,
    /** {@code COLLECTION_ERROR}: the collector failed; the source's true content is unknown. */
    PROVIDER_FAILURE,
}
