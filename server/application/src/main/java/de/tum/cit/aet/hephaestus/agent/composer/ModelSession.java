package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One composer conversation with the attempt's frozen chat model: the system prompt, the turns, the tools and what the
 * model called. It speaks the two chat protocols the proxy serves, with the request fields the sandbox runner's model
 * client sends on them, and it never streams.
 */
final class ModelSession {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** One model call as the transport sends it; the proxy fences, bills and bounds it. */
    interface Transport {
        Answer call(byte[] body);
    }

    /** What the transport returned: the provider's answer, or the proxy rule that refused the call. */
    @SuppressWarnings("ArrayRecordComponent") // the provider's response bytes, parsed once here
    record Answer(int status, byte[] body, boolean owned, ReviewModelGateway.@Nullable Refusal refusal) {}

    /** A tool the model may call. */
    record Tool(String name, String description, ObjectNode parameters) {}

    /** One tool call: its id, its tool, and its arguments as the model wrote them. */
    record ToolCall(String id, String name, JsonNode arguments) {}

    /** The tool's answer to one call. */
    record ToolResult(String callId, String text) {}

    /** The model's reply: its text and the tools it called, or why the call failed. */
    record Reply(
            @Nullable String text,
            List<ToolCall> toolCalls,
            @Nullable Failure failure) {}

    enum Failure {
        /** The attempt no longer runs on this worker; nothing more may be sent or stored. */
        OWNERSHIP_LOST,
        /** The workspace's AI choice no longer permits the model. */
        AI_CHOICE,
        /** The budget that funds the attempt is spent. */
        BUDGET,
        /** The attempt's work deadline has passed. */
        WORK_TIME,
        /** The provider refused the request itself, such as a revoked key or a request over the context window. */
        REFUSED,
        /** The provider failed or answered with something unreadable; another call may succeed. */
        PROVIDER
    }

    /** The model settings frozen on the attempt. */
    record Settings(
            LlmApiProtocol protocol,
            String modelId,
            @Nullable ReasoningEffort reasoningEffort,
            int maxOutputTokens,
            @Nullable Double temperature) {}

    private final Transport transport;
    private final ObjectMapper mapper;
    private final Settings settings;
    private final String system;
    private final List<Tool> tools;
    private final ArrayNode history = JSON.arrayNode();
    private int calls;

    ModelSession(Transport transport, ObjectMapper mapper, Settings settings, String system, List<Tool> tools) {
        this.transport = transport;
        this.mapper = mapper;
        this.settings = settings;
        this.system = system;
        this.tools = List.copyOf(tools);
        if (settings.protocol() == LlmApiProtocol.OPENAI_COMPLETIONS)
            history.add(JSON.objectNode().put("role", "system").put("content", system));
    }

    int calls() {
        return calls;
    }

    /** Sends a user turn and returns the model's reply. */
    Reply send(String text) {
        history.add(JSON.objectNode().put("role", "user").put("content", text));
        return call();
    }

    /** Sends the same request again, after a provider failure left the conversation unchanged. */
    Reply retry() {
        return call();
    }

    /** Answers the model's tool calls, and optionally adds a user turn after them, and returns its next reply. */
    Reply answer(List<ToolResult> results, @Nullable String then) {
        for (var result : results) {
            if (settings.protocol() == LlmApiProtocol.OPENAI_COMPLETIONS)
                history.add(JSON.objectNode()
                        .put("role", "tool")
                        .put("tool_call_id", result.callId())
                        .put("content", result.text()));
            else
                history.add(JSON.objectNode()
                        .put("type", "function_call_output")
                        .put("call_id", result.callId())
                        .put("output", result.text()));
        }
        if (then != null) history.add(JSON.objectNode().put("role", "user").put("content", then));
        return call();
    }

    /** The request this session sends next, for an estimate of its size before it is sent. */
    String requestText(String nextTurn) {
        var probe = request();
        var turns =
                (ArrayNode) probe.path(settings.protocol() == LlmApiProtocol.OPENAI_COMPLETIONS ? "messages" : "input");
        turns.add(JSON.objectNode().put("role", "user").put("content", nextTurn));
        return probe.toString();
    }

