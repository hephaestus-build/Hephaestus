package de.tum.cit.aet.hephaestus.agent.run;

import java.io.Serial;

/** A write named a claim that no longer holds its run: a later claim, a requeue, an end or the reaper took it. */
public class StaleRunException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient AgentRunFence fence;

    public StaleRunException(AgentRunFence fence) {
        super("The run is no longer held under this claim");
        this.fence = fence;
    }

    /** @param cause the stale write that found the claim gone */
    public StaleRunException(AgentRunFence fence, Throwable cause) {
        super("The run is no longer held under this claim", cause);
        this.fence = fence;
    }

    public AgentRunFence fence() {
        return fence;
    }
}
