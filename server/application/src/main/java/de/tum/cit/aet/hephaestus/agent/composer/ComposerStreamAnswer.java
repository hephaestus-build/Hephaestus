package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answer of a composer's streamed model call, assembled from the stream's events into the body that the same call
 * answers without streaming. The composer streams so that the proxy sees a long composition keep sending; the server
 * assembles the answer here once, records it in the {@link ComposerTranscript} and hands it to the composer as the
 * stream's last event, so the composer keeps exactly the reply that the transcript holds.
 *
 * <p>An answer exists only when the stream reached its protocol's end: the finish reason of a chat completion, the
 * {@code message_stop} of a message, or the completed or incomplete response. A stream cut before that has none.
 */
public final class ComposerStreamAnswer implements Consumer<byte[]> {

    /** The event that carries the assembled answer to the composer, after the upstream's own events. */
    public static final String EVENT = "hephaestus.answer";

    // A composer reply is a few kilobytes of notes and tool calls; a stream past this is not one to keep.
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final LlmApiProtocol protocol;
    private final ObjectMapper mapper;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private int seen;
    private boolean overflowed;

    // openai-completions
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private boolean anyContent;
    private boolean anyReasoning;
    private final TreeMap<Integer, ToolCall> calls = new TreeMap<>();
    private @Nullable String finishReason;

    // anthropic-messages
    private final TreeMap<Integer, Block> blocks = new TreeMap<>();
    private @Nullable JsonNode stopReason;
    private boolean messageStopped;

    // openai-responses
    private @Nullable JsonNode response;

    public ComposerStreamAnswer(LlmApiProtocol protocol, ObjectMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    @Override
    public void accept(byte[] chunk) {
        if (overflowed) return;
        seen += chunk.length;
        if (seen > MAX_BYTES) {
            overflowed = true;
            return;
        }
        int start = 0;
        for (int i = 0; i < chunk.length; i++) {
            if (chunk[i] != '\n') continue;
            line.write(chunk, start, i - start);
            handle(line.toString(StandardCharsets.UTF_8));
            line.reset();
            start = i + 1;
        }
        line.write(chunk, start, chunk.length - start);
    }

    /** The answer as the call answers without streaming; {@code null} when the stream did not reach its end. */
    public @Nullable JsonNode answer() {
        if (overflowed) return null;
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> completion();
            case ANTHROPIC_MESSAGES -> message();
            case OPENAI_RESPONSES -> response;
        };
    }

    private void handle(String raw) {
        String text = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (!text.startsWith("data:")) return;
        String data = text.substring(5).strip();
        if (data.isEmpty() || data.equals("[DONE]")) return;
        JsonNode event;
        try {
            event = mapper.readTree(data);
        } catch (JacksonException unreadable) {
            return;
        }
        if (!event.isObject()) return;
        switch (protocol) {
            case OPENAI_COMPLETIONS -> chunk(event);
            case ANTHROPIC_MESSAGES -> anthropic(event);
            case OPENAI_RESPONSES -> responses(event);
        }
    }

    // ------------------------------------------------------------ openai-completions

    private void chunk(JsonNode event) {
        JsonNode choice = event.path("choices").path(0);
        if (!choice.isObject()) return;
        JsonNode delta = choice.path("delta");
        if (delta.path("content").isString()) {
            anyContent = true;
            content.append(delta.get("content").asString());
        }
        if (delta.path("reasoning_content").isString()) {
            anyReasoning = true;
            reasoning.append(delta.get("reasoning_content").asString());
        }
        for (JsonNode call : delta.path("tool_calls")) {
            ToolCall assembled = calls.computeIfAbsent(call.path("index").asInt(0), index -> new ToolCall());
            if (call.path("id").isString()) assembled.id = call.get("id").asString();
            JsonNode fn = call.path("function");
            if (fn.path("name").isString()) assembled.name.append(fn.get("name").asString());
            if (fn.path("arguments").isString())
                assembled.arguments.append(fn.get("arguments").asString());
        }
        if (choice.path("finish_reason").isString())
            finishReason = choice.get("finish_reason").asString();
    }

