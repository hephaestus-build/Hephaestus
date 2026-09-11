package de.tum.cit.aet.hephaestus.agent.handler.spi;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record PreparedJobInputs(
        Map<String, byte[]> files,
        /** Staged by path rather than retained as byte arrays; see {@code EvidenceContribution#filesOnDisk}. */
        Map<String, java.nio.file.Path> filesOnDisk,
        java.util.List<EvidenceDirectory> directories,
        /** Releases worker evidence after final admission, or when the attempt terminates without admission. */
        java.util.List<AutoCloseable> cleanups,
        @Nullable ArtifactSourceManifest artifactSourceManifest,
        @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport)
        implements AutoCloseable {
    public PreparedJobInputs(
            Map<String, byte[]> files,
            @Nullable ArtifactSourceManifest artifactSourceManifest,
            @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        this(
                files,
                Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                artifactSourceManifest,
                automatedReviewReadinessReport);
    }

    public PreparedJobInputs(
            Map<String, byte[]> files,
            Map<String, java.nio.file.Path> filesOnDisk,
            java.util.List<AutoCloseable> cleanups,
            @Nullable ArtifactSourceManifest manifest,
            @Nullable AutomatedReviewReadinessReport readiness) {
        this(files, filesOnDisk, java.util.List.of(), cleanups, manifest, readiness);
    }

    @Override
    public void close() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(PreparedJobInputs.class).warn("Could not release staged input", e);
            }
        }
    }

    public PreparedJobInputs {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
        filesOnDisk = Map.copyOf(Objects.requireNonNull(filesOnDisk, "filesOnDisk"));
        directories = java.util.List.copyOf(Objects.requireNonNull(directories, "directories"));
        cleanups = java.util.List.copyOf(Objects.requireNonNull(cleanups, "cleanups"));
        if ((artifactSourceManifest == null) != (automatedReviewReadinessReport == null)) {
            throw new IllegalArgumentException("Evidence manifest and readiness report must be provided together");
        }
    }

    public static PreparedJobInputs filesOnly(Map<String, byte[]> files) {
        return new PreparedJobInputs(files, null, null);
    }
}
