package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;

/**
 * Worker → hub: one acknowledged chunk of an operation's output, or its terminal frame. The hub
 * synthesises a failed terminal frame with sequence {@code -1} when the worker is gone.
 */
public record GitOutput(UUID operationId, long sequence, String data, boolean terminal, boolean success)
        implements WorkerControlFrame {
    public GitOutput {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId must not be null");
        }
        if (sequence < -1) {
            throw new IllegalArgumentException("sequence must be >= -1, got: " + sequence);
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
    }

    @Override
    public String toString() {
        return "GitOutput[operationId=" + operationId + "]";
    }
}
