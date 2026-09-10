package de.tum.cit.aet.hephaestus.agent.config;

/** The things a workspace runs an LLM for; at most one {@link WorkspaceAgentBinding} per purpose and processing location. */
public enum AgentPurpose {
    /** Pull-request, issue, and conversation practice review. */
    PRACTICE_REVIEW,
    /** Interactive mentor turns (web SSE and Slack). */
    MENTOR,
}
