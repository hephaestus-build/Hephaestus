package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
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
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(catalogDigest, "catalogDigest");
        if (!catalogDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalog digest: " + catalogDigest);
        }
        Objects.requireNonNull(artifactKind, "artifactKind");
        Objects.requireNonNull(capturedAt, "capturedAt");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        var kinds = new HashSet<SourceKind>();
        for (SourceCapture source : sources) {
            if (!kinds.add(source.kind())) {
                throw new IllegalArgumentException("Duplicate source capture: " + source.kind());
            }
        }
    }
}
