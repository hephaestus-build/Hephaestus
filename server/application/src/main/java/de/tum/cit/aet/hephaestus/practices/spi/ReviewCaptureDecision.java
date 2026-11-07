package de.tum.cit.aet.hephaestus.practices.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record ReviewCaptureDecision(
        long practiceId,
        ReviewPracticeStatus status,
        @Nullable String reason,
        JsonNode facts) {
    /** The fact that holds the digest of what the practice's capture read, so that a later request can compare it. */
    public static final String SOURCE_FINGERPRINTS = "sourceFingerprints";

    public ReviewCaptureDecision {
        facts = facts.deepCopy();
    }

    @Override
    public JsonNode facts() {
        return facts.deepCopy();
    }
}
