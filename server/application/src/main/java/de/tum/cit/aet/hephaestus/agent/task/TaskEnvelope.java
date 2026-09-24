package de.tum.cit.aet.hephaestus.agent.task;

import java.util.Objects;
import java.util.UUID;

/** Task payload and input locations written to {@code /workspace/task.json}; see the workspace ABI. */
public record TaskEnvelope(int schemaVersion, UUID jobId, long workspaceId, Task task, TaskPaths paths) {
    public static final int SCHEMA_VERSION = 2;

    public TaskEnvelope {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(paths, "paths");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive, got " + schemaVersion);
        }
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive, got " + workspaceId);
        }
    }

    public static TaskEnvelope of(UUID jobId, long workspaceId, Task task) {
        return new TaskEnvelope(SCHEMA_VERSION, jobId, workspaceId, task, TaskPaths.capturedInputs());
    }
}
