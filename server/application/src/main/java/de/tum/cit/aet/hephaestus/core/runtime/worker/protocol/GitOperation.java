package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

public record GitOperation(
        java.util.UUID operationId,
        String sessionId,
        long workspaceId,
        long repositoryId,
        String requestJson,
        long deadlineEpochMillis,
        boolean delete)
        implements WorkerControlFrame {
    @Override
    public String toString() {
        return "GitOperation[operationId=" + operationId + "]";
    }
}
