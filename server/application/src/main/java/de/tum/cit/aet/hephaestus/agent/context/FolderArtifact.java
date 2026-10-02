package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Objects;

/** Attribution and digest of a frozen file, independent of reviewed-work readiness facts. */
public record FolderArtifact(SourceKind kind, SourceArtifact artifact) {
    public FolderArtifact {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(artifact, "artifact");
    }
}