    private @Nullable JsonNode completion() {
        if (finishReason == null) return null;
        ObjectNode message = JSON.objectNode().put("role", "assistant");
        if (anyContent) message.put("content", content.toString());
        else message.putNull("content");
        if (!calls.isEmpty()) {
            ArrayNode toolCalls = message.putArray("tool_calls");
            for (ToolCall call : calls.values()) {
                ObjectNode written = toolCalls.addObject().put("id", call.id).put("type", "function");
                written.putObject("function")
                        .put("name", call.name.toString())
                        .put("arguments", call.arguments.toString());
            }
        }
        if (anyReasoning) message.put("reasoning_content", reasoning.toString());
        ObjectNode answer = JSON.objectNode();
        answer.putArray("choices")
                .addObject()
                .put("index", 0)
                .put("finish_reason", finishReason)
                .set("message", message);
        return answer;
    }

    private static final class ToolCall {
        private String id = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    // ------------------------------------------------------------ anthropic-messages

    private void anthropic(JsonNode event) {
        switch (event.path("type").asString("")) {
            case "content_block_start" -> {
                JsonNode block = event.path("content_block");
                if (block.isObject())
                    blocks.put(event.path("index").asInt(0), new Block((ObjectNode) block.deepCopy()));
            }
            case "content_block_delta" -> {
                Block block = blocks.get(event.path("index").asInt(0));
                if (block != null) block.add(event.path("delta"));
            }
            case "message_delta" -> {
                JsonNode reason = event.path("delta").path("stop_reason");
                if (!reason.isMissingNode()) stopReason = reason;
            }
            case "message_stop" -> messageStopped = true;
            default -> {}
        }
    }

    private @Nullable JsonNode message() {
        if (!messageStopped) return null;
        ObjectNode answer = JSON.objectNode().put("type", "message").put("role", "assistant");
        ArrayNode content = answer.putArray("content");
        for (Block block : blocks.values()) content.add(block.written(mapper));
        answer.set("stop_reason", stopReason == null ? JSON.nullNode() : stopReason);
        return answer;
    }

    /** One content block of a message: its start, and the text, thinking, signature or tool input its deltas add. */
    private static final class Block {
        private final ObjectNode start;
        private final Map<String, StringBuilder> texts = new LinkedHashMap<>();
        private final StringBuilder input = new StringBuilder();
        private boolean anyInput;

        private Block(ObjectNode start) {
            this.start = start;
        }

        private void add(JsonNode delta) {
            switch (delta.path("type").asString("")) {
                case "text_delta" -> append("text", delta.path("text"));
                case "thinking_delta" -> append("thinking", delta.path("thinking"));
                case "signature_delta" -> append("signature", delta.path("signature"));
                case "input_json_delta" -> {
                    anyInput = true;
                    input.append(delta.path("partial_json").asString(""));
                }
                default -> {}
            }
        }

        private void append(String field, JsonNode text) {
            if (text.isString())
                texts.computeIfAbsent(
                                field,
                                unused -> new StringBuilder(start.path(field).asString("")))
                        .append(text.asString());
        }

        private JsonNode written(ObjectMapper mapper) {
            ObjectNode block = start.deepCopy();
            texts.forEach((field, text) -> block.put(field, text.toString()));
            if (!anyInput) return block;
            String json = input.toString().strip();
            try {
                block.set("input", json.isEmpty() ? JSON.objectNode() : mapper.readTree(json));
            } catch (JacksonException unreadable) {
                block.put("input", json);
            }
            return block;
        }
    }

    // ------------------------------------------------------------ openai-responses

    private void responses(JsonNode event) {
        String type = event.path("type").asString("");
        // An incomplete response answers as it does without streaming: with the output it reached.
        if ((type.equals("response.completed") || type.equals("response.incomplete"))
                && event.path("response").isObject()) response = event.get("response");
    }
}
