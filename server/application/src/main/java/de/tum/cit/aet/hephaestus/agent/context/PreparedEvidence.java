package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PreparedEvidence(
        Map<String, byte[]> files,
        Map<String, java.nio.file.Path> filesOnDisk,
        java.util.List<AutoCloseable> cleanups,
        ArtifactSourceManifest manifest,
        java.util.List<EvidenceDirectory> directories)
        implements AutoCloseable {
    public PreparedEvidence(
            Map<String, byte[]> files,
            Map<String, java.nio.file.Path> filesOnDisk,
            java.util.List<AutoCloseable> cleanups,
            ArtifactSourceManifest manifest) {
        this(files, filesOnDisk, cleanups, manifest, java.util.List.of());
    }

    public PreparedEvidence(Map<String, byte[]> files, ArtifactSourceManifest manifest) {
        this(files, Map.of(), java.util.List.of(), manifest);
    }

    public PreparedEvidence {
        directories = java.util.List.copyOf(directories);
        files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
        filesOnDisk = Map.copyOf(Objects.requireNonNull(filesOnDisk, "filesOnDisk"));
        cleanups = java.util.List.copyOf(Objects.requireNonNull(cleanups, "cleanups"));
        Objects.requireNonNull(manifest, "manifest");
    }

    /** Releases every staging directory backing {@link #filesOnDisk}. Safe to call more than once. */
    @Override
    public void close() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(PreparedEvidence.class).warn("Could not release staged evidence", e);
            }
        }
    }
}
