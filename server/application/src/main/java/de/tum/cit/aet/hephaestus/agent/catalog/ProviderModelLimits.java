package de.tum.cit.aet.hephaestus.agent.catalog;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads a model's token limits from the metadata that its provider returns. Each provider names them differently:
 *
 * <ul>
 *   <li>Anthropic {@code GET /v1/models}: {@code max_input_tokens} and {@code max_tokens}
 *       (https://platform.claude.com/docs/en/api/models/list).
 *   <li>OpenRouter {@code GET /api/v1/models}: {@code context_length}, and {@code top_provider.context_length} and
 *       {@code top_provider.max_completion_tokens} (https://openrouter.ai/docs/api-reference/models/get-models).
 *   <li>vLLM {@code GET /v1/models}: {@code max_model_len}, the context window that the server runs with
 *       ({@code ModelCard} in {@code vllm/entrypoints/serve/engine/protocol.py}).
 *   <li>LiteLLM {@code GET /model/info}: {@code model_info.max_input_tokens} and {@code model_info.max_output_tokens} of
 *       each {@code model_name} (https://docs.litellm.ai/docs/proxy/model_management,
 *       https://docs.litellm.ai/docs/provider_registration/add_model_pricing).
 *   <li>Ollama {@code POST /api/show}: the {@code num_ctx} line of {@code parameters}, at most {@code
 *       model_info["<general.architecture>.context_length"]}
 *       (https://github.com/ollama/ollama/blob/main/docs/api.md#show-model-information).
 * </ul>
 *
 * OpenAI's and Azure OpenAI's listings carry no limits; {@link ModelLimitCatalog} covers OpenAI's models. A value that
 * is not a positive whole number counts as absent.
 */
final class ProviderModelLimits {
    private ProviderModelLimits() {}

    private static final String NUM_CTX = "num_ctx";

    /** The limits a provider reported for one model; either may be absent. */
    record Limits(@Nullable Integer contextWindow, @Nullable Integer maxOutputTokens) {
        static final Limits NONE = new Limits(null, null);

        boolean empty() {
            return contextWindow == null && maxOutputTokens == null;
        }

        /** This model's limits, with each absent one taken from {@code other}. */
        Limits or(Limits other) {
            return new Limits(
                    contextWindow != null ? contextWindow : other.contextWindow,
                    maxOutputTokens != null ? maxOutputTokens : other.maxOutputTokens);
        }
    }

    /** The limits of each model in a {@code /models} listing, by model id. A model with no limits is left out. */
    static Map<String, Limits> fromModelList(LlmApiProtocol protocol, @Nullable JsonNode body) {
        Map<String, Limits> limits = new HashMap<>();
        if (body == null) return limits;
        for (JsonNode entry : body.path("data")) {
            String id = entry.path("id").asString("");
            if (id.isBlank()) continue;
            Limits found = protocol == LlmApiProtocol.ANTHROPIC_MESSAGES
                    ? new Limits(positive(entry.path("max_input_tokens")), positive(entry.path("max_tokens")))
                    // OpenRouter may route a call to a provider other than its top one: the smaller window fits both.
                    : new Limits(
                                    min(
                                            positive(entry.path("context_length")),
                                            positive(entry.path("top_provider").path("context_length"))),
                                    positive(entry.path("top_provider").path("max_completion_tokens")))
                            .or(new Limits(positive(entry.path("max_model_len")), null));
            if (!found.empty()) limits.put(id, found);
        }
        return limits;
    }

    /** The limits of each model in a LiteLLM {@code /model/info} answer, by its public {@code model_name}. */
    static Map<String, Limits> fromLiteLlmModelInfo(@Nullable JsonNode body) {
        Map<String, Limits> limits = new HashMap<>();
        if (body == null) return limits;
        for (JsonNode entry : body.path("data")) {
            String name = entry.path("model_name").asString("");
            if (name.isBlank()) continue;
            JsonNode info = entry.path("model_info");
            Limits found =
                    new Limits(positive(info.path("max_input_tokens")), positive(info.path("max_output_tokens")));
            // A model group can hold several deployments: the smallest limit fits each of them.
            if (!found.empty()) limits.merge(name, found, ProviderModelLimits::smaller);
        }
        return limits;
    }

    /**
     * The context window in an Ollama {@code /api/show} answer: the model's {@code num_ctx} parameter, at most its trained
     * context length. Without {@code num_ctx}, Ollama runs the model with its server's default window and silently drops
     * the start of a longer prompt, so the trained length alone is no usable window. Ollama reports no output limit; the
     * proxy writes one into each call.
     */
    static Limits fromOllamaShow(@Nullable JsonNode body) {
        if (body == null) return Limits.NONE;
        Integer window = null;
        for (String line : body.path("parameters").asString("").lines().toList()) {
            String parameter = line.strip();
            if (parameter.startsWith(NUM_CTX + " ") || parameter.startsWith(NUM_CTX + "\t"))
                window = positive(parameter.substring(NUM_CTX.length()).strip());
        }
        if (window == null) return Limits.NONE;
        JsonNode info = body.path("model_info");
        String architecture = info.path("general.architecture").asString("");
        Integer trained = architecture.isBlank() ? null : positive(info.path(architecture + ".context_length"));
        return new Limits(min(window, trained), null);
    }

    private static @Nullable Integer positive(String value) {
        try {
            int count = Integer.parseInt(value);
            return count > 0 ? count : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Limits smaller(Limits a, Limits b) {
        return new Limits(min(a.contextWindow, b.contextWindow), min(a.maxOutputTokens, b.maxOutputTokens));
    }

    private static @Nullable Integer min(@Nullable Integer a, @Nullable Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    private static @Nullable Integer positive(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) return null;
        int count = value.asInt();
        return count > 0 ? count : null;
    }
}
