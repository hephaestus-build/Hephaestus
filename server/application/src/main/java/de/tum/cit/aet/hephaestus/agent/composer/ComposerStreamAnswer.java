package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // The assembled text of one answer, its reasoning included: far above any output limit a composer asks for.
    private static final int MAX_TEXT = 4 * 1024 * 1024;

    // One event line; a completed response carries its whole output on one line.
    private static final int MAX_LINE = 16 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ComposerStreamAnswer.class);

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final LlmApiProtocol protocol;
    private final ObjectMapper mapper;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean afterCarriageReturn;
    private boolean finished;
    private long text;
    private boolean overflowed;
    private boolean failed;

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

    /** Reads lines ended by LF, CRLF or a lone CR, as the proxy's own event boundary does. */
    @Override
    public void accept(byte[] chunk) {
        if (overflowed || finished) return;
        int start = 0;
        for (int i = 0; i < chunk.length; i++) {
            byte b = chunk[i];
            if (b != '\n' && b != '\r') continue;
            boolean secondHalfOfCrLf = b == '\n' && afterCarriageReturn && i == start && line.size() == 0;
            afterCarriageReturn = b == '\r';
            if (!secondHalfOfCrLf) {
                line.write(chunk, start, i - start);
                endLine();
            }
            start = i + 1;
        }
        if (start < chunk.length) {
            afterCarriageReturn = false;
            if (line.size() + chunk.length - start > MAX_LINE) {
                overflow("an event line");
                return;
            }
            line.write(chunk, start, chunk.length - start);
        }
    }

    /**
     * The answer as the call answers without streaming; {@code null} when the stream did not reach its end, failed, or
     * was too large to keep. A last event that the provider did not end with a newline still counts.
     */
    public @Nullable JsonNode answer() {
        if (!finished && !overflowed && line.size() > 0) endLine();
        finished = true;
        if (overflowed || failed) return null;
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> completion();
            case ANTHROPIC_MESSAGES -> message();
            case OPENAI_RESPONSES -> response;
        };
    }

    private void endLine() {
        String raw = line.toString(StandardCharsets.UTF_8);
        line.reset();
        handle(raw);
    }

    private void handle(String raw) {
        if (!raw.startsWith("data:")) return;
        String data = raw.substring(5).strip();
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

    /** Counts assembled text; past the bound the answer is dropped, and the operator learns why. */
    private boolean keep(String added) {
        text += added.length();
        if (text <= MAX_TEXT) return true;
        overflow("its text");
        return false;
    }

    private void overflow(String what) {
        if (!overflowed)
            log.warn("Dropped a composer answer: {} passed the assembly bound, protocol={}", what, protocol.wire());
        overflowed = true;
    }

    // ------------------------------------------------------------ openai-completions

    private void chunk(JsonNode event) {
        // A provider that fails mid-stream sends an error instead of a choice; what came before is no answer.
        if (event.has("error")) failed = true;
        JsonNode choice = event.path("choices").path(0);
        if (!choice.isObject()) return;
        JsonNode delta = choice.path("delta");
        if (delta.path("content").isString() && keep(delta.get("content").asString())) {
            anyContent = true;
            content.append(delta.get("content").asString());
        }
        if (delta.path("reasoning_content").isString()
                && keep(delta.get("reasoning_content").asString())) {
            anyReasoning = true;
            reasoning.append(delta.get("reasoning_content").asString());
        }
        for (JsonNode call : delta.path("tool_calls")) {
            ToolCall assembled = toolCall(call);
            if (call.path("id").isString() && assembled.id.isEmpty())
                assembled.id = call.get("id").asString();
            JsonNode fn = call.path("function");
            // Some providers repeat the name in every delta: it is set once, as pi-ai sets it.
            if (fn.path("name").isString() && assembled.name.isEmpty())
                assembled.name = fn.get("name").asString();
            if (fn.path("arguments").isString() && keep(fn.get("arguments").asString()))
                assembled.arguments.append(fn.get("arguments").asString());
        }
        if (choice.path("finish_reason").isString()) {
            finishReason = choice.get("finish_reason").asString();
            if (finishReason.equals("error")) failed = true;
        }
    }

    /** The call a delta continues: by its index, else by its id, else the last one. */
    private ToolCall toolCall(JsonNode delta) {
        if (delta.path("index").isNumber())
            return calls.computeIfAbsent(delta.get("index").asInt(), index -> new ToolCall());
        String id = delta.path("id").asString("");
        if (!id.isEmpty()) {
            for (ToolCall known : calls.values()) if (known.id.equals(id)) return known;
            ToolCall started = new ToolCall();
            calls.put(calls.isEmpty() ? 0 : calls.lastKey() + 1, started);
            return started;
        }
        if (calls.isEmpty()) calls.put(0, new ToolCall());
        return calls.lastEntry().getValue();
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
                written.putObject("function").put("name", call.name).put("arguments", call.arguments.toString());
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
        private String name = "";
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
                if (block != null) keep(block.add(event.path("delta")));
            }
            case "message_delta" -> {
                JsonNode reason = event.path("delta").path("stop_reason");
                if (!reason.isMissingNode()) stopReason = reason;
            }
            case "message_stop" -> messageStopped = true;
            case "error" -> failed = true;
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

        /** Adds a delta's text, thinking, signature or tool input, and returns what it added. */
        private String add(JsonNode delta) {
            return switch (delta.path("type").asString("")) {
                case "text_delta" -> append("text", delta.path("text"));
                case "thinking_delta" -> append("thinking", delta.path("thinking"));
                case "signature_delta" -> append("signature", delta.path("signature"));
                case "input_json_delta" -> {
                    anyInput = true;
                    String partial = delta.path("partial_json").asString("");
                    input.append(partial);
                    yield partial;
                }
                default -> "";
            };
        }

        private String append(String field, JsonNode text) {
            if (!text.isString()) return "";
            texts.computeIfAbsent(
                            field, unused -> new StringBuilder(start.path(field).asString("")))
                    .append(text.asString());
            return text.asString();
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
