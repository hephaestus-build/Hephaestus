package de.tum.cit.aet.hephaestus.agent.handler.spi;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record PreparedJobInputs(
        Map<String, byte[]> files,
        /** Staged by path rather than retained as byte arrays; see {@code EvidenceContribution#filesOnDisk}. */
        Map<String, Path> filesOnDisk,
        List<EvidenceDirectory> directories,
        /** Releases worker evidence after final admission, or when the attempt terminates without admission. */
        List<AutoCloseable> cleanups,
        @Nullable ArtifactSourceManifest artifactSourceManifest,
        @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport)
        implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PreparedJobInputs.class);

    public PreparedJobInputs(
            Map<String, byte[]> files,
            @Nullable ArtifactSourceManifest artifactSourceManifest,
            @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        this(files, Map.of(), List.of(), List.of(), artifactSourceManifest, automatedReviewReadinessReport);
    }

    @Override
    public void close() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception e) {
                log.warn("Could not release staged input", e);
            }
        }
    }

    public PreparedJobInputs {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
        filesOnDisk = Map.copyOf(Objects.requireNonNull(filesOnDisk, "filesOnDisk"));
        directories = List.copyOf(Objects.requireNonNull(directories, "directories"));
        cleanups = List.copyOf(Objects.requireNonNull(cleanups, "cleanups"));
        if ((artifactSourceManifest == null) != (automatedReviewReadinessReport == null)) {
            throw new IllegalArgumentException("Evidence manifest and readiness report must be provided together");
        }
    }

    public static PreparedJobInputs filesOnly(Map<String, byte[]> files) {
        return new PreparedJobInputs(files, null, null);
    }
}
