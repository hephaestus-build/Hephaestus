package de.tum.cit.aet.hephaestus.agent.mentor.chat.wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Translates Pi {@code AgentSessionEvent} JSON into AI SDK {@link UIMessageChunk}s. Stateful
 * per turn; the caller threads a {@link TranslatorState} through every call. An event it cannot
 * account for — an unknown or malformed type, or streamed text that does not match the model's
 * final text — fails the turn with an {@link UIMessageChunk.Error} rather than being dropped.
 */
@Component
public class PiEventToUiChunkTranslator {

    private static final Logger log = LoggerFactory.getLogger(PiEventToUiChunkTranslator.class);

    public static final String REPLY_LOST_IN_TRANSIT = "Part of Heph's reply was lost in transit. Please try again.";

    public List<UIMessageChunk> translate(JsonNode piEvent, TranslatorState state) {
        if (piEvent == null || !piEvent.path("type").isString()) {
            return failTurn(state, "an event without a type");
        }
        String type = piEvent.get("type").asString();
        return switch (type) {
            case "message_start" -> handleMessageStart(piEvent, state);
            case "message_update" -> handleMessageUpdate(piEvent, state);
            case "message_end" -> handleMessageEnd(piEvent, state);
            case "tool_execution_start" -> handleToolStart(piEvent, state);
            case "tool_execution_end" -> handleToolEnd(piEvent, state);
            case "turn_end" -> handleTurnEnd(state);
            case "agent_end" -> handleAgentEnd(piEvent, state);
            case "link_observation" -> handleLinkObservation(piEvent, state);
            case "pi_error" -> handleError(piEvent, state);
            case "turn_watchdog_fired" -> handleWatchdogFired(state);
            case "session_persisted" -> handleSessionPersisted(piEvent, state);
            // Every other AgentSessionEvent of the pinned Pi SDK, none of which carries reply content.
            // An event not listed here may, so it fails the turn rather than vanishing: a Pi upgrade that
            // adds one must add it here.
            case "runner_ready",
                    "agent_start",
                    "turn_start",
                    "tool_execution_update",
                    "queue_update",
                    "compaction_start",
                    "compaction_end",
                    "entry_appended",
                    "session_info_changed",
                    "thinking_level_changed",
                    "auto_retry_start",
                    "auto_retry_end",
                    "summarization_retry_scheduled",
                    "summarization_retry_attempt_start",
                    "summarization_retry_finished",
                    "bash_execution_update" -> List.of();
            default -> failTurn(state, "an unknown event type '" + type + "'");
        };
    }

    /** The stream carried something this turn cannot account for, so its reply cannot be vouched for. */
    private List<UIMessageChunk> failTurn(TranslatorState state, String what) {
        log.warn("Mentor stream carried {}; failing the turn", what);
        state.markStreamBroken();
        List<UIMessageChunk> out = new ArrayList<>(closeOpenStreamingBlocks(state));
        out.add(new UIMessageChunk.Error(REPLY_LOST_IN_TRANSIT));
        return out;
    }

    // session_persisted → capture verbatim Pi session JSONL into state

    private List<UIMessageChunk> handleSessionPersisted(JsonNode event, TranslatorState state) {
        String jsonl = optionalString(event, "jsonl");
        if (jsonl == null || jsonl.isEmpty()) {
            // WARN, not DEBUG: a missing payload silently breaks prompt-cache continuity on the
            // next cold restart. Surfaces runner regressions in logs instead of an invisible miss.
            log.warn("session_persisted carried no jsonl payload — next cold restart will use stale bytes");
            return List.of();
        }
        state.observeSessionJsonl(jsonl.getBytes(StandardCharsets.UTF_8));
        return List.of();
    }

    /** Closing chunks for any block still open when a turn fails outside the event stream. */
    public List<UIMessageChunk> closeOpenBlocks(TranslatorState state) {
        return closeOpenStreamingBlocks(state);
    }

    // turn_watchdog_fired → Error with user-friendly text

