package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Server-produced verdicts travel with admitted observations, never with unverified model output. */
public final class CitationVerification {

    private static final JsonMapper MAPPER = new JsonMapper();

    private CitationVerification() {}

    static void record(ObjectNode citation, AgentJob job, String artifactDigest, String quoteDigest) {
        var verdict = citation.putObject("verification");
        verdict.put("status", "VERIFIED");
        verdict.put("jobId", job.getId().toString());
        verdict.put("attempt", job.getRetryCount());
        verdict.put("artifactSha256", artifactDigest);
        verdict.put("quoteSha256", quoteDigest);
        verdict.put("scope", "EXACT_LOCATION");
        verdict.put("citationSha256", citationDigest(citation));
    }

    static void requireVerified(AgentJob job, @Nullable JsonNode evidence) {
        if (evidence == null) throw new JobDeliveryException("No admitted evidence");
        JsonNode citations = evidence.path("citations");
        if (!citations.isArray() || citations.isEmpty()) throw new JobDeliveryException("No admitted citations");
        requireVerifiedCitations(job.getId(), job.getRetryCount(), citations);
    }

    public static void requireVerifiedCitations(UUID jobId, int attempt, JsonNode citations) {
        if (!citations.isArray() || citations.isEmpty()) throw new JobDeliveryException("No admitted citations");
        for (JsonNode citation : citations) {
            JsonNode verdict = citation.path("verification");
            if (!"VERIFIED".equals(verdict.path("status").asString())
                    || !"EXACT_LOCATION".equals(verdict.path("scope").asString())
                    || !citationDigest(citation)
                            .equals(verdict.path("citationSha256").asString())
                    || !jobId.toString().equals(verdict.path("jobId").asString())
                    || attempt != verdict.path("attempt").asInt(-1)
                    || !verdict.path("artifactSha256").asString().matches("[0-9a-f]{64}")
                    || !verdict.path("quoteSha256").asString().matches("[0-9a-f]{64}")) {
                throw new JobDeliveryException("Citation has no verification for this review attempt");
            }
            if (citation.path("quote").isString()
                    && !quoteDigest(citation.path("quote").asString())
                            .equals(verdict.path("quoteSha256").asString())) {
                throw new JobDeliveryException("Citation quote differs from its admitted verdict");
            }
        }
    }

    static String citationDigest(JsonNode citation) {
        var identity = MAPPER.createArrayNode();
        identity.add(citation.path("sourceKind").asString());
        identity.add(citation.path("artifactPath").asString());
        identity.add(citation.path("path").asString());
        identity.add(citation.path("side").asString());
        identity.add(citation.path("revision").asString());
        identity.add(citation.path("startLine").asInt(-1));
        identity.add(citation.path("endLine").asInt(citation.path("startLine").asInt(-1)));
        return ProvenanceDigest.sha256Hex(MAPPER.writeValueAsBytes(identity));
    }

    static String quoteDigest(String quote) {
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(quote));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return ProvenanceDigest.sha256Hex(bytes);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new JobDeliveryException("Citation quote is not valid Unicode", exception);
        }
    }
}
