package de.tum.cit.aet.hephaestus.agent.context;

import java.util.List;
import java.util.Objects;

/**
 * Credentials the deterministic scanner found on the added lines of a captured change, bound by digest
 * to the diff artifact those lines were staged as. Recorded beside the manifest in the evidence
 * snapshot at preparation, so admission reads a verdict rather than running a scan.
 *
 * @param artifactSha256 SHA-256 of the staged diff artifact the hits belong to
 */
public record SecretScan(String artifactPath, String artifactSha256, List<Hit> hits) {

    /** The evidence snapshot node the verdicts are recorded under, beside {@code manifest}. */
    public static final String SNAPSHOT_NODE = "secretHits";

    /** One credential on one added line, carried as the digest of that line rather than its text. */
    public record Hit(String path, int newLine, String lineHash, String ruleId) {
        public Hit {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(lineHash, "lineHash");
            Objects.requireNonNull(ruleId, "ruleId");
        }
    }

    public SecretScan {
        Objects.requireNonNull(artifactPath, "artifactPath");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
    }
}
