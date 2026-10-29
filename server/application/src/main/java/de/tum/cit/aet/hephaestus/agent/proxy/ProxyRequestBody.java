package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Locks model identity and client-executed capabilities on each supported chat wire. */
final class ProxyRequestBody {
    private ProxyRequestBody() {}

    /**
     * @param withoutUsageRequest the body as the caller sent it; {@code null} when we added nothing, so
     *     a rejection is the caller's own and there is nothing to retry
     */
    @SuppressWarnings("ArrayRecordComponent") // raw request bytes sent upstream; never compared, hashed or printed
    record PreparedBody(byte[] body, byte @Nullable [] withoutUsageRequest) {
        byte[] withoutUsageRequestOrBody() {
            return withoutUsageRequest != null ? withoutUsageRequest : body;
        }
    }

    /**
     * Lock the model, strip what the sandbox may not ask for, and — on a streaming chat-completions
     * request — ask the provider to report usage.
     *
     * @param includeStreamingUsage true for chat-completions, whose streams report no usage unless the
     *     request carries {@code stream_options.include_usage}; the responses API reports it regardless
     * @return {@code null} when the body is not a JSON object or asks for a capability the proxy
     *     refuses to forward
     */
    @Nullable
    private static PreparedBody prepareOpenAi(
            ObjectMapper objectMapper, byte[] body, String upstreamModelId, boolean includeStreamingUsage) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode tree = objectMapper.readTree(body);
            if (!tree.isObject()) return null;
            ObjectNode object = (ObjectNode) tree;
            if (usesProviderHostedTool(object.get("tools"))
                    || object.has("web_search_options")
                    || object.has("audio")
                    || !isTextOnlyModality(object.get("modalities"))) return null;
            object.put("model", upstreamModelId);
            object.remove("service_tier");
            if (!includeStreamingUsage || !object.path("stream").asBoolean(false)) {
                return new PreparedBody(objectMapper.writeValueAsBytes(object), null);
            }
            byte[] asSent = objectMapper.writeValueAsBytes(object);
            JsonNode existing = object.get("stream_options");
            ObjectNode options = existing != null && existing.isObject()
                    ? (ObjectNode) existing
                    : object.putObject("stream_options");
            options.put("include_usage", true);
            byte[] withUsage = objectMapper.writeValueAsBytes(object);
            boolean weAddedTheFlag = !Arrays.equals(asSent, withUsage);
            return new PreparedBody(withUsage, weAddedTheFlag ? asSent : null);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static PreparedBody prepare(
            ObjectMapper objectMapper, byte[] body, String upstreamModelId, LlmApiProtocol protocol) {
        if (protocol != LlmApiProtocol.ANTHROPIC_MESSAGES) {
            return prepareOpenAi(objectMapper, body, upstreamModelId, protocol == LlmApiProtocol.OPENAI_COMPLETIONS);
        }
        try {
            JsonNode tree = objectMapper.readTree(body);
            if (!(tree instanceof ObjectNode object)
                    || object.has("mcp_servers")
                    || object.has("container")
                    || usesAnthropicHostedTool(object.get("tools"))) return null;
            object.put("model", upstreamModelId);
            object.remove("service_tier");
            return new PreparedBody(objectMapper.writeValueAsBytes(object), null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean usesAnthropicHostedTool(@Nullable JsonNode tools) {
        if (tools == null) return false;
        if (!tools.isArray()) return true;
        for (JsonNode tool : tools) {
            String type = tool.path("type").asString("custom");
            if (!"custom".equals(type)
                    || !tool.path("name").isString()
                    || !tool.path("input_schema").isObject()) return true;
        }
        return false;
    }

    private static boolean usesProviderHostedTool(JsonNode tools) {
        if (tools == null) return false;
        if (!tools.isArray()) return true;
        for (JsonNode tool : tools) {
            String type = tool.path("type").asString("");
            if (!"function".equals(type) && !"custom".equals(type)) return true;
        }
        return false;
    }

    private static boolean isTextOnlyModality(JsonNode modalities) {
        return (modalities == null
                || (modalities.isArray()
                        && modalities.size() == 1
                        && "text".equals(modalities.get(0).asString())));
    }
}
