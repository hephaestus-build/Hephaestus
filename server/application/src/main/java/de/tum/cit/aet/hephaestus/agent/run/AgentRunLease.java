package de.tum.cit.aet.hephaestus.agent.run;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A claim that {@link AgentRuns} granted: the holder drives the run until {@code leasedUntil} unless it renews.
 *
 * @param attempt the attempts the run spent before this claim
 */
public record AgentRunLease(
        UUID runId,
        long workspaceId,
        AgentRunKind kind,
        String concurrencyKey,
        int attempt,
        int epoch,
        Instant leasedUntil,
        @Nullable Instant deadline) {

    public AgentRunFence fence() {
        return new AgentRunFence(runId, workspaceId, epoch);
    }
}
