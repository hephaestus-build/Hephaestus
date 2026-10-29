package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link PracticePiAdapter#buildSandboxSpec}. Carries no prompt — that travels in the
 * {@code task.json} envelope the handler writes.
 *
 * @param jobToken the job's one proxy credential; the review and its practice programs' direct model calls share it
 * @param practiceScriptModels the direct models that the practice programs may call, by kind; empty for a job that is
 *     no practice review
 */
public record PracticeAgentRequest(
        String apiProtocol,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort,
        String jobToken,
        int timeoutSeconds,
        Map<ModelKind, FrozenModel> practiceScriptModels,
        boolean forcedToolChoice) {
    public PracticeAgentRequest {
        Objects.requireNonNull(apiProtocol, "apiProtocol must not be null");
        Objects.requireNonNull(upstreamModelId, "upstreamModelId must not be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds);
        }
        if (jobToken == null || jobToken.isBlank()) {
            throw new IllegalArgumentException("jobToken is required — every sandbox talks to the LLM proxy");
        }
        practiceScriptModels = Map.copyOf(practiceScriptModels);
    }
}