    private List<UIMessageChunk> handleWatchdogFired(TranslatorState state) {
        // Runner emits this when the per-turn budget is exceeded; the bare type string
        // ("turn_watchdog_fired") leaked to the UI as the error text. Map to a user-facing
        // message; the runner-side correlate is logged at WARN already. Close any open
        // text block first so the AI SDK reducer doesn't crash on an `error` chunk
        // following an unmatched `*-start` (vercel/ai #11700).
        List<UIMessageChunk> out = new ArrayList<>(closeOpenStreamingBlocks(state));
        out.add(new UIMessageChunk.Error("Mentor turn timed out before completion."));
        return out;
    }

    // message_end → captures the authoritative usage snapshot and checks the streamed text against it

    private List<UIMessageChunk> handleMessageEnd(JsonNode event, TranslatorState state) {
        // Per pi-coding-agent dist/core/extensions/types.d.ts MessageEndEvent.message: AgentMessage.
        // For assistant messages the final, authoritative `usage` + `model` + `stopReason` live
        // here, as does the final `content`. For tool result and user messages this branch is a
        // no-op. The turn-level Finish chunk on agent_end carries the metadata to the client.
        // Capturing `stopReason` here is the authoritative source — agent_end's walk
        // through messages[] is a fallback for runners that omit message_end.
        JsonNode message = event.path("message");
        if (!"assistant".equals(optionalString(message, "role"))) return List.of();
        captureModel(message, state);
        state.completeUsage(message.path("usage"));
        String stopReason = optionalString(message, "stopReason");
        if (stopReason != null) {
            state.observeStopReason(stopReason);
        }
        return verifyAssistantText(state, textBlocks(message));
    }

    /**
     * Checks the streamed text of the last assistant message against its final text, closing its text
     * block. The client has already rendered the deltas, so a mismatch cannot be repaired on the wire:
     * the turn fails, and the stored message keeps the model's final text. {@code null} means no final
     * text exists to check against, which fails the same way.
     */
    private List<UIMessageChunk> verifyAssistantText(TranslatorState state, @Nullable List<String> finalText) {
        int streamedLength = state.unverifiedTextLength();
        String openTextId = state.activeTextId();
        boolean matched = state.verifyAssistantMessage(finalText);
        List<UIMessageChunk> out = new ArrayList<>(2);
        if (openTextId != null) {
            out.add(new UIMessageChunk.TextEnd(openTextId));
        }
        if (!matched) {
            log.warn(
                    "Streamed assistant text ({} chars) does not match its final text ({} chars); failing the turn",
                    streamedLength,
                    finalText == null
                            ? "no"
                            : finalText.stream().mapToInt(String::length).sum());
            out.add(new UIMessageChunk.Error(REPLY_LOST_IN_TRANSIT));
        }
        return out;
    }

    private static boolean failsTurn(List<UIMessageChunk> chunks) {
        return chunks.stream().anyMatch(UIMessageChunk.Error.class::isInstance);
    }

    private static List<String> textBlocks(JsonNode message) {
        List<String> blocks = new ArrayList<>();
        for (JsonNode block : message.path("content")) {
            String text = "text".equals(optionalString(block, "type")) ? optionalString(block, "text") : null;
            if (text != null) {
                blocks.add(text);
            }
        }
        return blocks;
    }

    // message_start / Start + StartStep

    private List<UIMessageChunk> handleMessageStart(JsonNode event, TranslatorState state) {
        String role = optionalString(event.path("message"), "role");
        if (!"assistant".equals(role)) {
            return List.of();
        }
        // A message still unverified here never delivered its message_end.
        if (state.hasUnverifiedAssistantMessage()) {
            List<UIMessageChunk> gap = verifyAssistantText(state, null);
            if (failsTurn(gap)) {
                return gap;
            }
        }
        // Capture model + any opening usage snapshot on the assistant message header.
        capturePartialUsage(event.path("message"), state);
        // Decoupled Start vs StartStep: if the orchestrator pre-emitted the Start chunk (so the
        // webapp's useChat reducer can show a placeholder during sandbox cold-start), the
        // translator must NOT re-emit Start — AI-SDK dedupes by message id but the duplicate is
        // wire-noise. The StartStep, however, is per-Pi-message and must always fire because
        // useChat's reducer pushes a {type: "step-start"} part for each one. Pi may emit
        // multiple message_start events across a single turn (one per assistant message in a
        // multi-step tool-using turn), each of which gets its own step.
        boolean firstStart = !state.isStarted();
        if (firstStart) {
            state.markStarted();
        }
        state.incrementStep();
        state.openAssistantMessage();
        if (firstStart) {
            return List.of(new UIMessageChunk.Start(state.assistantMessageId(), null), new UIMessageChunk.StartStep());
        }
        // Subsequent assistant message in the same turn (e.g. after a tool execution) — keep
        // the existing top-level Start the client already has, fire a fresh StartStep.
        return List.of(new UIMessageChunk.StartStep());
    }

