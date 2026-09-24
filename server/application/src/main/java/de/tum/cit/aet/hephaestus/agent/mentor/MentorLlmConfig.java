package de.tum.cit.aet.hephaestus.agent.mentor;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.AdmittedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * Slim projection of the LLM fields that {@link MentorPiAdapter} needs to build a sandbox spec — the
 * same resolved, non-secret shape the practice-review path freezes into {@code ConfigSnapshot}.
 */
public record MentorLlmConfig(
        String apiProtocol,
        String baseUrl,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable FundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        @Nullable LlmPriceSnapshot priceSnapshot,
        boolean allowInternet,
        int timeoutSeconds) {
    public static MentorLlmConfig fromAdmission(ModelBindingSource config, AdmittedLlmModel admitted) {
        ResolvedLlmModel resolved = admitted.resolved();
        LlmModelResolver.ConnectionRef ref = admitted.connection();
        return new MentorLlmConfig(
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
                config.isAllowInternet(),
                config.getTimeoutSeconds());
    }
}
