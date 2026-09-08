package de.tum.cit.aet.hephaestus.agent.handler.inapp;

/**
 * The outcome of routing one composed message for the practice pages. Only {@link #ADMIT} becomes a
 * PREPARED IN_APP feedback unit; every other value is a named, testable reason the developer is not
 * shown it.
 */
public enum InAppRoutingDecision {
    /** A corroborated, live, autonomy-admitted pattern the developer has not been shown lately — prepare it. */
    ADMIT,

    /**
     * The composed message named a practice this recipient has no measurement of in the window, so
     * there is nothing to stand behind it. A message with no evidence is not feedback.
     */
    NO_EVIDENCE,

    /** Evidence does not meet the composition contract's minimum distinct-work threshold. */
    UNCORROBORATED,

    /** The practice's autonomy (OFF or HUMAN_APPROVAL) does not admit the in-app lane. */
    PRACTICE_REQUIRES_APPROVAL,

    /**
     * Every measurement behind the message came from a backfill campaign. Backfill is sound as a
     * snapshot and, by {@code ObservationOrigin}'s own words, "unusable as a trend against LIVE rows" —
     * and a process-level message is exactly a trend claim. Held back deliberately rather than
     * structurally: the entitlement in {@code ObservationOrigin} still admits the lane, so lifting this is
     * one line and one decision, taken with a real workspace in front of you.
     */
    BACKFILL_HELD,

    /**
     * The recipient was shown a message about this practice inside the cooldown. A pattern does not
     * change week to week, and repeating it turns a private surface into nagging.
     */
    RECENTLY_SURFACED,

    /**
     * The practice's occasion is about somebody other than the artifact's author, so the observations
     * bound to it may be filed against the wrong person. Refused by name rather than shown: the private
     * view is the surface that would make the misattribution visible, to the one person it is not about.
     * Lifts when reviewer attribution exists.
     */
    REVIEWER_ATTRIBUTED,

    /** The composed message arrived without a body or a next step, so there is nothing to deliver. */
    INCOMPLETE,
}
