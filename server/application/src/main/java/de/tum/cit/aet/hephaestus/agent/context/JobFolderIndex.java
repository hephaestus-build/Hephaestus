package de.tum.cit.aet.hephaestus.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record JobFolderIndex(
        SourceContractVersion contractVersion,
        String catalogDigest,
        String artifactKind,
        Instant capturedAt,
        List<SourceCapture> sources,
        List<WorkspaceRefusal> refusals,
        List<FolderArtifact> artifacts) {
    public JobFolderIndex(
            SourceContractVersion contractVersion,
            String catalogDigest,
            String artifactKind,
            Instant capturedAt,
            List<SourceCapture> sources) {
        this(contractVersion, catalogDigest, artifactKind, capturedAt, sources, List.of(), artifactsOf(sources));
    }

    public JobFolderIndex(
            SourceContractVersion contractVersion,
            String catalogDigest,
            String artifactKind,
            Instant capturedAt,
            List<SourceCapture> sources,
            List<WorkspaceRefusal> refusals) {
        this(contractVersion, catalogDigest, artifactKind, capturedAt, sources, refusals, artifactsOf(sources));
    }

    private static List<FolderArtifact> artifactsOf(List<SourceCapture> sources) {
        return sources.stream()
                .flatMap(source ->
                        source.artifacts().stream().map(artifact -> new FolderArtifact(source.kind(), artifact)))
                .toList();
    }

    public JobFolderIndex {
        refusals = List.copyOf(refusals);
        artifacts = List.copyOf(artifacts);
        var paths = new HashSet<String>();
        for (FolderArtifact artifact : artifacts) {
            if (!paths.add(artifact.artifact().path()))
                throw new IllegalArgumentException("Duplicate folder artifact path");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        requireCapture(
                contractVersion,
                catalogDigest,
                artifactKind,
                capturedAt,
                sources.stream().map(SourceCapture::kind).toList());
    }

    /** What the capture established about each source, without its staged files. */
    public Retained retained() {
        return new Retained(
                contractVersion,
                catalogDigest,
                artifactKind,
                capturedAt,
                sources.stream()
                        .map(source -> new RetainedSource(source.kind(), source.state()))
                        .toList());
    }

    /**
     * A manifest as it stays on its job once admission retires the staged files: the capture's contract, kind,
     * time and each source's state and facts, read with the same checks as the whole manifest. The folder's and
     * each source's artifact and refusal inventories are not part of it, so their retirement leaves it intact; a
     * reader that needs a file's digest reads the whole manifest instead.
     */
    @JsonIgnoreProperties({"refusals", "artifacts"})
    public record Retained(
            SourceContractVersion contractVersion,
            String catalogDigest,
            String artifactKind,
            Instant capturedAt,
            List<RetainedSource> sources) {
        public Retained {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            requireCapture(
                    contractVersion,
                    catalogDigest,
                    artifactKind,
                    capturedAt,
                    sources.stream().map(RetainedSource::kind).toList());
        }
    }

    /** One source's retained state and facts; see {@link Retained}. */
    @JsonIgnoreProperties({"artifacts"})
    public record RetainedSource(SourceKind kind, SourceCaptureState state) {
        public RetainedSource {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(state, "state");
        }
    }

    private static void requireCapture(
            SourceContractVersion contractVersion,
            String catalogDigest,
            String artifactKind,
            Instant capturedAt,
            List<SourceKind> sourceKinds) {
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(catalogDigest, "catalogDigest");
        if (!catalogDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalog digest: " + catalogDigest);
        }
        Objects.requireNonNull(artifactKind, "artifactKind");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (sourceKinds.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        var kinds = new HashSet<SourceKind>();
        for (SourceKind kind : sourceKinds) {
            if (!kinds.add(Objects.requireNonNull(kind, "kind"))) {
                throw new IllegalArgumentException("Duplicate source capture: " + kind);
            }
        }
    }
}
