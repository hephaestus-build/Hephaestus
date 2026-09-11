package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

public record GitAck(java.util.UUID operationId, long sequence) implements WorkerControlFrame {
    @Override
    public String toString() {
        return "GitAck[operationId=" + operationId + "]";
    }
}
