package de.tum.cit.aet.hephaestus.agent.job;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Private on-disk capture manifest, not a product HTTP contract. */
public record ExecutionCaptureManifest(
        int attempt,
        String captureState,
        Instant preparedAt,
        String image,
        @Nullable String traceId,
        List<FileDTO> files) {
    public record FileDTO(String path, String sha256, long bytes, String mediaType) {}
}
