package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Persisted-admission fixtures for tests of downstream authorization, not quote verification. */
public final class AdmittedObservationFixtures {
    private AdmittedObservationFixtures() {}

    public static JsonNode evidence(UUID jobId, String sourceKind) {
        return evidence(jobId, sourceKind, "inputs/context/evidence.txt", "evidence.txt", "verified quote");
    }

    /** One admitted citation at line 1 of {@code path}, carrying the verdict a real admission records. */
    public static JsonNode evidence(UUID jobId, String sourceKind, String artifactPath, String path, String quote) {
        var job = new AgentJob();
        job.setId(jobId);
        var evidence = new JsonMapper().createObjectNode();
        var citation = evidence.putArray("citations")
                .addObject()
                .put("sourceKind", sourceKind)
                .put("artifactPath", artifactPath)
                .put("path", path)
                .put("startLine", 1)
                .put("endLine", 1)
                .put("quote", quote)
                .put("quoteRedacted", false);
        CitationVerification.record(citation, job, "a".repeat(64), CitationVerification.quoteDigest(quote));
        return evidence;
    }
}
