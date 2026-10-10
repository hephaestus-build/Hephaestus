package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link PracticePiAdapter#buildSandboxSpec}. Carries no prompt — that travels in the
 * {@code task.json} envelope the handler writes.
 *
 * @param precomputeToken the credential of the precompute scripts, scoped to the precompute models
 *     only; {@code null} for a job that runs no precompute scripts
 * @param precomputeModels the models that the precompute scripts may call, by kind; empty without a
 *     {@code precomputeToken}
 */
public record PracticeAgentRequest(
        String apiProtocol,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort,
        String jobToken,
        int timeoutSeconds,
        @Nullable String precomputeToken,
        Map<ModelKind, FrozenModel> precomputeModels) {
    public PracticeAgentRequest {
        Objects.requireNonNull(apiProtocol, "apiProtocol must not be null");
        Objects.requireNonNull(upstreamModelId, "upstreamModelId must not be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds);
        }
        if (jobToken == null || jobToken.isBlank()) {
            throw new IllegalArgumentException("jobToken is required — every sandbox talks to the LLM proxy");
        }
        precomputeModels = Map.copyOf(precomputeModels);
    }
}
