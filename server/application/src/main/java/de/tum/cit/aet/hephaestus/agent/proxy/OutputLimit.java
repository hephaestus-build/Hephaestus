package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.io.Serial;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The output limit of a generative model call (OWASP LLM10:2025, Unbounded Consumption). Every chat call that the proxy
 * forwards carries one in the fields of its own protocol: each field that the request names is clamped to the call's
 * limit {@code L}, and a request that names none gets {@code L} in the protocol's default field. Fields of another
 * protocol do not count. The effective limit and the request bytes bound what the call can cost.
 */
final class OutputLimit {
    private OutputLimit() {}

    /** A request names an output limit in its protocol's field that is not a positive whole number. */
    static final class Refused extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }

        Refused(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The output tokens that bound a call's cost: the effective limit of a generative request ({@link #clamp}), the
     * frozen limit of a decision call, and none for an embedding or reranking call.
     *
     * @param limit the call's limit {@code L}; {@code null} when its model froze none
     * @return {@code null} when nothing bounds the call's output
     * @throws Refused when a generative call has no limit, or its request names a limit that is not a positive whole
     *     number
     */
    static @Nullable Long bound(LlmApiProtocol protocol, @Nullable Long limit, JsonNode request) {
        return switch (protocol) {
            case ANTHROPIC_MESSAGES, OPENAI_COMPLETIONS, OPENAI_RESPONSES -> {
                if (limit == null)
                    throw new Refused("This model has no output limit. An admin must set its maximum output tokens"
                            + " or its context window.");
                // The same rule as the forwarded body, on a copy: the bound is the limit that the provider receives.
                long effective =
                        request instanceof ObjectNode object ? clamp(object.deepCopy(), protocol, limit) : limit;
                if (protocol != LlmApiProtocol.OPENAI_COMPLETIONS) yield effective;
                JsonNode count = request.get("n");
                if (count == null || count.isNull()) yield effective;
                if (!count.isIntegralNumber() || !count.canConvertToLong() || count.asLong() <= 0)
                    throw new Refused("The completion count n of a model call must be a positive whole number");
                try {
                    yield Math.multiplyExact(effective, count.asLong());
                } catch (ArithmeticException overflow) {
                    throw new Refused("The total output limit of a model call is too large", overflow);
                }
            }
            case OPENAI_DECISIONS -> limit;
            case OPENAI_EMBEDDINGS, COHERE_RERANK -> 0L;
        };
    }

    /** Whether calls on this protocol write output. Embedding, reranking and decision calls write none. */
    static boolean generative(LlmApiProtocol protocol) {
        return !fields(protocol).isEmpty();
    }

    /**
     * Sets each output-limit field of the protocol that the request names to at most {@code limit}, or writes {@code
     * limit} into the protocol's default field when the request names none. A JSON {@code null} counts as absent.
     *
     * @return the effective limit: the largest value that the request now names
     * @throws Refused when a field of the protocol is not a positive whole number
     */
    static long clamp(ObjectNode request, LlmApiProtocol protocol, long limit) {
        List<String> fields = fields(protocol);
        long effective = 0;
        for (String field : fields) {
            JsonNode value = request.get(field);
            if (value == null || value.isNull()) continue;
            if (!value.isIntegralNumber() || value.bigIntegerValue().signum() <= 0)
                throw new Refused("The output limit " + field + " of a model call must be a positive whole number");
            long clamped = value.canConvertToLong() ? Math.min(value.asLong(), limit) : limit;
            request.put(field, clamped);
            effective = Math.max(effective, clamped);
        }
        if (effective > 0) return effective;
        request.put(fields.getFirst(), limit);
        return limit;
    }

    /**
     * The fields that name an output limit on each chat wire; the first is the one written when the request names none.
     * On chat completions, {@code max_tokens} is the field that OpenAI-compatible servers (vLLM, Ollama, LiteLLM) accept
     * most widely; {@code max_completion_tokens} counts when the caller sends it.
     */
    private static List<String> fields(LlmApiProtocol protocol) {
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> List.of("max_tokens");
            case OPENAI_COMPLETIONS -> List.of("max_tokens", "max_completion_tokens");
            case OPENAI_RESPONSES -> List.of("max_output_tokens");
            case OPENAI_DECISIONS, OPENAI_EMBEDDINGS, COHERE_RERANK -> List.of();
        };
    }
}
