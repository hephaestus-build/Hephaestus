package de.tum.cit.aet.hephaestus.agent.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Content-addressed, private execution evidence; absence is never reported as complete capture. */
public record ExecutionArchiveDTO(
        int schemaVersion,
        @NonNull UUID jobId,
        @NonNull String jobStatus,
        @NonNull List<AttemptDTO> attempts) {

    public record AttemptDTO(
            int attempt,
            @NonNull String captureState,
            @NonNull Instant preparedAt,
            @NonNull String image,
            @Nullable String traceId,
            @NonNull List<FileDTO> files) {}

    public record FileDTO(
            @NonNull String path,
            @NonNull String sha256,
            long bytes,
            @NonNull String mediaType) {}
}
