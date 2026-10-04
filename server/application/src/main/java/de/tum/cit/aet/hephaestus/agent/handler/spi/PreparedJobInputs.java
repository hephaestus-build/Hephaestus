package de.tum.cit.aet.hephaestus.agent.handler.spi;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The immutable, disk-backed folder retained for one attempt through admission.
 *
 * @param answeredPractices ready practices left out of the folder because a completed review already answered them
 */
public record PreparedJobInputs(
        Map<String, Path> filesOnDisk,
        List<EvidenceDirectory> directories,
        List<AutoCloseable> cleanups,
        @Nullable JobFolderIndex folderIndex,
        @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport,
        List<AnsweredPractice> answeredPractices)
        implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PreparedJobInputs.class);

    public PreparedJobInputs {
        filesOnDisk = Collections.unmodifiableMap(new LinkedHashMap<>(filesOnDisk));
        directories = List.copyOf(directories);
        cleanups = List.copyOf(cleanups);
        answeredPractices = List.copyOf(answeredPractices);
        if ((folderIndex == null) != (automatedReviewReadinessReport == null)) {
            throw new IllegalArgumentException("Folder index and readiness report must be provided together");
        }
    }

    public PreparedJobInputs(
            Map<String, Path> filesOnDisk,
            List<EvidenceDirectory> directories,
            List<AutoCloseable> cleanups,
            @Nullable JobFolderIndex folderIndex,
            @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        this(filesOnDisk, directories, cleanups, folderIndex, automatedReviewReadinessReport, List.of());
    }

    /** The same folder, owning the same cleanups; the receiver must not be closed separately. */
    public PreparedJobInputs withAnsweredPractices(List<AnsweredPractice> answered) {
        return new PreparedJobInputs(
                filesOnDisk, directories, cleanups, folderIndex, automatedReviewReadinessReport, answered);
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
