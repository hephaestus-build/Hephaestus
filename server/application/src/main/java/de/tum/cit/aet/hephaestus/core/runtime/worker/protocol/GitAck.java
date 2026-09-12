package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;

/** Hub → worker: the chunk with this sequence was written, send the next one. */
public record GitAck(UUID operationId, long sequence) implements WorkerControlFrame {
    public GitAck {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId must not be null");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0, got: " + sequence);
        }
    }

    @Override
    public String toString() {
        return "GitAck[operationId=" + operationId + "]";
    }
}
