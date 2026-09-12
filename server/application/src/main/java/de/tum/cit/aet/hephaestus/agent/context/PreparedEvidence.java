package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one capture staged for a sandbox.
 *
 * @param manifest the source manifest of a practice review; null for evidence built without a source
 *     contract, which only the mentor chat and a files-only test fixture do
 */
public record PreparedEvidence(
        Map<String, byte[]> files,
        Map<String, Path> filesOnDisk,
        List<AutoCloseable> cleanups,
        @Nullable ArtifactSourceManifest manifest,
        List<EvidenceDirectory> directories)
        implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PreparedEvidence.class);

    public PreparedEvidence(
            Map<String, byte[]> files,
            Map<String, Path> filesOnDisk,
            List<AutoCloseable> cleanups,
            @Nullable ArtifactSourceManifest manifest) {
        this(files, filesOnDisk, cleanups, manifest, List.of());
    }

    public PreparedEvidence(Map<String, byte[]> files, @Nullable ArtifactSourceManifest manifest) {
        this(files, Map.of(), List.of(), manifest);
    }

    public PreparedEvidence {
        directories = List.copyOf(directories);
        files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
        filesOnDisk = Map.copyOf(Objects.requireNonNull(filesOnDisk, "filesOnDisk"));
        cleanups = List.copyOf(Objects.requireNonNull(cleanups, "cleanups"));
    }

    /** The same capture with {@code files} in place of this one's; disk-staged content and cleanups are shared. */
    public PreparedEvidence withFiles(Map<String, byte[]> files) {
        return new PreparedEvidence(files, filesOnDisk, cleanups, manifest, directories);
    }

    /** Releases every staging directory backing {@link #filesOnDisk}. Safe to call more than once. */
    @Override
    public void close() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception e) {
                log.warn("Could not release staged evidence", e);
            }
        }
    }
}
