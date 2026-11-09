package de.tum.cit.aet.hephaestus.agent.run;

/** What a run drives. Each kind has one {@link AgentRunHandler}, which owns its domain row. */
public enum AgentRunKind {
    /** A review's measurement: its practices checked against the captured work. */
    REVIEW,
    /** A review's composition: the feedback written from its admitted results, after its measurement ended. */
    REVIEW_COMPOSE
}
