package de.tum.cit.aet.hephaestus.agent.conversation;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentLimits;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.usage.AdmittedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import org.jspecify.annotations.Nullable;

/**
 * Slim projection of the LLM fields a Hephaestus turn runs with — the
 * same resolved, non-secret shape the practice-review path freezes into {@code ConfigSnapshot}.
 */
public record ConversationLlmConfig(
        String apiProtocol,
        String baseUrl,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable UsageFundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        @Nullable LlmPriceSnapshot priceSnapshot,
        int timeoutSeconds) {
    public ConversationLlmConfig {
        // Reserve shutdown time even for bindings persisted below the runnable floor or above the Hephaestus turn
        // ceiling.
        timeoutSeconds = Math.clamp(
                timeoutSeconds,
                PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1,
                ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS);
    }

    public static ConversationLlmConfig fromAdmission(ModelBindingSource config, AdmittedLlmModel admitted) {
        ResolvedLlmModel resolved = admitted.resolved();
        LlmModelResolver.ConnectionRef ref = admitted.connection();
        return new ConversationLlmConfig(
                resolved.apiProtocol(),
                resolved.baseUrl(),
                resolved.upstreamModelId(),
                resolved.contextWindow(),
                resolved.maxOutputTokens(),
                resolved.reasoningEffort(),
                ref.scope(),
                ref.connectionId(),
                ref.modelId(),
                ref.workspaceId(),
                admitted.price(),
                config.getTimeoutSeconds());
    }
}
