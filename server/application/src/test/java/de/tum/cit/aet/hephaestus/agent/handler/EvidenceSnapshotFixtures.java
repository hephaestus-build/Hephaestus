package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evidence snapshots shaped exactly as an attempt records them, so admission reads them back through
 * the typed manifest the way it reads a real one.
 */
public final class EvidenceSnapshotFixtures {

    public static final String CAPTURED_AT = "2026-08-03T00:00:00Z";

    private EvidenceSnapshotFixtures() {}

    /** A pull-request snapshot with no sources yet; add at least one with {@link #availableSource}. */
    public static ObjectNode snapshot(ObjectMapper mapper) {
        return snapshot(mapper, ArtifactKinds.PULL_REQUEST.value());
    }

    public static ObjectNode snapshot(ObjectMapper mapper, String artifactKind) {
        ObjectNode snapshot = mapper.createObjectNode();
        ObjectNode manifest = snapshot.putObject("manifest");
        manifest.put("contractVersion", ArtifactSourceCatalogRegistry.CURRENT_VERSION.value());
        manifest.put("catalogDigest", "0".repeat(64));
        manifest.put("artifactKind", artifactKind);
        manifest.put("capturedAt", CAPTURED_AT);
        manifest.putArray("sources");
        snapshot.putArray("practices");
        return snapshot;
    }

    /** Adds an available, complete, non-empty source and returns it so artifacts can be added. */
    public static ObjectNode availableSource(ObjectNode snapshot, String kind, @Nullable String immutableIdentity) {
        ObjectNode source = snapshot.withObject("manifest").withArray("sources").addObject();
        source.put("kind", kind);
        ObjectNode state = source.putObject("state");
        state.put("availability", "AVAILABLE");
        state.put("content", "NON_EMPTY");
        state.put("completeness", "COMPLETE");
        ObjectNode facts = state.putObject("facts");
        facts.put("capturedAt", CAPTURED_AT);
        if (immutableIdentity != null) facts.put("immutableIdentity", immutableIdentity);
        state.putArray("limitations");
        source.putArray("artifacts");
        return source;
    }

    public static ObjectNode artifact(ObjectNode source, String path, String sha256) {
        return source.withArray("artifacts")
                .addObject()
                .put("path", path)
                .put("mediaType", "text/plain")
                .put("sha256", sha256)
                .put("bytes", 0);
    }

    /** Turns a source into one the capture could not read; its artifacts go with it. */
    public static void unavailable(ObjectNode source) {
        source.putObject("state").put("availability", "UNAVAILABLE").put("reasonCode", "NOT_FOUND");
        source.putArray("artifacts");
    }

    public static ObjectNode admittedPractice(ObjectNode snapshot, String slug, long revisionId) {
        return snapshot.withArray("practices").addObject().put("slug", slug).put("revisionId", revisionId);
    }

    /** Records secret verdicts the way preparation does, bound to the staged diff by digest. */
    public static void secretScan(
            ObjectMapper mapper, ObjectNode snapshot, String diffSha256, List<SecretScan.Hit> hits) {
        snapshot.set(
                SecretScan.SNAPSHOT_NODE,
                mapper.valueToTree(new SecretScan(CapturedEvidence.DIFF_ARTIFACT, diffSha256, hits)));
    }
}
