package de.tum.cit.aet.hephaestus.agent.job;

/** Why an {@link AgentJob} entered the {@link AgentJobStatus#CANCELLED} terminal state. */
public enum AgentJobCancellationReason {
    /** Graceful drain await timed out with the job still in flight. */
    DRAIN_GRACEFUL,
    /** Drain mode was immediate, so the job was cancelled without waiting. */
    DRAIN_IMMEDIATE,
    /**
     * At claim time the capped purse funding this job was exhausted, or its month was unverifiable — a
     * cap that cannot be verified is not a cap, so both refuse the job.
     */
    BUDGET_EXHAUSTED,
    /** The catalog binding was revoked or changed between submit and claim. */
    MODEL_UNAVAILABLE,
    /** A developer declined AI or has not made the workspace-required choice. */
    MEMBER_AI_DECLINED,
}
