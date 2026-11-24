package de.tum.cit.aet.hephaestus.agent.run;

/** Why a run ended. The domain row records its detailed outcome. */
public enum AgentRunStopReason {
    /** Its holder completed its work. */
    COMPLETED,
    /** Its work went on in a run of another kind, which carries the outcome; its subject keeps it as history. */
    HANDED_OFF,
    /** Its holder ended it because its work failed. */
    FAILED,
    /** Its subject was cancelled, erased or replaced. */
    CANCELLED,
    /** Its leases expired more often than its kind's attempt budget allows. */
    ATTEMPTS_EXHAUSTED,
    /** It waited longer than its {@code start_within} for room to begin, while nothing of its subject was ahead. */
    NOT_STARTED,
    /** Its work timed out or its deadline passed. */
    DEADLINE,
    /**
     * The authority it was requested under narrowed, and a boundary refused it (ADR 0134). It does not go on under the
     * old authority; new work is requested under the new one.
     */
    AUTHORITY_CHANGED
}
