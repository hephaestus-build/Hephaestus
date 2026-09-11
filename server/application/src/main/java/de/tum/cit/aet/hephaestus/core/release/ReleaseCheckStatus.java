package de.tum.cit.aet.hephaestus.core.release;

/**
 * The one verdict an administrator reads. A failed or never-performed check is its own value so that
 * the absence of a known update is never mistaken for being up to date.
 */
public enum ReleaseCheckStatus {
    /** The operator opted out of outbound checks; nothing is known and nothing will be asked. */
    DISABLED,
    /** The running build is not a published release, so there is no release to compare with. */
    NOT_APPLICABLE,
    /** Checks are on but none has completed since this process started. */
    NEVER_CHECKED,
    /** The last check completed and no newer release is published. */
    CURRENT,
    /** The last check completed and a newer release is published. */
    UPDATE_AVAILABLE,
    /** The last check did not complete; any {@code latest} shown dates from the last success. */
    FAILED
}
