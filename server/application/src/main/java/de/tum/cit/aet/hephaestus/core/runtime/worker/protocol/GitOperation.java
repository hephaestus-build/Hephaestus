package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;

/**
 * Hub → worker: run one native Git operation on the worker's mirror, or delete the mirror when
 * {@code delete} is set. {@code requestJson} may carry a provider token, so it never reaches a log.
 */
public record GitOperation(
        UUID operationId,
        String sessionId,
        long workspaceId,
        long repositoryId,
        String requestJson,
        long deadlineEpochMillis,
        boolean delete)
        implements WorkerControlFrame {
    public GitOperation {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId must not be null");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (workspaceId < 0 || repositoryId <= 0) {
            throw new IllegalArgumentException("workspaceId and repositoryId must identify a repository");
        }
        if (requestJson == null) {
            throw new IllegalArgumentException("requestJson must not be null");
        }
        if (deadlineEpochMillis <= 0) {
            throw new IllegalArgumentException("deadlineEpochMillis must be positive");
        }
    }

    @Override
    public String toString() {
        return "GitOperation[operationId=" + operationId + "]";
    }
}
