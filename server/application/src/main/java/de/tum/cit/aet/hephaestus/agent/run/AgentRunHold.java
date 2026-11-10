package de.tum.cit.aet.hephaestus.agent.run;

/**
 * Why a queued run waits for something its work does not control, which can be lifted before its {@code available_at}.
 * A retry's backoff is not a hold: it has only its {@code available_at}.
 */
public enum AgentRunHold {
    /** The workspace's model budget is spent; raising the budget lifts it. */
    BUDGET,
    /** No sandbox had room for the run; it waits a growing interval for room. */
    SANDBOX_ROOM
}
