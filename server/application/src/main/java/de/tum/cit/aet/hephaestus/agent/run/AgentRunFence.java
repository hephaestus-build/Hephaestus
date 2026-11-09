package de.tum.cit.aet.hephaestus.agent.run;

import java.util.UUID;

/**
 * The write credential of a run's holder: the run and the epoch of the claim it holds. Every write of the holder names
 * it, and a write under any other epoch changes nothing.
 */
public record AgentRunFence(UUID runId, long workspaceId, int epoch) {
    public AgentRunFence {
        if (epoch < 1) throw new IllegalArgumentException("A fence names a claim, and claims count from 1");
    }

    public AgentRunRef run() {
        return new AgentRunRef(runId, workspaceId);
    }
}
