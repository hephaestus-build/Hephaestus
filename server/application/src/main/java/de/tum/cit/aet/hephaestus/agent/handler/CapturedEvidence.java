package de.tum.cit.aet.hephaestus.agent.handler;

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

/** Typed access to the persisted evidence snapshot. */
final class CapturedEvidence {

    record Artifact(SourceKind kind, String sha256) {}

    private final ArtifactSourceManifest manifest;
    private final Set<SourceKind> availableSources;
    private final Map<String, Artifact> artifacts;

    private CapturedEvidence(ArtifactSourceManifest manifest) {
        this.manifest = manifest;
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
        return new CapturedEvidence(manifest);
    }

    static CapturedEvidence of(AgentJob job, ObjectMapper mapper) {
        JsonNode snapshot = job.getEvidenceSnapshot();
        if (snapshot == null || !snapshot.isObject()) {
            throw new JobDeliveryException("Job has no evidence snapshot: jobId=" + job.getId());
        }
        JsonNode manifest = snapshot.path("manifest");
        if (!manifest.isObject()) {
            throw new JobDeliveryException("Job evidence snapshot has no source manifest: jobId=" + job.getId());
        }
        try {
            return new CapturedEvidence(mapper.treeToValue(manifest, ArtifactSourceManifest.class));
        } catch (JacksonException exception) {
            throw new JobDeliveryException(
                    "Job evidence snapshot is not a readable manifest: jobId=" + job.getId(), exception);
        }
    }

    SourceContractVersion contractVersion() {
        return manifest.contractVersion();
    }

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

    Artifact requireArtifact(SourceKind kind, String path) {
        Artifact artifact = artifacts.get(path);
        if (!availableSources.contains(kind)
                || artifact == null
                || !artifact.kind().equals(kind)) {
            throw new JobDeliveryException("Unavailable or misattributed evidence source " + kind + ": path=" + path);
        }
        return artifact;
    }

    @Nullable
    String immutableIdentity(SourceKind kind) {
        for (SourceCapture source : manifest.sources()) {
            if (source.kind().equals(kind) && source.state() instanceof SourceCaptureState.Available available) {
                return available.facts().immutableIdentity();
            }
        }
        return null;
    }

    String pinnedHead() {
        String identity = immutableIdentity(RepositoryTreeContentSource.KIND);
        String head = identity == null ? "" : identity.split(":", 2)[0];
        if (!head.matches(CitationVerification.GIT_OBJECT_ID)) {
            throw new JobDeliveryException("Captured repository has no pinned commit identity");
        }
        return head;
    }

    /** The reviewed range as {@code {base, head}}, from the change the diff source pinned. */
    String[] reviewRange() {
        String identity = immutableIdentity(PracticeSubjectClause.DIFF_SOURCE);
        String[] range = identity == null ? new String[0] : identity.split(":", -1);
        if (range.length != 2
                || !range[0].matches(CitationVerification.GIT_OBJECT_ID)
                || !range[1].matches(CitationVerification.GIT_OBJECT_ID)) {
            throw new JobDeliveryException("Captured change has no pinned base and head");
        }
        return range;
    }
}
