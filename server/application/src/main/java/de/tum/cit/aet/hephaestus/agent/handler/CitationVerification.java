package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Server-produced verdicts travel with admitted results, never with unverified model output. */
public final class CitationVerification {

    /** A lowercase hex SHA-256 digest. */
    public static final String SHA256_HEX = "[0-9a-f]{64}";

    private CitationVerification() {}

    /** Every admitted citation has an exact quote digest and a captured text digest. */
    public static boolean isVerified(JsonNode citations) {
        if (!citations.isArray() || citations.isEmpty()) return false;
        for (JsonNode citation : citations) {
            JsonNode verdict = citation.path("verification");
            if (!"VERIFIED".equals(verdict.path("status").asString())
                    || !verdict.path("itemSha256").asString().matches(SHA256_HEX)
                    || !verdict.path("quoteSha256").asString().matches(SHA256_HEX)
                    || !citation.path("quote").isString()) return false;
            String quoteDigest = utf8Digest(citation.path("quote").asString());
            if (quoteDigest == null
                    || !quoteDigest.equals(verdict.path("quoteSha256").asString())) return false;
        }
        return true;
    }

    static String quoteDigest(String quote) {
        String digest = utf8Digest(quote);
        if (digest == null) throw new JobDeliveryException("Citation quote is not valid Unicode");
        return digest;
    }

    /** The digest of a quote as a candidate submitted it; null when it has no UTF-8 encoding. */
    static @Nullable String quoteDigestOrNull(String quote) {
        return utf8Digest(quote);
    }

    /** Null for a quote with a lone surrogate, which has no UTF-8 encoding to digest. */
    private static @Nullable String utf8Digest(String quote) {
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(quote));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return ProvenanceDigest.sha256Hex(bytes);
        } catch (CharacterCodingException exception) {
            return null;
        }
    }
}
