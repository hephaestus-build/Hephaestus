package de.tum.cit.aet.hephaestus.agent.run;

/** Why a run ended. It names the run machinery only; the domain row records what the work itself came to. */
public enum AgentRunStopReason {
    /** Its holder ended it. */
    COMPLETED,
    /** Someone other than its holder ended it: a person, an erasure, a replacement, or a refused claim. */
    CANCELLED,
    /** Its leases expired more often than its kind's attempt budget allows. */
    ATTEMPTS_EXHAUSTED,
    /** It waited longer than its {@code start_within} for room to begin, while nothing of its subject was ahead. */
    NOT_STARTED,
    /** Its deadline passed while it ran. */
    DEADLINE
}
