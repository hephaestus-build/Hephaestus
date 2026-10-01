package de.tum.cit.aet.hephaestus.agent.handler.spi;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The immutable, disk-backed folder retained for one attempt through admission. */
public record PreparedJobInputs(
        Map<String, Path> filesOnDisk,
        List<EvidenceDirectory> directories,
        List<AutoCloseable> cleanups,
        @Nullable JobFolderIndex folderIndex,
        @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport)
        implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PreparedJobInputs.class);

    public PreparedJobInputs {
        filesOnDisk = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filesOnDisk));
        directories = List.copyOf(directories);
        cleanups = List.copyOf(cleanups);
        if ((folderIndex == null) != (automatedReviewReadinessReport == null)) {
            throw new IllegalArgumentException("Folder index and readiness report must be provided together");
        }
    }

    @Override
    public void close() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception exception) {
                log.warn("Could not release the job folder", exception);
            }
        }
    }
}