    // message_update: text_delta only. Hidden reasoning and tool internals are never user-facing.

    private List<UIMessageChunk> handleMessageUpdate(JsonNode event, TranslatorState state) {
        // Pi shape: {type:"message_update", message: AgentMessage, assistantMessageEvent: {...}}
        // See pi-coding-agent extensions/types.ts MessageUpdateEvent for the union.
        JsonNode ame = event.path("assistantMessageEvent");
        if (!ame.path("type").isString()) {
            return failTurn(state, "a message_update without an assistantMessageEvent type");
        }
        return handleAssistantMessageEvent(ame, event, state);
    }

    /**
     * Translate the inner {@code assistantMessageEvent} payload Pi attaches to every
     * {@code message_update}. {@code contentIndex} is a stable per-message integer that lets us
     * keep concurrent text + reasoning blocks separate; we derive a stable {@code text-<n>} /
     * {@code reasoning-<n>} block id from it so subsequent deltas reconcile to the same
     * {@link UIMessageChunk.TextStart} on the AI SDK side. Usage is captured opportunistically:
     * {@code partial.usage} accumulates per chunk on most providers (per
     * {@code AssistantMessageEvent.partial: AssistantMessage}). It is also re-emitted as a final
     * snapshot on {@code message_end.message.usage}, which is the authoritative source.
     */
    private List<UIMessageChunk> handleAssistantMessageEvent(JsonNode ame, JsonNode parent, TranslatorState state) {
        String innerType = ame.get("type").asString();
        // Best-effort usage + model capture from `partial.usage` / `partial.model`. message_end
        // overwrites with the final snapshot. agent_end has no usage on the event itself.
        capturePartialUsage(ame.path("partial"), state);
        capturePartialUsage(parent.path("message"), state);
        String blockId = blockIdFor(ame, innerType);
        return switch (innerType) {
            case "text_delta" -> {
                String delta = optionalString(ame, "delta");
                yield delta == null
                        ? failTurn(state, "a text_delta without its text")
                        : textDelta(blockId, delta, state);
            }
            case "thinking_delta", "thinking_end" -> List.of();
            case "text_end" -> closeTextIfMatches(blockId, state);
            // The rest of pi-ai's AssistantMessageEvent union. Text opens lazily on its first delta,
            // hidden reasoning and tool internals are never user-facing, and message_end carries the
            // final message these lifecycle markers describe.
            case "text_start",
                    "thinking_start",
                    "toolcall_start",
                    "toolcall_delta",
                    "toolcall_end",
                    "start",
                    "done",
                    "error" -> List.of();
            default -> failTurn(state, "an unknown assistantMessageEvent type '" + innerType + "'");
        };
    }

    /**
     * Emit {@code TextEnd(id)} if the open text block matches {@code blockId}; close the
     * accumulator (which flushes the buffered text into {@code partsAccumulator}) regardless.
     * No-op if no text block is open or the id doesn't match (defends against stray closes).
     */
    private List<UIMessageChunk> closeTextIfMatches(String blockId, TranslatorState state) {
        if (blockId == null || !blockId.equals(state.activeTextId())) return List.of();
        state.closeTextBlock();
        return List.of(new UIMessageChunk.TextEnd(blockId));
    }

    private static String blockIdFor(JsonNode ame, String innerType) {
        // Pi's contentIndex is the per-message position of the content block. Map it to a stable
        // block id so concurrent deltas merge into the same TextStart on the AI SDK side. We
        // namespace by inner-type so text-0 and reasoning-0 never collide.
        JsonNode idx = ame.path("contentIndex");
        long index = idx.isIntegralNumber() ? idx.asLong() : 0L;
        return "text-" + index;
    }

