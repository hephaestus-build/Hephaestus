package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.commons.io.FileUtils;

/**
 * The paths of the evidence folders under the jobs root: where an attempt's folder is, which relative paths stay
 * inside a folder, the digest check of a captured file, and the removal of a folder.
 */
final class EvidenceFolders {
    private EvidenceFolders() {}

    static Path directory(Path jobsRoot, ReviewJob job) {
        return directory(jobsRoot, job.getWorkspaceId(), job.getId(), job.getAttempt(), job.getRunId());
    }

    static Path directory(Path jobsRoot, long workspaceId, UUID jobId, int attempt, UUID runId) {
        return jobsRoot.resolve(Long.toString(workspaceId))
                .resolve(jobId.toString())
                .resolve(attempt + "-"
                        + ProvenanceDigest.sha256Hex(runId.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static Path safePath(Path root, String path) {
        Path relative = Path.of(path);
        if (relative.isAbsolute()
                || !relative.normalize().equals(relative)
                || path.isBlank()
                || path.contains("\\")
                || relative.startsWith("..")) {
            throw new IllegalArgumentException("Unsafe evidence path: " + path);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root))
            throw new IllegalArgumentException("Unsafe evidence path");
        return resolved;
    }

    static void requireDigest(String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalStateException("Captured evidence digest mismatch");
    }

    static void delete(Path root) {
        try {
            FileUtils.deleteDirectory(root.toFile());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
