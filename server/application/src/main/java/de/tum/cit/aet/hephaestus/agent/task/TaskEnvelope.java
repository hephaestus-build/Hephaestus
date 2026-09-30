package de.tum.cit.aet.hephaestus.agent.task;

import java.util.Objects;
import java.util.UUID;

/** One flat task.json record. The staged runner and task schema change together. */
public record TaskEnvelope(
        int schemaVersion,
        UUID jobId,
        long workspaceId,
        String prompt,
        int pullRequestNumber,
        String repositoryFullName,
        String contextRoot,
        String repositoryRoot,
        String manifest,
        String practiceIndex,
        String compositionRequest,
        String preparedFeedback,
        String precomputeScripts) {
    public static final int SCHEMA_VERSION = 3;

    public TaskEnvelope {
        Objects.requireNonNull(jobId, "jobId");
        new Task(prompt, pullRequestNumber, repositoryFullName);
        new TaskPaths(
                contextRoot,
                repositoryRoot,
                manifest,
                practiceIndex,
                compositionRequest,
                preparedFeedback,
                precomputeScripts);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
        }
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive, got " + workspaceId);
        }
    }

    public static TaskEnvelope of(UUID jobId, long workspaceId, Task task) {
        var paths = TaskPaths.capturedInputs();
        return new TaskEnvelope(
                SCHEMA_VERSION,
                jobId,
                workspaceId,
                task.prompt(),
                task.pullRequestNumber(),
                task.repositoryFullName(),
                paths.contextRoot(),
                paths.repositoryRoot(),
                paths.manifest(),
                paths.practiceIndex(),
                paths.compositionRequest(),
                paths.preparedFeedback(),
                paths.precomputeScripts());
    }

    public TaskPaths paths() {
        return new TaskPaths(
                contextRoot,
                repositoryRoot,
                manifest,
                practiceIndex,
                compositionRequest,
                preparedFeedback,
                precomputeScripts);
    }
}