    private static void capturePartialUsage(JsonNode messageNode, TranslatorState state) {
        if (messageNode == null || messageNode.isMissingNode() || !messageNode.isObject()) {
            return;
        }
        captureModel(messageNode, state);
        JsonNode usage = messageNode.path("usage");
        if (usage.isObject()) {
            state.observeUsage(usage);
        }
    }

    private static void captureModel(JsonNode messageNode, TranslatorState state) {
        if (messageNode.has("model") && messageNode.get("model").isString()) {
            state.observeModel(messageNode.get("model").asString());
        }
    }

    private List<UIMessageChunk> textDelta(@Nullable String blockId, String deltaText, TranslatorState state) {
        List<UIMessageChunk> out = new ArrayList<>(2);
        if (state.activeTextId() == null) {
            String id = blockId != null ? blockId : "text-" + UUID.randomUUID();
            state.openTextBlock(id);
            out.add(new UIMessageChunk.TextStart(id));
        }
        state.appendText(deltaText);
        out.add(new UIMessageChunk.TextDelta(Objects.requireNonNull(state.activeTextId()), deltaText));
        return out;
    }

    // tool_execution_start / end

    private List<UIMessageChunk> handleToolStart(JsonNode event, TranslatorState state) {
        return closeOpenStreamingBlocks(state);
    }

    private List<UIMessageChunk> handleToolEnd(JsonNode event, TranslatorState state) {
        return List.of();
    }

    // turn_end

    private List<UIMessageChunk> handleTurnEnd(TranslatorState state) {
        List<UIMessageChunk> out = closeOpenStreamingBlocks(state);
        state.decrementStep();
        out.add(new UIMessageChunk.FinishStep());
        return out;
    }

    private List<UIMessageChunk> closeOpenStreamingBlocks(TranslatorState state) {
        List<UIMessageChunk> out = new ArrayList<>(2);
        if (state.activeTextId() != null) {
            String id = state.activeTextId();
            state.closeTextBlock();
            out.add(new UIMessageChunk.TextEnd(id));
        }
        return out;
    }

    // agent_end → Finish

    private List<UIMessageChunk> handleAgentEnd(JsonNode event, TranslatorState state) {
        // Pi shape per pi-coding-agent/dist/core/extensions/types.d.ts AgentEndEvent:
        //   {type:"agent_end", messages: AgentMessage[]}
        // Crucially, agent_end has NO top-level `usage`, `model`, or `stopReason` — those live
        // on each AssistantMessage (verified pi-ai/src/types.ts:277-287). We walk the last
        // assistant message in `messages` to harvest model+usage+stopReason as a last-resort
        // fallback if neither message_update nor message_end ran.
        // Last-assistant-wins inside the messages[] walk — covers the multi-step case where
        // agent_end ships all assistant messages of the turn. If none of them carries a
        // stopReason (e.g. a stub runner or a Pi build that emits only message_end), fall back
        // to the authoritative value captured on `message_end.message.stopReason` via state.
        String piStopReason = null;
        List<JsonNode> finalUsages = new ArrayList<>();
        @Nullable JsonNode lastAssistant = null;
        List<JsonNode> assistants = new ArrayList<>();
        if (event.path("messages").isArray()) {
            for (JsonNode msg : event.get("messages")) {
                if (msg != null && msg.isObject() && "assistant".equals(optionalString(msg, "role"))) {
                    lastAssistant = msg;
                    assistants.add(msg);
                    captureModel(msg, state);
                    JsonNode usage = msg.path("usage");
                    if (usage.isObject() && !usage.isEmpty()) finalUsages.add(usage);
                    String reason = optionalString(msg, "stopReason");
                    if (reason != null) piStopReason = reason; // last assistant wins
                }
            }
        }
        // A last message whose message_end never arrived is checked against its copy here.
        List<UIMessageChunk> out = new ArrayList<>();
        if (state.hasUnverifiedAssistantMessage()) {
            out.addAll(verifyAssistantText(state, lastAssistant == null ? null : textBlocks(lastAssistant)));
            if (failsTurn(out)) {
                return out;
            }
        }
        // An assistant message whose every event was lost leaves nothing unverified, but its text is
        // still here; finishing without it would store a shorter answer as complete.
        List<String> finalTexts =
                assistants.stream().map(m -> String.join("", textBlocks(m))).toList();
        if (!state.endsWithVerified(finalTexts)) {
            out.addAll(failTurn(state, "agent_end text that did not stream in that order"));
            return out;
        }
        state.replaceCompletedUsage(finalUsages);
        if (piStopReason == null) {
            piStopReason = state.observedStopReason();
        }
        out.addAll(closeOpenStreamingBlocks(state));
        UIMessageChunk.MessageMetadata metadata = UIMessageChunk.MessageMetadata.of(
                state.observedModel(),
                UIMessageChunk.MessageMetadata.Usage.fromJsonNode(state.observedUsage()),
                /* costUsd, set by the persistence layer post-translation */ null);
        out.add(new UIMessageChunk.Finish(mapStopReason(piStopReason), metadata));
        return out;
    }

