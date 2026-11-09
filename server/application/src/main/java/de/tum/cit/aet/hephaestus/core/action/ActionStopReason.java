package de.tum.cit.aet.hephaestus.core.action;

/**
 * Why an action ended. Every ended action records one; the database CHECK lists the same names. A kind's own detail
 * (an SMTP reply class, a provider status) stays in its metrics and its domain row.
 */
public enum ActionStopReason {
    /** The provider confirmed the effect. */
    COMPLETED,
    /** The provider refused it for good, such as an SMTP 5xx reply. */
    REJECTED,
    /** The recipient or target is not valid, or no longer exists. */
    RECIPIENT_UNAVAILABLE,
    /** This instance has no transport configured for the kind. */
    NOT_CONFIGURED,
    /** Nobody wants it any more: the recipient unsubscribed or lost the role, or its source is gone. */
    WITHDRAWN,
    /** Silent Mode held it back. */
    SILENCED,
    /** Every attempt failed before a write could start. */
    ATTEMPTS_EXHAUSTED,
    /** It was no longer timely before an attempt started. */
    EXPIRED,
    /** A write may have reached the provider, and the outcome is unknown. */
    WRITE_UNCONFIRMED,
    /** The lease expired after a write may have started, and the kind cannot read back. */
    LEASE_LOST_AFTER_WRITE
}
