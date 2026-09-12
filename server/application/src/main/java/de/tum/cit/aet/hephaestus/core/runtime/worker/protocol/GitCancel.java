package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;

/** Hub → worker: the hub stopped waiting for this operation; stop it and free its permit. */
public record GitCancel(UUID operationId) implements WorkerControlFrame {
    public GitCancel {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId must not be null");
        }
    }

    @Override
    public String toString() {
        return "GitCancel[operationId=" + operationId + "]";
    }
}