    /**
     * Map Pi {@code StopReason} ({@code stop|length|toolUse|error|aborted}, pi-ai/src/types.ts)
     * to the AI-SDK UIMessageStream {@code finishReason} enum
     * ({@code stop|length|content-filter|tool-calls|error|other}). The wider
     * {@code LanguageModelV2FinishReason} includes {@code "unknown"} but the chunk schema
     * does not — strict-zod parsing rejects it client-side. Unknown values map to {@code OTHER}.
     *
     * <p>{@code null} (Pi never reported a stop reason) maps to wire-null. Defaulting to
     * {@code STOP} would mask provider regressions (e.g. an upstream change dropping
     * {@code aborted}) and the AI-SDK schema accepts {@code finishReason} as optional.
     */
    static UIMessageChunk.@Nullable FinishReason mapStopReason(@Nullable String piStopReason) {
        if (piStopReason == null) return null;
        return switch (piStopReason) {
            case "stop" -> UIMessageChunk.FinishReason.STOP;
            case "length" -> UIMessageChunk.FinishReason.LENGTH;
            case "toolUse" -> UIMessageChunk.FinishReason.TOOL_CALLS;
            case "error", "aborted" -> UIMessageChunk.FinishReason.ERROR;
            default -> UIMessageChunk.FinishReason.OTHER;
        };
    }

    // link_observation → DataObservation

    private List<UIMessageChunk> handleLinkObservation(JsonNode event, TranslatorState state) {
        // Runner emits camelCase `observationId` (pi-mentor-runner.ts defineLinkObservationTool).
        // A link that cannot be read is feedback evidence this turn would silently lose, so it fails the turn.
        String observationIdStr = optionalString(event, "observationId");
        @Nullable UUID observationId = null;
        try {
            observationId = observationIdStr == null ? null : UUID.fromString(observationIdStr);
        } catch (IllegalArgumentException e) {
            // Falls through to the failure below.
        }
        if (observationId == null) {
            return failTurn(state, "a link_observation without a readable observationId");
        }
        state.recordDataObservation(observationId);
        return List.of(UIMessageChunk.DataObservation.of(observationId));
    }

    // pi_error / turn_watchdog_fired → Error

    private List<UIMessageChunk> handleError(JsonNode event, TranslatorState state) {
        String errorText = optionalString(event, "message");
        if (errorText == null) {
            errorText = optionalString(event, "error");
        }
        if (errorText == null) {
            errorText = event.get("type").asString();
        }
        // Close any open text/reasoning block before the terminal error chunk: the AI SDK
        // reducer crashes on an `error` chunk that follows an unmatched `*-start` (vercel/ai #11700).
        List<UIMessageChunk> out = new ArrayList<>(closeOpenStreamingBlocks(state));
        out.add(new UIMessageChunk.Error(errorText));
        return out;
    }

    // helpers

    /**
     * Return the {@code field}'s text value, or {@code null} if absent, JSON-null, or a non-text
     * shape (object, array, number, boolean). Surfacing {@code value.toString()} for non-textual
     * nodes would mask shape drift — a future Pi schema change that ships an object where we
     * expect a string would silently leak {@code "{\"id\":\"...\"}"} into the LLM-visible field.
     */
    @Nullable
    private static String optionalString(@Nullable JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isString()) return null;
        return value.asString();
    }
}
