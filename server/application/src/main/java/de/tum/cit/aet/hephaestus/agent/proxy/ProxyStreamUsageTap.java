package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a streamed call's token usage out of the SSE bytes on their way to the client. Without it a
 * streaming call would be unbillable, since the proxy returns as soon as it sees an SSE body.
 *
 * <p>An OpenAI-compatible stream reports usage at most once, at the end: chat-completions on the final
 * chunk, the responses API under {@code response.usage} of the terminal event — including
 * {@code response.incomplete} and {@code response.failed}, so a truncated response is still billed for
 * what it burned. The figure is cumulative for the whole response, so the tap replaces rather than
 * sums, and never estimates from the deltas.
 *
 * <p>Anthropic reports input and cache buckets in message_start and cumulative updates in
 * message_delta. A partial update keeps the other buckets, also when the stream stops early.
 *
 * <p>Not thread-safe: one instance serves one call, whose request thread both feeds it each chunk and reads
 * {@link #observed()} once the stream has returned.
 */
final class ProxyStreamUsageTap implements Consumer<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(ProxyStreamUsageTap.class);

    /** Bounds a provider that never sends a newline; far above any real SSE frame. */
    private static final int MAX_LINE_BYTES = 1024 * 1024;

    private static final byte[] DATA_PREFIX = "data:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] USAGE_MARKER = "usage".getBytes(StandardCharsets.US_ASCII);

    private final ObjectMapper objectMapper;
    private final LlmApiProtocol protocol;
    private final ObjectNode anthropicUsage;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(512);

    /** Set once a line outgrows the cap; cleared at the next newline so the stream resynchronises. */
    private boolean abandoningLine;

    @Nullable
    private ProxyTokenUsage observed;

    private boolean malformedUsage;
    private @Nullable Runnable firstToken;

    ProxyStreamUsageTap(ObjectMapper objectMapper, boolean responsesProtocol) {
        this(objectMapper, responsesProtocol ? LlmApiProtocol.OPENAI_RESPONSES : LlmApiProtocol.OPENAI_COMPLETIONS);
    }

    ProxyStreamUsageTap(ObjectMapper objectMapper, LlmApiProtocol protocol) {
        this.objectMapper = objectMapper;
        this.protocol = protocol;
        this.anthropicUsage = objectMapper.createObjectNode();
    }

    /** One callback for generated content; role frames, usage and keep-alives are not tokens. */
    void observeFirstToken(Runnable observer) {
        firstToken = observer;
    }

    @Override
    public void accept(byte[] chunk) {
        int lineStart = 0;
        for (int i = 0; i < chunk.length; i++) {
            if (chunk[i] != '\n') {
                continue;
            }
            if (abandoningLine) {
                abandoningLine = false;
            } else {
                pending.write(chunk, lineStart, i - lineStart);
                handleLine(pending.toByteArray());
            }
            pending.reset();
            lineStart = i + 1;
        }
        if (abandoningLine) {
            return;
        }
        int remaining = chunk.length - lineStart;
        if (pending.size() + remaining > MAX_LINE_BYTES) {
            log.warn("Abandoning an SSE frame longer than {} bytes; its usage (if any) goes unread", MAX_LINE_BYTES);
            pending.reset();
            abandoningLine = true;
            return;
        }
        pending.write(chunk, lineStart, remaining);
    }

    private void handleLine(byte[] line) {
        int from = startsWith(line, DATA_PREFIX) ? DATA_PREFIX.length : -1;
        if (from < 0) return;
        boolean usageCandidate = contains(line, USAGE_MARKER);
        if (!usageCandidate && firstToken == null) return;
        JsonNode event;
        try {
            event = objectMapper.readTree(new String(line, from, line.length - from, StandardCharsets.UTF_8));
            if (!event.isObject()) return;
        } catch (RuntimeException exception) {
            if (usageCandidate) malformedUsage(exception);
            return;
        }
        if (usageCandidate) {
            try {
                readUsage(event);
            } catch (RuntimeException exception) {
                malformedUsage(exception);
            }
        }
        Runnable observer = firstToken;
        if (observer != null && generatedContent(event)) {
            firstToken = null;
            try {
                observer.run();
            } catch (RuntimeException exception) {
                log.debug(
                        "Could not record first-token timing: {}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void malformedUsage(RuntimeException exception) {
        malformedUsage = true;
        // A parser message can quote the model's answer, so log only its type.
        log.debug(
                "Unparseable SSE frame while looking for usage: {}",
                exception.getClass().getName());
    }

    private void readUsage(JsonNode event) {
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
            String type = event.path("type").asString("");
            JsonNode usage = switch (type) {
                case "message_start" -> event.path("message").path("usage");
                case "message_delta" -> event.path("usage");
                default -> null;
            };
            if (usage != null && usage.isObject()) {
                ObjectNode merged = anthropicUsage.deepCopy();
                for (String field : new String[] {
                    "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"
                }) {
                    if (usage.hasNonNull(field)) merged.set(field, usage.get(field));
                }
                ProxyTokenUsage parsed =
                        ProxyTokenUsage.from(objectMapper.createObjectNode().set("usage", merged), protocol);
                anthropicUsage.setAll(merged);
                observed = parsed;
            }
            return;
        }
        boolean responsesProtocol = protocol == LlmApiProtocol.OPENAI_RESPONSES;
        // The responses API nests usage under the `response` envelope of the terminal event.
        ProxyTokenUsage parsed = ProxyTokenUsage.from(event, responsesProtocol);
        if (parsed == null) {
            parsed = ProxyTokenUsage.from(event.get("response"), responsesProtocol);
        }
        if (parsed != null) {
            observed = parsed;
        }
    }

    private boolean generatedContent(JsonNode event) {
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
            JsonNode delta = event.path("delta");
            if (event.path("type").asString("").equals("content_block_delta")) {
                return nonempty(delta.path("text"))
                        || nonempty(delta.path("thinking"))
                        || nonempty(delta.path("partial_json"));
            }
            return event.path("type").asString("").equals("content_block_start")
                    && (nonempty(event.path("content_block").path("text"))
                            || nonempty(event.path("content_block").path("name")));
        }
        if (protocol == LlmApiProtocol.OPENAI_RESPONSES) {
            return switch (event.path("type").asString("")) {
                case "response.output_text.delta",
                        "response.refusal.delta",
                        "response.reasoning_text.delta",
                        "response.reasoning_summary_text.delta",
                        "response.function_call_arguments.delta" -> nonempty(event.path("delta"));
                default -> false;
            };
        }
        for (JsonNode choice : event.path("choices")) {
            JsonNode delta = choice.path("delta");
            if (nonempty(delta.path("refusal"))
                    || nonempty(delta.path("content"))
                    || nonempty(delta.path("reasoning_content"))
                    || nonempty(delta.path("reasoning"))) return true;
            for (JsonNode tool : delta.path("tool_calls")) {
                if (nonempty(tool.path("function").path("name"))
                        || nonempty(tool.path("function").path("arguments"))) return true;
            }
        }
        return false;
    }

    private static boolean nonempty(JsonNode value) {
        return value.isString() && !value.asString().isEmpty();
    }

    /** The last usage the stream reported, or {@code null} when it reported none. */
    @Nullable
    ProxyTokenUsage observed() {
        return observed;
    }

    boolean hasMalformedUsage() {
        return malformedUsage;
    }

    private static boolean startsWith(byte[] line, byte[] prefix) {
        if (line.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (line[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] line, byte[] marker) {
        outer:
        for (int i = 0; i + marker.length <= line.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (line[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
