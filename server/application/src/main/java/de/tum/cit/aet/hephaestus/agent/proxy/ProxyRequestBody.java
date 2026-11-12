package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.util.Arrays;
import java.util.Set;
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
     * Lock the model, clamp or write the output limit, strip what the sandbox may not ask for, and — on a streaming
     * chat-completions request — ask the provider to report usage.
     *
     * <p>A chat-completions stream reports no usage unless the request carries {@code stream_options.include_usage};
     * the responses API reports it regardless.
     *
     * @return {@code null} when the body is not a JSON object or asks for a capability the proxy
     *     refuses to forward
     */
    @Nullable
    private static PreparedBody prepareOpenAi(
            ObjectMapper objectMapper,
            byte[] body,
            String upstreamModelId,
            LlmApiProtocol protocol,
            @Nullable Long outputLimit) {
        boolean includeStreamingUsage = protocol == LlmApiProtocol.OPENAI_COMPLETIONS;
        if (body == null || body.length == 0) return null;
        try {
            JsonNode tree = objectMapper.readTree(body);
            if (!tree.isObject()) return null;
            ObjectNode object = (ObjectNode) tree;
            if (!hasTextOnlyInput(object)
                    || usesProviderHostedTool(object.get("tools"))
                    || object.has("web_search_options")
                    || object.has("audio")
                    || !isTextOnlyModality(object.get("modalities"))) return null;
            object.put("model", upstreamModelId);
            object.remove("service_tier");
            if (outputLimit != null && OutputLimit.generative(protocol))
                OutputLimit.clamp(object, protocol, outputLimit);
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

    /**
     * @param outputLimit the call's output limit, which a generative request is clamped to ({@link OutputLimit#clamp});
     *     {@code null} for a call that writes no output
     */
    @Nullable
    static PreparedBody prepare(
            ObjectMapper objectMapper,
            byte[] body,
            String upstreamModelId,
            LlmApiProtocol protocol,
            @Nullable Long outputLimit) {
        if (protocol != LlmApiProtocol.ANTHROPIC_MESSAGES) {
            return prepareOpenAi(objectMapper, body, upstreamModelId, protocol, outputLimit);
        }
        try {
            JsonNode tree = objectMapper.readTree(body);
            if (!(tree instanceof ObjectNode object)
                    || !hasTextOnlyInput(object)
                    || object.has("mcp_servers")
                    || object.has("container")
                    || usesAnthropicHostedTool(object.get("tools"))) return null;
            object.put("model", upstreamModelId);
            object.remove("service_tier");
            if (outputLimit != null && OutputLimit.generative(protocol))
                OutputLimit.clamp(object, protocol, outputLimit);
            return new PreparedBody(objectMapper.writeValueAsBytes(object), null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Request bytes bound text tokens, but cannot bound provider-side state, images, audio or fetched files. */
    static boolean hasTextOnlyInput(JsonNode request) {
        if (request.hasNonNull("previous_response_id")
                || request.hasNonNull("conversation")
                || request.hasNonNull("prompt")) return false;
        for (String field : new String[] {"messages", "input", "system"}) {
            JsonNode value = request.get(field);
            if (value != null && containsNonTextInput(value)) return false;
        }
        return true;
    }

    private static boolean containsNonTextInput(JsonNode value) {
        if (value.isObject()) {
            String type = value.path("type").asString("");
            if (Set.of(
                                    "image",
                                    "image_url",
                                    "input_image",
                                    "input_audio",
                                    "audio",
                                    "input_file",
                                    "file",
                                    "document",
                                    "video",
                                    "input_video",
                                    "item_reference")
                            .contains(type)
                    || value.has("inlineData")
                    || value.has("fileData")
                    || value.hasNonNull("file_id")) return true;
            // Tool arguments are text to the model; their field names do not name input modalities.
            if (type.equals("tool_use") || type.equals("function_call")) return false;
        }
        if (value.isObject() || value.isArray())
            for (JsonNode child : value) if (containsNonTextInput(child)) return true;
        return false;
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
