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
    /** It was no longer timely before an attempt started, or before an attempt that lost its lease wrote anything. */
    EXPIRED,
    /** A readback proved that the effect does not exist, and nothing should create it now. */
    ABSENT,
    /** A write may have reached the provider, and the outcome is unknown. */
    WRITE_UNCONFIRMED,
    /** The lease expired after a write may have started, and no attempt may follow. */
    LEASE_LOST_AFTER_WRITE,
    /**
     * The workspace's authority narrowed after the action was queued, such as a member who left, so its write never
     * started (ADR 0134). After a write of the same action started, the action ends uncertain with this reason.
     */
    AUTHORITY_CHANGED
}
