package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Why a model stopped answering, as its provider reports it. Only a provider's own word counts: a call that the
 * proxy refused before it reached the provider is no model's refusal ({@code llm_proxy_refused_total} counts those).
 */
final class ModelStopReason {

    /** The model declined to answer: Anthropic's {@code refusal}, OpenAI's {@code content_filter}. */
    private static final Set<String> REFUSED = Set.of("refusal", "content_filter");

    /** The answer hit its token or context limit. */
    private static final Set<String> LENGTH =
            Set.of("max_tokens", "length", "max_output_tokens", "model_context_window_exceeded");

    private ModelStopReason() {}

    /**
     * The stop reason in a buffered answer or in one event of a streamed answer, or {@code null} when it names none.
     * Anthropic writes it as {@code stop_reason}, in a stream under {@code delta}. OpenAI chat completions write a
     * {@code finish_reason} per choice. The responses API states an incomplete answer's reason under
     * {@code incomplete_details}, in a stream inside the terminal event's {@code response}, and a refusal as a
     * {@code refusal} content part.
     */
    static @Nullable String of(@Nullable JsonNode node, LlmApiProtocol protocol) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return switch (protocol) {
            case ANTHROPIC_MESSAGES ->
                text(node.path("stop_reason"), node.path("delta").path("stop_reason"));
            case OPENAI_COMPLETIONS -> {
                for (JsonNode choice : node.path("choices")) {
                    String reason = text(choice.path("finish_reason"));
                    if (reason != null) yield reason;
                }
                yield null;
            }
            case OPENAI_RESPONSES -> {
                JsonNode response = node.has("response") ? node.path("response") : node;
                String reason = text(response.path("incomplete_details").path("reason"));
                if (reason != null) yield reason;
                for (JsonNode item : response.path("output")) {
                    for (JsonNode part : item.path("content")) {
                        if ("refusal".equals(part.path("type").asString(""))) yield "refusal";
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    /** The outcome of a served call that stopped for {@code reason}: {@code refused}, {@code length} or {@code completed}. */
    static String outcome(@Nullable String reason) {
        if (reason == null) {
            return "completed";
        }
        if (REFUSED.contains(reason)) {
            return "refused";
        }
        return LENGTH.contains(reason) ? "length" : "completed";
    }

    private static @Nullable String text(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate.isString() && !candidate.asString().isEmpty()) {
                return candidate.asString();
            }
        }
        return null;
    }
}
