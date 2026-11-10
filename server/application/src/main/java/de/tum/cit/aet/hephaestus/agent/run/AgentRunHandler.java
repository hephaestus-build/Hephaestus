package de.tum.cit.aet.hephaestus.agent.run;

import java.time.Duration;

/**
 * The domain side of one {@link AgentRunKind}. The reaper calls it in the transaction that takes a run back, before it
 * writes the run, so the domain row moves with its run or neither moves, and the domain still reads the run as the lost
 * holder left it: first {@link #lock}, then {@link #lost} or {@link #ended}.
 */
public interface AgentRunHandler {

    AgentRunKind kind();

    /** How many lost leases a run of this kind survives before the reaper ends it. */
    int retries();

    /** How long a run that lost its lease waits before it is offered again, after it spent {@code spentAttempts}. */
    default Duration retryAfter(int spentAttempts) {
        return AgentRuns.backoff(spentAttempts);
    }

    /**
     * Locks the run's domain row, and whatever this kind's writers lock before it. The reaper calls it before anything
     * else, because every writer of a domain row and its run takes the domain row first.
     */
    void lock(AgentRunRef run);

    /**
     * The lease under {@code fence} expired, and the run has retries left. The holder of that epoch can no longer
     * write.
     *
     * @return true when the domain row went back to the queue, so the run is queued again for its next claim; false
     *     when the kind's own budget for the work is spent and it ended the domain row, so the run ends with {@link
     *     AgentRunStopReason#ATTEMPTS_EXHAUSTED}
     */
    boolean lost(AgentRunFence fence);

    /** The run ends without its holder: its leases were lost too often, or its deadline passed. */
    void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason);
}
