package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

public record GitCancel(java.util.UUID operationId) implements WorkerControlFrame {
    @Override
    public String toString() {
        return "GitCancel[operationId=" + operationId + "]";
    }
}
