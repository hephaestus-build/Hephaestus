package de.tum.cit.aet.hephaestus.core.release;

/** A check that failed or never ran is its own verdict, never folded into {@link #CURRENT}. */
public enum ReleaseCheckStatus {
    /** The operator switched outbound checks off. */
    DISABLED,
    /** The running build is not a published release, so there is nothing to compare with. */
    NOT_APPLICABLE,
    /** No check has completed since this process started. */
    NEVER_CHECKED,
    /** No newer release was published when GitHub was last asked. */
    CURRENT,
    /** A newer release is published. */
    UPDATE_AVAILABLE,
    /** The last check did not complete; {@code latest} is whatever the last completed check found. */
    FAILED
}
