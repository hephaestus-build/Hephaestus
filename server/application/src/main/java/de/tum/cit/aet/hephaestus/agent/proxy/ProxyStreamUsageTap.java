package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
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
    /** Anthropic's {@code stop_reason} and OpenAI's {@code finish_reason}; the responses API's ride with usage. */
    private static final byte[] STOP_MARKER = "_reason".getBytes(StandardCharsets.US_ASCII);
    /** The responses API's terminal event of a failed answer, which can carry neither usage nor a reason. */
    private static final byte[] FAILED_MARKER = "response.failed".getBytes(StandardCharsets.US_ASCII);

    private final ObjectMapper objectMapper;
    private final LlmApiProtocol protocol;
    private final ObjectNode anthropicUsage;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(512);

    /** Set once a line outgrows the cap; cleared at the next newline so the stream resynchronises. */
    private boolean abandoningLine;

    @Nullable
    private ProxyTokenUsage observed;

    private boolean malformedUsage;

    private @Nullable String stopReason;

    private @Nullable LongConsumer firstEvent;

    /** Whether a content event arrived: text, reasoning or a tool call, not a role, an error or a comment. */
    private boolean content;

    private long sentAt = System.nanoTime();

    /** Written by the exchange's thread when the head arrives, read by the request thread for the first event. */
    private volatile boolean served = true;

    ProxyStreamUsageTap(ObjectMapper objectMapper, boolean responsesProtocol) {
        this(objectMapper, responsesProtocol ? LlmApiProtocol.OPENAI_RESPONSES : LlmApiProtocol.OPENAI_COMPLETIONS);
    }

    ProxyStreamUsageTap(ObjectMapper objectMapper, LlmApiProtocol protocol) {
        this.objectMapper = objectMapper;
        this.protocol = protocol;
        this.anthropicUsage = objectMapper.createObjectNode();
    }

    /**
     * Receives the nanoseconds from the request to the stream's first content event: the provider's first answer, after
     * any time in its queue. Only a served stream counts, as a failure that answers at once says nothing about a queue.
     * A role, an error or a comment can arrive at once from a provider or a gateway that serves nothing after it.
     */
    void onFirstContent(LongConsumer firstEvent) {
        this.firstEvent = firstEvent;
    }

    /** The request leaves now; a second one, such as a retry without our usage request, starts the clock again. */
    void sending() {
        sentAt = System.nanoTime();
        served = true;
        content = false;
    }

    /** Whether the stream carried any content event. */
    boolean hasContent() {
        return content;
    }

    /** No content and no usage: whatever the provider sent, it answered nothing. */
    boolean unanswered() {
        return !content && observed == null;
    }

    /** Nanoseconds since the request left. */
    long sinceSent() {
        return System.nanoTime() - sentAt;
    }

    void answered(boolean success) {
        served = success;
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
        if (from >= 0 && !content && carriesContent(line, from)) {
            content = true;
            LongConsumer first = firstEvent;
            firstEvent = null;
            if (first != null && served) first.accept(System.nanoTime() - sentAt);
        }
        if (from < 0
                || !(contains(line, USAGE_MARKER) || contains(line, STOP_MARKER) || contains(line, FAILED_MARKER))) {
            return;
        }
        try {
            JsonNode event = objectMapper.readTree(new String(line, from, line.length - from, StandardCharsets.UTF_8));
            if (!event.isObject()) {
                return;
            }
            String reason = ModelStopReason.of(event, protocol);
            if (reason != null) {
                stopReason = reason;
            }
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
        } catch (RuntimeException e) {
            malformedUsage = true;
            // The type only: a parser's message quotes the frame, which carries the model's answer.
            log.debug(
                    "Unparseable SSE frame while looking for usage: {}",
                    e.getClass().getName());
        }
    }

    /** Read only until the first content event, which is one or two events into a stream. */
    private boolean carriesContent(byte[] line, int from) {
        int start = from;
        while (start < line.length && line[start] == ' ') start++;
        if (start >= line.length || line[start] != '{') return false;
        try {
            JsonNode event =
                    objectMapper.readTree(new String(line, start, line.length - start, StandardCharsets.UTF_8));
            return switch (protocol) {
                case ANTHROPIC_MESSAGES -> event.path("type").asString("").equals("content_block_delta");
                case OPENAI_RESPONSES -> {
                    String type = event.path("type").asString("");
                    yield type.startsWith("response.") && type.endsWith(".delta");
                }
                default -> {
                    for (JsonNode choice : event.path("choices")) {
                        JsonNode delta = choice.path("delta");
                        for (String field : new String[] {"content", "reasoning_content", "reasoning"})
                            if (!delta.path(field).asString("").isEmpty()) yield true;
                        if (!delta.path("tool_calls").isEmpty()
                                || !choice.path("text").asString("").isEmpty()) yield true;
                    }
                    yield false;
                }
            };
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** The last usage the stream reported, or {@code null} when it reported none. */
    @Nullable
    ProxyTokenUsage observed() {
        return observed;
    }

    /** The last stop reason the stream reported, or {@code null} when it reported none. */
    @Nullable
    String stopReason() {
        return stopReason;
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
