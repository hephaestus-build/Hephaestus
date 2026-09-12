package de.tum.cit.aet.hephaestus.agent.handler.spi;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one attempt stages for its sandbox: the captured evidence, and what preparation established
 * about it before any model ran.
 *
 * @param automatedReviewReadinessReport present exactly when the evidence carries a source manifest
 * @param secretScan the deterministic secret verdicts over the captured change, when the review
 *     admits the practice that reads them
 */
public record PreparedJobInputs(
        PreparedEvidence evidence,
        @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport,
        @Nullable SecretScan secretScan)
        implements AutoCloseable {

    public PreparedJobInputs(
            PreparedEvidence evidence, @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        this(evidence, automatedReviewReadinessReport, null);
    }

    public PreparedJobInputs {
        Objects.requireNonNull(evidence, "evidence");
        if ((evidence.manifest() == null) != (automatedReviewReadinessReport == null)) {
            throw new IllegalArgumentException("Evidence manifest and readiness report must be provided together");
        }
    }

    public static PreparedJobInputs filesOnly(Map<String, byte[]> files) {
        return new PreparedJobInputs(new PreparedEvidence(files, null), null);
    }

    public Map<String, byte[]> files() {
        return evidence.files();
    }

    /** Staged by path rather than retained as byte arrays; see {@code EvidenceContribution#filesOnDisk}. */
    public Map<String, Path> filesOnDisk() {
        return evidence.filesOnDisk();
    }

    public List<EvidenceDirectory> directories() {
        return evidence.directories();
    }

    /** Releases worker evidence after final admission, or when the attempt terminates without admission. */
    public List<AutoCloseable> cleanups() {
        return evidence.cleanups();
    }

    public @Nullable ArtifactSourceManifest artifactSourceManifest() {
        return evidence.manifest();
    }

    @Override
    public void close() {
        evidence.close();
    }
}
