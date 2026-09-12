package de.tum.cit.aet.hephaestus.agent.practice;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link PracticePiAdapter#buildSandboxSpec}. Carries no prompt — that travels in the
 * {@code task.json} envelope the handler writes.
 */
public record PracticeAgentRequest(
        String apiProtocol,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        boolean supportsReasoning,
        String jobToken,
        int timeoutSeconds) {
    public PracticeAgentRequest {
        Objects.requireNonNull(apiProtocol, "apiProtocol must not be null");
        Objects.requireNonNull(upstreamModelId, "upstreamModelId must not be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds);
        }
        if (jobToken == null || jobToken.isBlank()) {
            throw new IllegalArgumentException("jobToken is required — every sandbox talks to the LLM proxy");
        }
    }
}