    private ObjectNode request() {
        var body = JSON.objectNode();
        body.put("model", settings.modelId());
        if (settings.protocol() == LlmApiProtocol.OPENAI_COMPLETIONS) {
            body.set("messages", history.deepCopy());
            body.put("store", false);
            body.put("max_completion_tokens", settings.maxOutputTokens());
            var list = body.putArray("tools");
            for (var tool : tools) {
                var function = JSON.objectNode().put("name", tool.name()).put("description", tool.description());
                function.set("parameters", tool.parameters());
                list.add(JSON.objectNode().put("type", "function").set("function", function));
            }
            if (settings.reasoningEffort() != null) body.put("reasoning_effort", effort(settings.reasoningEffort()));
        } else {
            body.put("instructions", system);
            body.set("input", history.deepCopy());
            body.put("store", false);
            body.put("max_output_tokens", Math.max(16, settings.maxOutputTokens()));
            var list = body.putArray("tools");
            for (var tool : tools) {
                var function = JSON.objectNode()
                        .put("type", "function")
                        .put("name", tool.name())
                        .put("description", tool.description());
                function.set("parameters", tool.parameters());
                function.put("strict", false);
                list.add(function);
            }
            if (settings.reasoningEffort() != null) {
                body.putObject("reasoning").put("effort", effort(settings.reasoningEffort()));
                body.putArray("include").add("reasoning.encrypted_content");
            }
        }
        if (settings.temperature() != null) body.put("temperature", settings.temperature());
        return body;
    }

    /** The wire effort: OpenAI's scale, with {@code NONE} as {@code none}. */
    private static String effort(ReasoningEffort effort) {
        return effort.name().toLowerCase(Locale.ROOT);
    }

    private Reply call() {
        calls++;
        Answer answer = transport.call(mapper.writeValueAsBytes(request()));
        if (!answer.owned()) return new Reply(null, List.of(), Failure.OWNERSHIP_LOST);
        var refusal = answer.refusal();
        if (refusal != null)
            return new Reply(
                    null,
                    List.of(),
                    switch (refusal) {
                        case AI_CHOICE -> Failure.AI_CHOICE;
                        case BUDGET -> Failure.BUDGET;
                        case WORK_TIME -> Failure.WORK_TIME;
                        case ATTEMPT_ENDED -> Failure.OWNERSHIP_LOST;
                    });
        int status = answer.status();
        if (status == 408 || status == 429 || status >= 500) return new Reply(null, List.of(), Failure.PROVIDER);
        // A provider's client error answers the same request the same way again.
        if (status < 200 || status >= 300) return new Reply(null, List.of(), Failure.REFUSED);
        JsonNode response;
        try {
            response = mapper.readTree(answer.body());
        } catch (JacksonException unreadable) {
            return new Reply(null, List.of(), Failure.PROVIDER);
        }
        return settings.protocol() == LlmApiProtocol.OPENAI_COMPLETIONS ? completion(response) : responses(response);
    }

    private Reply completion(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (!message.isObject()) return new Reply(null, List.of(), Failure.PROVIDER);
        var kept = JSON.objectNode().put("role", "assistant");
        JsonNode content = message.path("content");
        if (content.isString()) kept.put("content", content.asString());
        else kept.putNull("content");
        var calls = new ArrayList<ToolCall>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            kept.set("tool_calls", toolCalls);
            for (JsonNode call : toolCalls)
                calls.add(new ToolCall(
                        call.path("id").asString(""),
                        call.path("function").path("name").asString(""),
                        arguments(call.path("function").path("arguments"))));
        }
        JsonNode reasoning = message.path("reasoning_content");
        if (reasoning.isString()) kept.put("reasoning_content", reasoning.asString());
        history.add(kept);
        return new Reply(content.isString() ? content.asString() : null, calls, null);
    }

    private Reply responses(JsonNode response) {
        JsonNode output = response.path("output");
        if (!output.isArray()) return new Reply(null, List.of(), Failure.PROVIDER);
        var text = new StringBuilder();
        var calls = new ArrayList<ToolCall>();
        for (JsonNode item : output) {
            history.add(item);
            String type = item.path("type").asString("");
            if (type.equals("message"))
                for (JsonNode part : item.path("content"))
                    if (part.path("type").asString("").equals("output_text"))
                        text.append(part.path("text").asString(""));
            if (type.equals("function_call"))
                calls.add(new ToolCall(
                        item.path("call_id").asString(""),
                        item.path("name").asString(""),
                        arguments(item.path("arguments"))));
        }
        return new Reply(text.isEmpty() ? null : text.toString(), calls, null);
    }

    /** The arguments as JSON: a model sends them as a JSON string; anything unreadable stays the raw string. */
    private JsonNode arguments(JsonNode raw) {
        if (!raw.isString()) return raw;
        try {
            JsonNode parsed = mapper.readTree(raw.asString());
            return parsed == null ? raw : parsed;
        } catch (JacksonException unreadable) {
            return raw;
        }
    }
}
