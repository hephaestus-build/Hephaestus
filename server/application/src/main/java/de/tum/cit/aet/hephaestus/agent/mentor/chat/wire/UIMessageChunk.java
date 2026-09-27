package de.tum.cit.aet.hephaestus.agent.mentor.chat.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Sealed root of every chunk emitted on the mentor SSE stream. Wire-compatible with AI SDK
 * {@code UIMessageChunk} ({@code vercel/ai} → {@code packages/ai/src/ui-message-stream/ui-message-chunks.ts}).
 * Round-trip serialisation is locked down by {@code UIMessageChunkSerializationTest}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UIMessageChunk.Start.class, name = "start"),
    @JsonSubTypes.Type(value = UIMessageChunk.StartStep.class, name = "start-step"),
    @JsonSubTypes.Type(value = UIMessageChunk.FinishStep.class, name = "finish-step"),
    @JsonSubTypes.Type(value = UIMessageChunk.Finish.class, name = "finish"),
    @JsonSubTypes.Type(value = UIMessageChunk.TextStart.class, name = "text-start"),
    @JsonSubTypes.Type(value = UIMessageChunk.TextDelta.class, name = "text-delta"),
    @JsonSubTypes.Type(value = UIMessageChunk.TextEnd.class, name = "text-end"),
    @JsonSubTypes.Type(value = UIMessageChunk.ReasoningStart.class, name = "reasoning-start"),
    @JsonSubTypes.Type(value = UIMessageChunk.ReasoningDelta.class, name = "reasoning-delta"),
    @JsonSubTypes.Type(value = UIMessageChunk.ReasoningEnd.class, name = "reasoning-end"),
    @JsonSubTypes.Type(value = UIMessageChunk.ToolInputStart.class, name = "tool-input-start"),
    @JsonSubTypes.Type(value = UIMessageChunk.ToolInputAvailable.class, name = "tool-input-available"),
    @JsonSubTypes.Type(value = UIMessageChunk.ToolOutputAvailable.class, name = "tool-output-available"),
    @JsonSubTypes.Type(value = UIMessageChunk.ToolOutputError.class, name = "tool-output-error"),
    @JsonSubTypes.Type(value = UIMessageChunk.Error.class, name = "error"),
    @JsonSubTypes.Type(value = UIMessageChunk.DataMentorStatus.class, name = "data-mentor-status"),
    @JsonSubTypes.Type(value = UIMessageChunk.DataObservation.class, name = "data-observation"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface UIMessageChunk {
    /**
     * Spec-correct response header (RFC convention) announcing the AI SDK v1 stream protocol.
     * Set by the controller; the {@code DefaultChatTransport} on the webapp does NOT enforce it
     * (verified against ai-sdk default-chat-transport.ts + http-chat-transport.ts).
     */
    String RESPONSE_HEADER = "x-vercel-ai-ui-message-stream";

    /** Current AI SDK protocol version that {@link PiEventToUiChunkTranslator} emits. */
    String PROTOCOL_VERSION = "v1";

    /**
     * Lifecycle: first chunk of an assistant message. {@code messageId} should equal the DB
     * primary key of the persisted {@code chat_message} row so client-side {@code useChat}
     * reconciliation survives a page refresh.
     */
    record Start(@Nullable UUID messageId, @Nullable MessageMetadata messageMetadata) implements UIMessageChunk {}

    /** Lifecycle: open a step (Pi internal turn boundary). */
    record StartStep() implements UIMessageChunk {}

    /** Lifecycle: close a step. */
    record FinishStep() implements UIMessageChunk {}

    /**
     * Terminal chunk. The optional {@code messageMetadata} carries cost + usage so the webapp
     * (and downstream observability) can surface them.
     */
    record Finish(
            @Nullable FinishReason finishReason, @Nullable MessageMetadata messageMetadata) implements UIMessageChunk {}

    /**
     * AI SDK strict-zod enum for the {@code finish} chunk's {@code finishReason}
     * (vercel/ai packages/ai/src/ui-message-stream/ui-message-chunks.ts — {@code finishReasonSchema}).
     * Modelled as a Java enum so the type system rejects drift at compile time instead of the
     * AI SDK client rejecting the chunk at runtime.
     */
    enum FinishReason {
        STOP("stop"),
        LENGTH("length"),
        CONTENT_FILTER("content-filter"),
        TOOL_CALLS("tool-calls"),
        ERROR("error"),
        OTHER("other");

        private final String wire;

        FinishReason(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static FinishReason fromWire(String value) {
            for (FinishReason r : values()) {
                if (r.wire.equals(value)) return r;
            }
            throw new IllegalArgumentException("Unknown finishReason '" + value + "' — must be one of the AI SDK enum");
        }
    }

    /**
     * Typed metadata attached to {@link Finish} (and, for forward-compat, {@link Start}).
     * Webapp mirrors this in {@code webapp/src/lib/types.ts → MessageMetadata}; the two must
     * stay in lock-step. {@code @JsonInclude(NON_NULL)} ensures unknown fields are omitted on
     * the wire so the AI SDK strict-zod {@code messageMetadata} validator accepts the payload.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MessageMetadata(
            @Nullable String model,
            @Nullable Usage usage,
            @Nullable Double costUsd) {
        public static @Nullable MessageMetadata of(
                @Nullable String model, @Nullable Usage usage, @Nullable Double costUsd) {
            // Cheap "nothing observed" gate so we don't ship `{}` to clients (which the strict
            // validator still accepts but bloats every Finish chunk).
            if (model == null && usage == null && costUsd == null) return null;
            return new MessageMetadata(model, usage, costUsd);
        }

        /** Pi's {@code Usage} shape (pi-ai/src/types.ts) projected onto the AI SDK wire. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Usage(
                @Nullable Integer input,
                @Nullable Integer output,
                @Nullable Integer cacheRead,
                @Nullable Integer cacheWrite,
                @Nullable Integer totalTokens) {
            /** Build from a Pi JSONNode {@code usage} block; null-tolerant on every field. */
            public static @Nullable Usage fromJsonNode(@Nullable JsonNode node) {
                if (node == null || !node.isObject() || node.isEmpty()) return null;
                Integer in = readInt(node, "input");
                Integer out = readInt(node, "output");
                Integer cr = readInt(node, "cacheRead");
                Integer cw = readInt(node, "cacheWrite");
                Integer tt = readInt(node, "totalTokens");
                if (in == null && out == null && cr == null && cw == null && tt == null) return null;
                return new Usage(in, out, cr, cw, tt);
            }

            @Nullable
            private static Integer readInt(JsonNode node, String field) {
                JsonNode v = node.get(field);
                if (v == null || v.isNull() || !v.isIntegralNumber()) return null;
                return v.asInt();
            }
        }
    }

    /**
     * Open a streaming text block. Subsequent {@link TextDelta}s for this {@code id} append.
     * AI SDK's {@code readUIMessageStream} merges deltas into a single UIMessage text part.
     */
    record TextStart(String id) implements UIMessageChunk {}

    /** Append text fragment to the open block identified by {@code id}. */
    record TextDelta(String id, String delta) implements UIMessageChunk {}

    /** Close the streaming text block. */
    record TextEnd(String id) implements UIMessageChunk {}

    /** Open a streaming reasoning (chain-of-thought) block. */
    record ReasoningStart(String id) implements UIMessageChunk {}

    /** Append reasoning fragment. */
    record ReasoningDelta(String id, String delta) implements UIMessageChunk {}

    /** Close the reasoning block. */
    record ReasoningEnd(String id) implements UIMessageChunk {}

    /** Pi tool execution: tool selected; input streaming will begin. */
    record ToolInputStart(String toolCallId, String toolName) implements UIMessageChunk {}

    /** Pi tool execution: full input is now known. */
    record ToolInputAvailable(String toolCallId, String toolName, JsonNode input) implements UIMessageChunk {}

    /** Pi tool execution: tool produced an output successfully. */
    record ToolOutputAvailable(String toolCallId, JsonNode output) implements UIMessageChunk {}

    /** Pi tool execution: tool failed; {@code errorText} surfaces to the client UI. */
    record ToolOutputError(String toolCallId, String errorText) implements UIMessageChunk {}

    /** Fatal error during the turn; emitter completes after this chunk. */
    record Error(String errorText) implements UIMessageChunk {}

    /**
     * Hephaestus-specific data part — cold-start banner, container warming etc. Matches the
     * AI SDK strict-object schema for {@code data-*} chunks:
     * {@code {type, id?, data: unknown, transient?: boolean}}. {@code transient:true} hides
     * it from the persisted UIMessage parts array on the client.
     */
    record DataMentorStatus(
            @JsonProperty("id") String id,
            @JsonProperty("data") DataMentorStatusPayload data,
            @JsonProperty("transient") @Nullable Boolean transientFlag)
            implements UIMessageChunk {
        /** Stable id so subsequent status emits dedupe client-side instead of accumulating. */
        public static final String STATUS_PART_ID = "mentor-status";

        public record DataMentorStatusPayload(
                String state, @Nullable String reason) {}

        public static DataMentorStatus of(String state, @Nullable String reason) {
            return new DataMentorStatus(STATUS_PART_ID, new DataMentorStatusPayload(state, reason), Boolean.TRUE);
        }
    }

    /**
     * Hephaestus-specific data part emitted when Pi calls the {@code link_observation} custom tool: the feedback
     * the mentor wrote about one observation, shown to the developer as part of the reply. Permanent (NOT
     * transient), and the only place those words live — they are not Pi assistant text, so they never enter the
     * streamed-text verification. Each link gets its own {@code id}, because the AI SDK merges a data part into an
     * earlier one with the same type and id.
     */
    record DataObservation(
            @JsonProperty("id") UUID id,
            @JsonProperty("data") DataObservationPayload data) implements UIMessageChunk {
        public static final String PART_TYPE = "data-observation";

        public DataObservation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(data, "data");
        }

        public record DataObservationPayload(UUID observationId, String text) {}

        public static DataObservation of(UUID observationId, String text) {
            return new DataObservation(UUID.randomUUID(), new DataObservationPayload(observationId, text));
        }

        /**
         * The observations a stored reply showed the developer feedback about, in order: its data-observation
         * parts that carry text. A part stored before links carried text showed nothing, so it names none.
         */
        public static List<UUID> shownObservationIds(@Nullable JsonNode parts) {
            List<UUID> shown = new ArrayList<>();
            if (parts == null || !parts.isArray()) {
                return shown;
            }
            for (JsonNode part : parts) {
                JsonNode data = part.path("data");
                if (PART_TYPE.equals(part.path("type").asString(""))
                        && !data.path("text").asString("").isBlank()) {
                    try {
                        shown.add(UUID.fromString(data.path("observationId").asString("")));
                    } catch (IllegalArgumentException unreadable) {
                        // Not a link this server wrote; it settles nothing.
                    }
                }
            }
            return shown;
        }
    }
}
