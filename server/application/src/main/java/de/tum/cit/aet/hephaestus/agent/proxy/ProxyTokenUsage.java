package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Normalized token buckets for one proxied response.
 *
 * @param billableInputTokens prompt tokens MINUS cache reads and writes, because OpenAI-compatible
 *     providers report both as details of the inclusive prompt total
 * @param reasoningTokens already counted inside {@link #outputTokens}; reported, never priced twice
 */
public record ProxyTokenUsage(
        int billableInputTokens, int outputTokens, int reasoningTokens, int cacheReadTokens, int cacheWriteTokens) {
    /**
     * Reads the usage block of one non-streamed response in the shape its protocol reports.
     *
     * @return {@code null} when the response reports no token count
     */
    static @Nullable ProxyTokenUsage from(JsonNode body, LlmApiProtocol protocol) {
        JsonNode reported = body.get("usage");
        JsonNode usage = reported != null && reported.isObject() ? reported : null;
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> from(body, false);
            case OPENAI_RESPONSES -> from(body, true);
            case OPENAI_DECISIONS ->
                usage == null
                        ? null
                        : new ProxyTokenUsage(
                                requiredCount(usage, "input_tokens"), requiredCount(usage, "output_tokens"), 0, 0, 0);
            case OPENAI_EMBEDDINGS ->
                usage == null ? null : new ProxyTokenUsage(requiredCount(usage, "prompt_tokens"), 0, 0, 0, 0);
            case COHERE_RERANK -> usage == null ? null : rerankUsage(usage);
        };
    }

    /** Reranking providers report either a total or a prompt count; some report neither. */
    private static @Nullable ProxyTokenUsage rerankUsage(JsonNode usage) {
        Integer total = optionalCount(usage, "total_tokens");
        Integer counted = total != null ? total : optionalCount(usage, "prompt_tokens");
        return counted == null ? null : new ProxyTokenUsage(counted, 0, 0, 0, 0);
    }

    /** @return {@code null} when no usage block is present */
    static @Nullable ProxyTokenUsage from(@Nullable JsonNode usageOwner, boolean responsesProtocol) {
        if (usageOwner == null) {
            return null;
        }
        JsonNode usage = usageOwner.get("usage");
        if (usage == null || !usage.isObject()) {
            return null;
        }
        int input;
        int output;
        int reasoning;
        int cacheRead;
        int cacheWrite;
        if (responsesProtocol) {
            input = requiredCount(usage, "input_tokens");
            output = requiredCount(usage, "output_tokens");
            cacheRead = count(usage.path("input_tokens_details"), "cached_tokens");
            cacheWrite = cacheWriteTokens(usage.path("input_tokens_details"));
            reasoning = count(usage.path("output_tokens_details"), "reasoning_tokens");
        } else {
            input = requiredCount(usage, "prompt_tokens");
            output = requiredCount(usage, "completion_tokens");
            cacheRead = chatCacheReadTokens(usage);
            cacheWrite = cacheWriteTokens(usage.path("prompt_tokens_details"));
            reasoning = count(usage.path("completion_tokens_details"), "reasoning_tokens");
        }
        if ((long) cacheRead + cacheWrite > input) {
            throw new IllegalArgumentException("Cache token details exceed input tokens");
        }
        return new ProxyTokenUsage(input - cacheRead - cacheWrite, output, reasoning, cacheRead, cacheWrite);
    }

    /** OpenAI-compatible completions retain the native SDK's nested, cache-hit, then top-level precedence. */
    private static int chatCacheReadTokens(JsonNode usage) {
        Integer nested = optionalCount(usage.path("prompt_tokens_details"), "cached_tokens");
        if (nested != null) {
            return nested;
        }
        Integer cacheHit = optionalCount(usage, "prompt_cache_hit_tokens");
        return cacheHit != null ? cacheHit : count(usage, "cached_tokens");
    }

    private static int cacheWriteTokens(JsonNode details) {
        Integer standard = optionalCount(details, "cache_write_tokens");
        Integer logos = optionalCount(details, "created_cache_tokens");
        if (standard != null && logos != null && !standard.equals(logos)) {
            throw new IllegalArgumentException("Conflicting cache-write token details");
        }
        return standard != null ? standard : logos != null ? logos : 0;
    }

    private static int count(JsonNode owner, String field) {
        Integer value = optionalCount(owner, field);
        return value != null ? value : 0;
    }

    private static int requiredCount(JsonNode owner, String field) {
        Integer value = optionalCount(owner, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing token count: " + field);
        }
        return value;
    }

    private static @Nullable Integer optionalCount(JsonNode owner, String field) {
        JsonNode value = owner.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException("Invalid token count: " + field);
        }
        return value.intValue();
    }
}
