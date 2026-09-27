package de.tum.cit.aet.hephaestus.agent.mentor.chat.wire;

import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mutable per-turn translator state. Built incrementally as Pi events stream in, snapshotted at
 * end-of-turn into the persisted {@code chat_message.parts} (and read on disconnect/timeout paths
 * to flush whatever has been observed so far).
 *
 * <p>Thread-safety: writes happen on the runner-event dispatcher thread; snapshots can be read
 * from the orchestrator's virtual thread on a disconnect race. Every method synchronises on the
 * instance monitor so an {@link #partsSnapshot()} deepCopy sees a stable {@link ArrayNode}
 * (which is NOT thread-safe).
 */
public final class TranslatorState {

    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    /** Assistant message id — passed back on the {@link UIMessageChunk.Start} chunk for reconciliation. */
    private final UUID assistantMessageId;

    private @Nullable String activeTextId;

    /** Buffer of text so far for the open text block — used to materialise the final {@code text} part. */
    private final StringBuilder textBuffer = new StringBuilder();

    /** AI SDK UIMessage parts as accumulated. Order matches the stream; written to JSONB at end-of-turn. */
    private final ArrayNode partsAccumulator = nodes.arrayNode();

    /** Every text delta since the last verified assistant message, checked against that message's final text. */
    private final StringBuilder unverifiedText = new StringBuilder();

    private boolean assistantMessageOpen = false;

    /** Final texts of the assistant messages verified this turn, in order; empty texts are not kept. */
    private final List<String> verifiedTexts = new ArrayList<>();

    /** Index in {@link #partsAccumulator} where the unverified assistant message's parts begin. */
    private int messagePartsStart;

    /** The stream stopped being intact, so neither its reply nor its runner can be trusted. */
    private boolean streamBroken = false;

    /** Did we emit at least one {@code Start} chunk? Defensive — runner may replay an event. */
    private boolean started = false;

    private boolean llmCallStarted = false;

    /** Step counter — Pi internally tracks turns; we surface them as AI SDK steps for grouping. */
    private int stepDepth = 0;

    private final ObjectNode completedUsage = nodes.objectNode();

    @Nullable
    private JsonNode currentUsage;

    private int completedCallCount;

    /** Model id observed on the first AssistantMessage; used for pricing lookup. */
    @Nullable
    private String observedModel;

    /**
     * Authoritative Pi {@code stopReason} captured on {@code message_end.message.stopReason}.
     * The translator's {@code handleAgentEnd} uses this when present, falling back to a walk
     * over {@code agent_end.messages[]} only when no message_end was emitted.
     */
    @Nullable
    private String observedStopReason;

    /** Verbatim Pi SDK session JSONL captured from {@code session_persisted}; see {@link ChatThread#getSessionJsonl}. */
    private byte @Nullable [] observedSessionJsonl;

    /**
     * Which connection funds this turn's LLM calls, frozen at turn start. Unsynchronized unlike the
     * streaming mutators below: written once, before any runner event can race it.
     */
    @Nullable
    private FundingSource connectionScope;

    @Nullable
    private Long connectionId;

    @Nullable
    private String admittedModel;

    @Nullable
    private LlmPriceSnapshot admittedPrice;

    public TranslatorState(UUID assistantMessageId) {
        this.assistantMessageId = assistantMessageId;
    }

    public void bindConnection(@Nullable FundingSource connectionScope, @Nullable Long connectionId) {
        this.connectionScope = connectionScope;
        this.connectionId = connectionId;
    }

    public void bindAdmission(String model, @Nullable LlmPriceSnapshot price) {
        this.admittedModel = model;
        this.admittedPrice = price;
    }

    public @Nullable String admittedModel() {
        return admittedModel;
    }

    public @Nullable LlmPriceSnapshot admittedPrice() {
        return admittedPrice;
    }

    @Nullable
    public FundingSource connectionScope() {
        return connectionScope;
    }

    @Nullable
    public Long connectionId() {
        return connectionId;
    }

    public UUID assistantMessageId() {
        return assistantMessageId;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized void markStarted() {
        this.started = true;
    }

    public synchronized void markLlmCallStarted() {
        this.llmCallStarted = true;
    }

    public synchronized boolean hasLlmCallStarted() {
        return llmCallStarted;
    }

    public synchronized int incrementStep() {
        // Mirror the AI SDK reducer's `{type:"step-start"}` part so a rehydrated message renders
        // the same step boundaries the client built incrementally during streaming.
        ObjectNode stepStart = nodes.objectNode();
        stepStart.put("type", "step-start");
        partsAccumulator.add(stepStart);
        return ++stepDepth;
    }

    public synchronized int decrementStep() {
        // Pi may emit more turn_end than start-step (e.g. agent_end without a paired turn_end);
        // clamp the field itself, not just the return value, so the next incrementStep starts
        // from a sane base instead of climbing out of a negative hole.
        if (stepDepth > 0) {
            stepDepth--;
        }
        return stepDepth;
    }

    public synchronized @Nullable String activeTextId() {
        return activeTextId;
    }

    public synchronized void openTextBlock(String id) {
        this.activeTextId = id;
        this.textBuffer.setLength(0);
    }

    public synchronized void appendText(String delta) {
        this.textBuffer.append(delta);
        this.unverifiedText.append(delta);
    }

    public synchronized void openAssistantMessage() {
        assistantMessageOpen = true;
        messagePartsStart = partsAccumulator.size();
        unverifiedText.setLength(0);
    }

    /** An assistant message started or streamed text, and its final text has not been checked yet. */
    public synchronized boolean hasUnverifiedAssistantMessage() {
        return assistantMessageOpen || unverifiedText.length() > 0;
    }

    public synchronized int unverifiedTextLength() {
        return unverifiedText.length();
    }

    /**
     * Closes the open text block and checks the text streamed since the last verified assistant message
     * against that message's final text blocks. On a mismatch, or with no final text ({@code null}), the
     * stream is marked broken and the message's stored text parts are replaced by the final text, or
     * dropped when there is none; its other parts keep their place.
     *
     * @return whether the streamed text matched
     */
    public synchronized boolean verifyAssistantMessage(@Nullable List<String> finalTextBlocks) {
        closeTextBlock();
        boolean matched =
                finalTextBlocks != null && String.join("", finalTextBlocks).contentEquals(unverifiedText);
        if (!matched) {
            int insertAt = -1;
            for (int i = partsAccumulator.size() - 1; i >= messagePartsStart; i--) {
                if ("text".equals(partsAccumulator.get(i).path("type").asString(""))) {
                    partsAccumulator.remove(i);
                    insertAt = i;
                }
            }
            if (insertAt < 0) {
                insertAt = partsAccumulator.size();
            }
            if (finalTextBlocks != null) {
                for (String text : finalTextBlocks) {
                    if (!text.isEmpty()) {
                        partsAccumulator.insert(insertAt++, textPart(text));
                    }
                }
            }
            streamBroken = true;
        } else if (!unverifiedText.isEmpty()) {
            verifiedTexts.add(unverifiedText.toString());
        }
        assistantMessageOpen = false;
        unverifiedText.setLength(0);
        messagePartsStart = partsAccumulator.size();
        return matched;
    }

    /**
     * Whether the non-empty texts of a run's assistant messages, in order, are the last texts this turn
     * verified. A retried attempt's texts come before them, so only the tail has to match.
     */
    public synchronized boolean endsWithVerified(List<String> finalTexts) {
        List<String> expected =
                finalTexts.stream().filter(text -> !text.isEmpty()).toList();
        int offset = verifiedTexts.size() - expected.size();
        return offset >= 0
                && verifiedTexts.subList(offset, verifiedTexts.size()).equals(expected);
    }

    public synchronized void markStreamBroken() {
        streamBroken = true;
    }

    public synchronized boolean isStreamBroken() {
        return streamBroken;
    }

    public synchronized void closeTextBlock() {
        if (activeTextId != null && textBuffer.length() > 0) {
            partsAccumulator.add(textPart(textBuffer.toString()));
        }
        this.activeTextId = null;
        this.textBuffer.setLength(0);
    }

    private ObjectNode textPart(String text) {
        ObjectNode part = nodes.objectNode();
        part.put("type", "text");
        part.put("text", text);
        // "done" — terminal value of AI SDK's TextUIPart.state, so a rehydrated message
        // doesn't render an in-progress streaming cursor.
        part.put("state", "done");
        return part;
    }

    /** Stores the part exactly as {@code observation} went on the wire, so the reloaded reply matches the live one. */
    public synchronized void recordDataObservation(UIMessageChunk.DataObservation observation) {
        ObjectNode part = nodes.objectNode();
        part.put("type", UIMessageChunk.DataObservation.PART_TYPE);
        part.put("id", observation.id().toString());
        part.putObject("data")
                .put("observationId", observation.data().observationId().toString())
                .put("text", observation.data().text());
        partsAccumulator.add(part);
    }

    /**
     * Snapshot of the parts array; safe to persist to JSONB without further mutation. The
     * {@code synchronized} pairs with every mutator so a cross-thread snapshot from the
     * orchestrator vthread (timeout / disconnect / error paths) sees a stable
     * {@link ArrayNode} — {@code ArrayNode.deepCopy()} racing an {@code add(...)} from the
     * runner-event thread would otherwise surface as a {@code ConcurrentModificationException}.
     */
    public synchronized ArrayNode partsSnapshot() {
        return partsAccumulator.deepCopy();
    }

    public synchronized void observeUsage(JsonNode usage) {
        if (usage != null && usage.isObject() && !usage.isEmpty()) {
            this.currentUsage = usage.deepCopy();
        }
    }

    public synchronized void completeUsage(JsonNode usage) {
        if (usage != null && usage.isObject() && !usage.isEmpty()) {
            addUsage(completedUsage, usage);
            completedCallCount++;
        }
        currentUsage = null;
    }

    /** Authoritative totals from {@code agent_end}; a runner may omit them, keeping what was streamed. */
    public synchronized void replaceCompletedUsage(List<JsonNode> usages) {
        if (usages.isEmpty()) return;
        completedUsage.removeAll();
        for (JsonNode usage : usages) {
            addUsage(completedUsage, usage);
        }
        completedCallCount = usages.size();
        currentUsage = null;
    }

    /** Record the assistant message's model id; first non-blank wins (model rarely changes mid-turn). */
    public synchronized void observeModel(@Nullable String model) {
        if (model != null && !model.isBlank() && this.observedModel == null) {
            this.observedModel = model;
        }
    }

    private boolean hasObservedUsage() {
        return !completedUsage.isEmpty() || currentUsage != null;
    }

    @Nullable
    public synchronized JsonNode observedUsage() {
        if (!hasObservedUsage()) return null;
        ObjectNode total = completedUsage.deepCopy();
        if (currentUsage != null) addUsage(total, currentUsage);
        return total;
    }

    /** Calls represented by {@link #observedUsage()}, including an interrupted in-progress call. */
    public synchronized int observedCallCount() {
        return completedCallCount + (currentUsage != null ? 1 : 0);
    }

    private static void addUsage(ObjectNode target, JsonNode source) {
        source.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                ObjectNode nested = target.has(name) && target.get(name).isObject()
                        ? (ObjectNode) target.get(name)
                        : target.putObject(name);
                addUsage(nested, value);
            } else if (value.isIntegralNumber()) {
                long existing = target.has(name) && target.get(name).isNumber()
                        ? target.get(name).asLong()
                        : 0L;
                target.put(name, existing + value.asLong());
            } else if (value.isFloatingPointNumber()) {
                double existing = target.has(name) && target.get(name).isNumber()
                        ? target.get(name).asDouble()
                        : 0D;
                target.put(name, existing + value.asDouble());
            }
        });
    }

    @Nullable
    public synchronized String observedModel() {
        return observedModel;
    }

    /**
     * Record the authoritative Pi stop reason observed on {@code message_end}. Last write wins
     * because multi-step turns can emit multiple message_end events; the latest one is the
     * terminal reason for the turn. Blank values are ignored to defend against an empty-field
     * runner emit overwriting a real prior reason.
     */
    public synchronized void observeStopReason(@Nullable String stopReason) {
        if (stopReason != null && !stopReason.isBlank()) {
            this.observedStopReason = stopReason;
        }
    }

    @Nullable
    public synchronized String observedStopReason() {
        return observedStopReason;
    }

    public synchronized void observeSessionJsonl(byte @Nullable [] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        this.observedSessionJsonl = bytes.clone();
    }

    public synchronized byte @Nullable [] observedSessionJsonl() {
        return observedSessionJsonl;
    }
}
