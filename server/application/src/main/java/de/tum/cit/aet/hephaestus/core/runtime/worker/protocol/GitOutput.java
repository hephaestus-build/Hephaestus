package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

public record GitOutput(java.util.UUID operationId, long sequence, String data, boolean terminal, boolean success)
        implements WorkerControlFrame {
    @Override
    public String toString() {
        return "GitOutput[operationId=" + operationId + "]";
    }
}
