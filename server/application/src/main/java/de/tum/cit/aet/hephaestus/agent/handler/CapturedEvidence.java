package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What one attempt captured, read back from its evidence snapshot as the typed manifest it was written
 * from. Every reader of the snapshot — citation boundaries, the pinned repository head, the changed
 * paths, the secret verdicts — asks here, so the snapshot's shape has one home.
 */
final class CapturedEvidence {

    /** The staged patch, and the NUL-separated list of the paths it touches. */
    static final String DIFF_ARTIFACT = ContentSource.OUTPUT_PREFIX + "diff.patch";

    static final String DIFF_PATHS_ARTIFACT = ContentSource.OUTPUT_PREFIX + "diff_paths.nul";

    /** One captured file: the source it belongs to and the digest of the bytes staged for the run. */
    record Artifact(SourceKind kind, String sha256) {}

    private final ArtifactSourceManifest manifest;
    private final Set<SourceKind> availableSources;
    private final Map<String, Artifact> artifacts;
    private final @Nullable SecretScan secretScan;

    private CapturedEvidence(ArtifactSourceManifest manifest, @Nullable SecretScan secretScan) {
        this.manifest = manifest;
        this.secretScan = secretScan;
        Set<SourceKind> available = new HashSet<>();
        Map<String, Artifact> byPath = new HashMap<>();
        for (SourceCapture source : manifest.sources()) {
            if (!(source.state() instanceof SourceCaptureState.Available)) continue;
            available.add(source.kind());
            for (SourceArtifact artifact : source.artifacts()) {
                if (byPath.put(artifact.path(), new Artifact(source.kind(), artifact.sha256())) != null) {
                    throw new JobDeliveryException(
                            "Evidence artifact belongs to multiple sources: path=" + artifact.path());
                }
            }
        }
        this.availableSources = Set.copyOf(available);
        this.artifacts = Map.copyOf(byPath);
    }

    static CapturedEvidence of(ArtifactSourceManifest manifest) {
        return new CapturedEvidence(manifest, null);
    }

    /** The job's evidence snapshot, refused as inadmissible when it does not hold a manifest this runtime can read. */
    static CapturedEvidence of(AgentJob job, ObjectMapper mapper) {
        JsonNode snapshot = job.getEvidenceSnapshot();
        if (snapshot == null || !snapshot.isObject()) {
            throw new JobDeliveryException("Job has no evidence snapshot: jobId=" + job.getId());
        }
        JsonNode manifest = snapshot.path("manifest");
        if (!manifest.isObject()) {
            throw new JobDeliveryException("Job evidence snapshot has no source manifest: jobId=" + job.getId());
        }
        JsonNode secretScan = snapshot.path(SecretScan.SNAPSHOT_NODE);
        try {
            return new CapturedEvidence(
                    mapper.treeToValue(manifest, ArtifactSourceManifest.class),
                    secretScan.isObject() ? mapper.treeToValue(secretScan, SecretScan.class) : null);
        } catch (JacksonException exception) {
            throw new JobDeliveryException(
                    "Job evidence snapshot is not a readable manifest: jobId=" + job.getId(), exception);
        }
    }

    SourceContractVersion contractVersion() {
        return manifest.contractVersion();
    }

    /** The sources the run could read; a citation of any other source is fabricated. */
    Set<SourceKind> availableSources() {
        return availableSources;
    }

    Map<String, Artifact> artifacts() {
        return artifacts;
    }

    @Nullable
    Artifact artifact(String path) {
        return artifacts.get(path);
    }

    /** The artifact at {@code path} as {@code kind} staged it; anything else is unavailable or misattributed. */
    Artifact requireArtifact(SourceKind kind, String path) {
        Artifact artifact = artifacts.get(path);
        if (!availableSources.contains(kind)
                || artifact == null
                || !artifact.kind().equals(kind)) {
            throw new JobDeliveryException("Unavailable or misattributed evidence source " + kind + ": path=" + path);
        }
        return artifact;
    }

    /** The immutable identity an available source reported, or null when it reported none. */
    @Nullable
    String immutableIdentity(SourceKind kind) {
        for (SourceCapture source : manifest.sources()) {
            if (source.kind().equals(kind) && source.state() instanceof SourceCaptureState.Available available) {
                return available.facts().immutableIdentity();
            }
        }
        return null;
    }

    /** The commit the captured repository was pinned at. */
    String pinnedHead() {
        String identity = immutableIdentity(RepositoryTreeContentSource.KIND);
        String head = identity == null ? "" : identity.split(":", 2)[0];
        if (!head.matches(CitationVerification.GIT_OBJECT_ID)) {
            throw new JobDeliveryException("Captured repository has no pinned commit identity");
        }
        return head;
    }

    /**
     * The paths the captured change touches, read from the artifact the capture wrote them to; empty when
     * no diff was captured.
     */
    Set<String> diffPaths(AgentJob job, JobEvidenceFiles evidenceFiles) {
        if (!availableSources.contains(PracticeSubjectClause.DIFF_SOURCE)) return Set.of();
        Artifact listing = artifacts.get(DIFF_PATHS_ARTIFACT);
        if (listing == null || !listing.kind().equals(PracticeSubjectClause.DIFF_SOURCE)) {
            throw new JobDeliveryException("Captured diff source has no diff artifact: jobId=" + job.getId());
        }
        return evidenceFiles
                .inspect(job, DIFF_PATHS_ARTIFACT, listing.sha256(), reader -> {
                    Set<String> paths = new HashSet<>();
                    StringBuilder path = new StringBuilder();
                    int value;
                    while ((value = reader.read()) != -1) {
                        if (value == 0) {
                            if (path.isEmpty()) throw new JobDeliveryException("Captured diff contains an empty path");
                            paths.add(path.toString());
                            path.setLength(0);
                        } else {
                            if (path.length() >= 32_768)
                                throw new JobDeliveryException(
                                        "Captured diff path exceeds the filesystem path resource bound");
                            path.append((char) value);
                        }
                    }
                    if (!path.isEmpty()) throw new JobDeliveryException("Captured diff path is not NUL terminated");
                    return Set.copyOf(paths);
                })
                .orElseThrow(() -> new JobDeliveryException("Captured diff is no longer available"));
    }

    /** The secret verdicts recorded at preparation, or null when the review did not run the scan. */
    @Nullable
    SecretScan secretScan() {
        return secretScan;
    }
}
