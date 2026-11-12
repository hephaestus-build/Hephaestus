package de.tum.cit.aet.hephaestus.agent.run;

import java.time.Duration;

/**
 * The domain side of one {@link AgentRunKind}. The reaper calls it in the transaction that takes a run back, before it
 * writes the run, unless the handler atomically continues it. The domain moves with its run or neither moves, and the domain reads the run as the lost
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
     * The lease expired with retries left. The domain either queues the same run, ends, or atomically replaces the run.
     * A continuation already ended the old run and relinked its successor; the reaper must not write it again.
     */
    LossOutcome lost(AgentRunFence fence);

    sealed interface LossOutcome permits Requeue, End, Continued {}

    record Requeue() implements LossOutcome {}

    record End() implements LossOutcome {}

    record Continued(AgentRunRef next) implements LossOutcome {}

    /** The run ends without its holder: its leases were lost too often, or its deadline passed. */
    void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason);
}
