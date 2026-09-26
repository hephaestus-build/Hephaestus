package de.tum.cit.aet.hephaestus.agent.mentor.chat.wire;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real-shape coverage for {@link PiEventToUiChunkTranslator}. Every test loads a fixture file from
 * {@code src/test/resources/agent/mentor/pi-events/} that mirrors the on-the-wire JSON Pi emits
 * — verified against {@code @earendil-works/pi-coding-agent} dist .d.ts shapes. We intentionally
 * do NOT shape the fixtures from what the runner's protocol-only stub produces: a stub-shaped
 * fixture cannot catch a wire-format mismatch (e.g. the translator reading snake_case `delta_type`
 * while real Pi sends camelCase `assistantMessageEvent.type`).
 *
 * <p>If you change a Pi event mapping, change the fixture too. Stub-only shapes (the synthetic
 * {@code pi_error} / {@code turn_watchdog_fired} the runner itself emits) are exercised inline.
 */
class PiEventToUiChunkTranslatorTest extends BaseUnitTest {

    private static final String FIXTURE_DIR = "agent/mentor/pi-events/";

    private final ObjectMapper mapper = new ObjectMapper();
    private PiEventToUiChunkTranslator translator;
    private TranslatorState state;
    private final UUID assistantMessageId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        translator = new PiEventToUiChunkTranslator();
        state = new TranslatorState(assistantMessageId);
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE_DIR + name)) {
            assertThat(in)
                    .as("fixture %s must exist on the test classpath", name)
                    .isNotNull();
            return mapper.readTree(in);
        }
    }

    // message_start

    @Test
    void messageStart_assistant_emitsStartAndCapturesModel() throws Exception {
        JsonNode event = fixture("message_start_assistant.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("Start", "StartStep");
        UIMessageChunk.Start start = (UIMessageChunk.Start) out.get(0);
        assertThat(start.messageId()).isEqualTo(assistantMessageId);
        // model lives on the message's role-bearing object, NOT a top-level event field.
        assertThat(state.observedModel()).isEqualTo("claude-3-5-haiku-20241022");
    }

    @Test
    void messageStart_userMessage_dropped() throws Exception {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "message_start");
        event.putObject("message").put("role", "user");
        assertThat(translator.translate(event, state)).isEmpty();
        assertThat(state.isStarted()).isFalse();
    }

    @Test
    void messageStart_withoutMessageRole_dropped() throws Exception {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "message_start");
        event.putObject("message").put("model", "test-model");
        event.put("role", "assistant");

        assertThat(translator.translate(event, state)).isEmpty();
        assertThat(state.isStarted()).isFalse();
    }

    @Test
    void messageStart_afterOrchestratorMarkedStarted_emitsStartStepOnly() throws Exception {
        // Orchestrator pre-emits Start at turn open, then markStarted() to suppress duplicate.
        state.markStarted();
        JsonNode event = fixture("message_start_assistant.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("StartStep");
    }

    @Test
    @DisplayName("message_start fired twice in one turn (multi-step turn): Start once, StartStep per step")
    void messageStart_multipleAssistantMessages_secondEmitsStartStepOnly() throws Exception {
        JsonNode event = fixture("message_start_assistant.json");

        List<UIMessageChunk> first = translator.translate(event, state);
        translator.translate(messageEnd(), state);
        List<UIMessageChunk> second = translator.translate(event, state);

        assertThat(first).extracting(c -> c.getClass().getSimpleName()).containsExactly("Start", "StartStep");
        assertThat(second).extracting(c -> c.getClass().getSimpleName()).containsExactly("StartStep");
    }

    // message_update (Pi's authoritative shape)

    @Test
    void delta_type_translatesTo_textDelta_chunk() throws Exception {
        JsonNode event = fixture("message_update_text_delta.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextStart", "TextDelta");
        UIMessageChunk.TextDelta delta = (UIMessageChunk.TextDelta) out.get(1);
        assertThat(delta.delta()).isEqualTo("hel");
        // contentIndex=0 → block id "text-0" (stable across deltas).
        assertThat(((UIMessageChunk.TextStart) out.get(0)).id()).isEqualTo("text-0");
        assertThat(state.observedUsage()).isNotNull();
        assertThat(state.observedUsage().get("input").asInt()).isEqualTo(12);
        assertThat(state.observedModel()).isEqualTo("claude-3-5-haiku-20241022");
    }

    @Test
    void delta_type_reusesBlockIdAcrossDeltas() throws Exception {
        JsonNode first = fixture("message_update_text_delta.json");
        translator.translate(first, state);

        ObjectNode second = (ObjectNode) first.deepCopy();
        ((ObjectNode) second.get("assistantMessageEvent")).put("delta", "lo");
        List<UIMessageChunk> out = translator.translate(second, state);

        // Just a TextDelta — TextStart already fired on the first delta, and both deltas must
        // reuse the same block id so the AI SDK reconciler merges them into one part.
        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextDelta");
        assertThat(((UIMessageChunk.TextDelta) out.get(0)).id()).isEqualTo("text-0");
    }

    @Test
    @DisplayName("thinking_delta is hidden from user-visible stream")
    void delta_type_dropsThinkingDelta() throws Exception {
        JsonNode event = fixture("message_update_thinking_delta.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).isEmpty();
    }

    // message_end (captures final usage but emits no UI chunks)

    @Test
    void messageEnd_capturesUsage_emitsNothing() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("Hello!");
        translator.translate(mapper.readTree("{\"type\":\"turn_end\"}"), state);

        List<UIMessageChunk> out = translator.translate(fixture("message_end_assistant.json"), state);

        assertThat(out).isEmpty();
        assertThat(state.observedUsage()).isNotNull();
        assertThat(state.observedUsage().get("output").asInt()).isEqualTo(5);
        assertThat(state.observedUsage().get("cacheRead").asInt()).isEqualTo(2);
        assertThat(state.observedUsage().path("cost").path("total").asDouble()).isEqualTo(0.00002715);
        assertThat(state.observedModel()).isEqualTo("claude-3-5-haiku-20241022");
    }

    @Test
    void multiStepTurn_sumsEveryCompletedAssistantCall() throws Exception {
        ObjectNode first = (ObjectNode) fixture("message_end_assistant.json").deepCopy();
        ObjectNode second = (ObjectNode) fixture("message_end_assistant.json").deepCopy();
        ((ObjectNode) second.path("message").path("usage"))
                .put("input", 40)
                .put("output", 10)
                .put("cacheRead", 3)
                .put("cacheWrite", 2)
                .put("totalTokens", 55);

        translator.translate(first, state);
        translator.translate(second, state);

        ObjectNode end = mapper.createObjectNode();
        end.put("type", "agent_end");
        end.putArray("messages");
        UIMessageChunk.Finish finish =
                (UIMessageChunk.Finish) translator.translate(end, state).getLast();
        assertThat(finish.messageMetadata()).isNotNull();
        assertThat(finish.messageMetadata().usage()).isNotNull();

        assertThat(finish.messageMetadata().usage().input()).isEqualTo(65);
        assertThat(finish.messageMetadata().usage().output()).isEqualTo(15);
        assertThat(finish.messageMetadata().usage().cacheRead()).isEqualTo(5);
        assertThat(finish.messageMetadata().usage().cacheWrite()).isEqualTo(3);
        assertThat(finish.messageMetadata().usage().totalTokens()).isEqualTo(88);
        assertThat(state.observedCallCount()).isEqualTo(2);
    }

    // tool_execution_start / end with real camelCase shapes

    @Test
    void tool_call_start_emits_inputAvailable() throws Exception {
        JsonNode event = fixture("tool_execution_start.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).isEmpty();
    }

    @Test
    void tool_call_success_emits_outputAvailable() throws Exception {
        translator.translate(fixture("tool_execution_start.json"), state);
        JsonNode event = fixture("tool_execution_end_success.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).isEmpty();
    }

    @Test
    void tool_call_failure_emits_outputError_withResultText() throws Exception {
        // Start a different call id so name lookup hits.
        ObjectNode start = mapper.createObjectNode();
        start.put("type", "tool_execution_start");
        start.put("toolCallId", "call-real-2");
        start.put("toolName", "fetch_context");
        start.putObject("args").put("path", "foo");
        translator.translate(start, state);

        JsonNode event = fixture("tool_execution_end_error.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).isEmpty();
    }

    @Test
    void tool_call_end_unknownStatus_treatedAsSuccess() throws Exception {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "tool_execution_end");
        event.put("toolCallId", "x");
        event.putObject("result")
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "ok");
        List<UIMessageChunk> out = translator.translate(event, state);
        assertThat(out).isEmpty();
    }

    // agent_end (no usage on event itself — harvest from messages[])

    @Test
    void agentEnd_noEventUsage_harvestsFromMessages() throws Exception {
        streamDeltas("Hi there!");
        JsonNode event = fixture("agent_end_no_usage.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        UIMessageChunk.Finish finish = (UIMessageChunk.Finish) out.get(out.size() - 1);
        assertThat(finish.messageMetadata()).isNotNull();
        assertThat(finish.messageMetadata().usage()).isNotNull();
        assertThat(finish.messageMetadata()).isNotNull();
        assertThat(finish.messageMetadata().model()).isEqualTo("claude-3-5-haiku-20241022");
        assertThat(finish.messageMetadata().usage()).isNotNull();
        assertThat(finish.messageMetadata().usage().input()).isEqualTo(25);
        assertThat(finish.messageMetadata().usage().output()).isEqualTo(5);
    }

    @Test
    void agentEndMessagesAreTheAuthoritativeTurnTotal() throws Exception {
        translator.translate(fixture("message_end_assistant.json"), state);
        // The terminal event contains the complete assistant-message set for the turn, so it
        // replaces streaming observations rather than double-counting them.
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "agent_end");
        event.putArray("messages")
                .addObject()
                .put("role", "assistant")
                .putObject("usage")
                .put("input", 999)
                .put("output", 999);

        List<UIMessageChunk> out = translator.translate(event, state);

        UIMessageChunk.Finish finish = (UIMessageChunk.Finish) out.get(out.size() - 1);
        var metadata = finish.messageMetadata();
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        var usage = metadata.usage();
        org.junit.jupiter.api.Assertions.assertNotNull(usage);
        assertThat(usage.input()).isEqualTo(999);
        assertThat(state.observedCallCount()).isEqualTo(1);
    }

    @Test
    void agentEnd_emptyMessages_noMetadata() throws Exception {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "agent_end");
        event.putArray("messages");
        List<UIMessageChunk> out = translator.translate(event, state);
        UIMessageChunk.Finish finish = (UIMessageChunk.Finish) out.get(out.size() - 1);
        assertThat(finish.messageMetadata()).isNull();
    }

    @Test
    void agentEnd_mapsStopReasonToAiSdkEnum() {
        // Pi StopReason union: stop | length | toolUse | error | aborted (pi-ai/src/types.ts:269)
        // AI SDK union: stop | length | content-filter | tool-calls | error | other (ui-message-chunks.ts)
        // Anything raw-Pi reaching the wire would be a strict-zod rejection at the client.
        assertThat(PiEventToUiChunkTranslator.mapStopReason("stop")).isSameAs(UIMessageChunk.FinishReason.STOP);
        assertThat(PiEventToUiChunkTranslator.mapStopReason("length")).isSameAs(UIMessageChunk.FinishReason.LENGTH);
        assertThat(PiEventToUiChunkTranslator.mapStopReason("toolUse"))
                .isSameAs(UIMessageChunk.FinishReason.TOOL_CALLS);
        assertThat(PiEventToUiChunkTranslator.mapStopReason("error")).isSameAs(UIMessageChunk.FinishReason.ERROR);
        assertThat(PiEventToUiChunkTranslator.mapStopReason("aborted")).isSameAs(UIMessageChunk.FinishReason.ERROR);
        assertThat(PiEventToUiChunkTranslator.mapStopReason("future-pi-reason"))
                .isSameAs(UIMessageChunk.FinishReason.OTHER);
        // Null Pi-side stopReason → wire-null (AI-SDK schema accepts finishReason as optional).
        // Don't default to STOP — that would mask provider regressions that drop the field.
        assertThat(PiEventToUiChunkTranslator.mapStopReason(null)).isNull();
    }

    @Test
    void agentEnd_extractsStopReasonFromLastAssistant() {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "agent_end");
        var msgs = event.putArray("messages");
        msgs.addObject().put("role", "user").put("stopReason", "irrelevant");
        msgs.addObject().put("role", "assistant").put("stopReason", "toolUse");
        msgs.addObject().put("role", "assistant").put("stopReason", "stop"); // last wins

        List<UIMessageChunk> out = translator.translate(event, state);

        UIMessageChunk.Finish finish = (UIMessageChunk.Finish) out.get(out.size() - 1);
        assertThat(finish.finishReason()).isSameAs(UIMessageChunk.FinishReason.STOP);
    }

    // turn_end + open block closure

    @Test
    void turnEnd_closesAllOpenBlocks() throws Exception {
        translator.translate(fixture("message_update_text_delta.json"), state);
        translator.translate(fixture("message_update_thinking_delta.json"), state);

        List<UIMessageChunk> out = translator.translate(mapper.readTree("{\"type\":\"turn_end\"}"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "FinishStep");
    }

    @Test
    void toolStart_closesOpenText() throws Exception {
        translator.translate(fixture("message_update_text_delta.json"), state);
        List<UIMessageChunk> out = translator.translate(fixture("tool_execution_start.json"), state);
        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd");
    }

    // link_observation (runner-emitted, camelCase canonical)

    @Test
    void linkObservation_camelCase_emitsDataObservation() throws Exception {
        JsonNode event = fixture("runner_link_observation.json");

        List<UIMessageChunk> out = translator.translate(event, state);

        assertThat(out).hasSize(1);
        UIMessageChunk.DataObservation df = (UIMessageChunk.DataObservation) out.get(0);
        assertThat(df.data().observationId()).isEqualTo(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertThat(df.id()).isEqualTo(df.data().observationId());
    }

    // synthetic runner events (snake-case, runner-owned)

    @Test
    void piError_emitsError() throws Exception {
        JsonNode event = mapper.readTree("{\"type\":\"pi_error\",\"error\":\"upstream timeout\"}");
        List<UIMessageChunk> out = translator.translate(event, state);
        assertThat(out).hasSize(1).first().isInstanceOf(UIMessageChunk.Error.class);
    }

    @Test
    void watchdogFired_emitsError() throws Exception {
        JsonNode event = mapper.readTree("{\"type\":\"turn_watchdog_fired\",\"threadId\":\"t\"}");
        assertThat(translator.translate(event, state)).hasSize(1).first().isInstanceOf(UIMessageChunk.Error.class);
    }

    @Test
    void runnerReady_dropped() throws Exception {
        assertThat(translator.translate(mapper.readTree("{\"type\":\"runner_ready\"}"), state))
                .isEmpty();
    }

    @Test
    void shouldFailTheTurnOnAnUnknownEventType() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("See ");

        // A renamed link_observation must not disappear while the reply still finishes.
        List<UIMessageChunk> out = translator.translate(
                mapper.readTree("{\"type\":\"link_observations\",\"observationId\":\"" + UUID.randomUUID() + "\"}"),
                state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(state.isStreamBroken()).isTrue();
    }

    @Test
    void piSessionLevelEvents_explicitlyDropped() throws Exception {
        // Unknown types fail the turn, so every housekeeping event Pi 0.84.4 emits must stay a no-op.
        String[] sessionEvents = {
            "agent_start",
            "turn_start",
            "tool_execution_update",
            "queue_update",
            "compaction_start",
            "compaction_end",
            "session_info_changed",
            "thinking_level_changed",
            "auto_retry_start",
            "auto_retry_end",
            "entry_appended",
            "summarization_retry_scheduled",
            "summarization_retry_attempt_start",
            "summarization_retry_finished",
            "bash_execution_update",
            "runner_ready",
        };
        for (String type : sessionEvents) {
            JsonNode event = mapper.readTree("{\"type\":\"" + type + "\"}");
            assertThat(translator.translate(event, state))
                    .as("session-level event %s must be an explicit no-op", type)
                    .isEmpty();
        }
    }

    @Test
    void piError_closesOpenStreamingBlock() throws Exception {
        // Drive a text-start + delta so an open block exists, then fire pi_error. The AI SDK
        // client reducer crashes on an `error` chunk that follows an unmatched `*-start` — we
        // must emit `text-end` first.
        JsonNode start = fixture("message_start_assistant.json");
        translator.translate(start, state);
        JsonNode delta = fixture("message_update_text_delta.json");
        translator.translate(delta, state);
        JsonNode err = mapper.readTree("{\"type\":\"pi_error\",\"error\":\"upstream timeout\"}");
        List<UIMessageChunk> out = translator.translate(err, state);
        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
    }

    @Test
    void watchdogFired_closesOpenStreamingBlock() throws Exception {
        JsonNode start = fixture("message_start_assistant.json");
        translator.translate(start, state);
        JsonNode delta = fixture("message_update_text_delta.json");
        translator.translate(delta, state);
        JsonNode watchdog = mapper.readTree("{\"type\":\"turn_watchdog_fired\"}");
        List<UIMessageChunk> out = translator.translate(watchdog, state);
        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
    }

    @Test
    void turnWatchdogFired_userFriendlyText() throws Exception {
        JsonNode event = mapper.readTree("{\"type\":\"turn_watchdog_fired\",\"threadId\":\"t\"}");
        List<UIMessageChunk> out = translator.translate(event, state);
        assertThat(out).hasSize(1).first().isInstanceOf(UIMessageChunk.Error.class);
        UIMessageChunk.Error err = (UIMessageChunk.Error) out.get(0);
        // Must be human-readable, not the raw wire symbol "turn_watchdog_fired".
        assertThat(err.errorText()).doesNotContain("turn_watchdog_fired").contains("timed out");
    }

    @Test
    void decrementStep_clampsField() {
        // Pi can emit more turn_end than start-step (e.g. agent_end without a paired turn_end).
        // The field itself must clamp at 0; if it goes negative, the next incrementStep would
        // return 0 instead of 1 and the step-start part would not advance.
        state.decrementStep();
        state.decrementStep();
        int after = state.incrementStep();
        assertThat(after)
                .as("incrementStep after over-decrements should yield 1, not 0")
                .isEqualTo(1);
    }

    @Test
    void messageEndStopReason_consumedByAgentEnd() throws Exception {
        // message_end carries the authoritative stopReason for the assistant message.
        ObjectNode messageEnd = mapper.createObjectNode();
        messageEnd.put("type", "message_end");
        ObjectNode msg = messageEnd.putObject("message");
        msg.put("role", "assistant");
        msg.put("stopReason", "length");
        translator.translate(messageEnd, state);
        assertThat(state.observedStopReason()).isEqualTo("length");

        ObjectNode agentEnd = mapper.createObjectNode();
        agentEnd.put("type", "agent_end");
        agentEnd.putArray("messages");
        List<UIMessageChunk> out = translator.translate(agentEnd, state);
        UIMessageChunk.Finish finish = (UIMessageChunk.Finish) out.get(out.size() - 1);
        assertThat(finish.finishReason()).isSameAs(UIMessageChunk.FinishReason.LENGTH);
    }

    @Test
    void thinkingEndDoesNotPersistHiddenReasoning() throws Exception {
        ObjectNode reasoningDelta = mapper.createObjectNode();
        reasoningDelta.put("type", "message_update");
        ObjectNode ame1 = reasoningDelta.putObject("assistantMessageEvent");
        ame1.put("type", "thinking_delta");
        ame1.put("contentIndex", 0);
        ame1.put("delta", "Let me think");
        translator.translate(reasoningDelta, state);

        ObjectNode reasoningEnd = mapper.createObjectNode();
        reasoningEnd.put("type", "message_update");
        ObjectNode ame2 = reasoningEnd.putObject("assistantMessageEvent");
        ame2.put("type", "thinking_end");
        ame2.put("contentIndex", 0);

        assertThat(translator.translate(reasoningEnd, state)).isEmpty();
        assertThat(state.partsSnapshot()).isEmpty();
    }

    @Test
    void malformedEvent_failsTheTurn() {
        assertThat(translator.translate(mapper.createObjectNode().put("noType", true), state))
                .extracting(c -> c.getClass().getSimpleName())
                .containsExactly("Error");
    }

    // parts accumulation

    @Test
    void stateAccumulatesPartsAcrossDeltas() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        translator.translate(fixture("message_update_text_delta.json"), state);
        ObjectNode second =
                (ObjectNode) fixture("message_update_text_delta.json").deepCopy();
        ((ObjectNode) second.get("assistantMessageEvent")).put("delta", "lo");
        translator.translate(second, state);
        translator.translate(mapper.readTree("{\"type\":\"turn_end\"}"), state);

        // AI SDK's reducer pushes {type:"step-start"} on every start-step chunk; we mirror that
        // here so the persisted UIMessage round-trips correctly through safeValidateUIMessages.
        JsonNode snapshot = state.partsSnapshot();
        assertThat(snapshot.isArray()).isTrue();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).get("type").asString()).isEqualTo("step-start");
        assertThat(snapshot.get(1).get("type").asString()).isEqualTo("text");
        assertThat(snapshot.get(1).get("text").asString()).isEqualTo("hello");
    }

    @Test
    void shouldOnlyCloseTheTextWhenStreamedTextMatchesTheFinalMessage() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("it. The ", "Closes #1");

        assertThat(translator.translate(messageEnd("it. The ", "Closes #1"), state))
                .extracting(c -> c.getClass().getSimpleName())
                .containsExactly("TextEnd");
        assertThat(state.isStreamBroken()).isFalse();
    }

    @Test
    void shouldFailTheTurnAndStoreTheFinalTextWhenADeltaWasLost() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        // ". The C" never arrived.
        streamDeltas("it", "loses #1");

        List<UIMessageChunk> out = translator.translate(messageEnd("it. The Closes #1"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(((UIMessageChunk.Error) out.get(1)).errorText())
                .isEqualTo(PiEventToUiChunkTranslator.REPLY_LOST_IN_TRANSIT);
        assertThat(state.isStreamBroken()).isTrue();
        JsonNode parts = state.partsSnapshot();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).get("type").asString()).isEqualTo("step-start");
        assertThat(parts.get(1).get("text").asString()).isEqualTo("it. The Closes #1");
    }

    @Test
    void shouldKeepALinkedObservationWhenReplacingTextWithALostDelta() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("a");
        UUID observationId = UUID.randomUUID();
        translator.translate(
                mapper.createObjectNode()
                        .put("type", "link_observation")
                        .put("observationId", observationId.toString()),
                state);
        // "b" never arrived.

        List<UIMessageChunk> out = translator.translate(messageEnd("ab"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        JsonNode parts = state.partsSnapshot();
        assertThat(parts)
                .extracting(p -> p.get("type").asString())
                .containsExactly("step-start", "data-observation", "text");
        assertThat(parts.get(1).path("data").path("observationId").asString()).isEqualTo(observationId.toString());
        assertThat(parts.get(2).get("text").asString()).isEqualTo("ab");
        assertThat(state.linkedObservationIds()).containsExactly(observationId);
    }

    @Test
    void shouldFailInsteadOfFinishingWhenMessageEndWasLost() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("it");

        List<UIMessageChunk> out = translator.translate(agentEnd("it. The Closes #1"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(state.isStreamBroken()).isTrue();
        assertThat(state.partsSnapshot().get(1).get("text").asString()).isEqualTo("it. The Closes #1");
    }

    @Test
    void shouldFinishWhenAgentEndConfirmsAMessageWithoutMessageEnd() throws Exception {
        streamDeltas("stub: hi");

        List<UIMessageChunk> out = translator.translate(agentEnd("stub: hi"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Finish");
        assertThat(state.isStreamBroken()).isFalse();
    }

    @Test
    void shouldFailWhenMessageStartWasLostAndTheTextDiffers() throws Exception {
        streamDeltas("it", "loses #1");

        List<UIMessageChunk> out = translator.translate(messageEnd("it. The Closes #1"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(state.isStreamBroken()).isTrue();
    }

    @Test
    void shouldFailWhenANewMessageStartsBeforeThePreviousOneEnded() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("it");

        List<UIMessageChunk> out = translator.translate(fixture("message_start_assistant.json"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(state.partsSnapshot())
                .extracting(p -> p.get("type").asString())
                .containsExactly("step-start");
    }

    @Test
    void shouldFailWhenAgentEndCarriesAReplyThatNeverStreamed() {
        List<UIMessageChunk> out = translator.translate(agentEnd("Link the issue."), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("Error");
        assertThat(state.isStreamBroken()).isTrue();
    }

    @Test
    void shouldFailWhenAWholeMiddleMessageWasLost() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("Let me check.");
        translator.translate(messageEnd("Let me check."), state);
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("Done.");
        translator.translate(messageEnd("Done."), state);

        List<UIMessageChunk> out = translator.translate(agentEnd("Let me check.", "Link the issue.", "Done."), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("Error");
    }

    @Test
    void shouldFinishWhenEveryReplyTextStreamedIncludingToolOnlyAndRetriedMessages() throws Exception {
        // A failed attempt that Pi retried streams text agent_end no longer carries.
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("Partial attempt");
        translator.translate(messageEnd("Partial attempt"), state);
        translator.translate(fixture("message_start_assistant.json"), state);
        translator.translate(messageEnd(), state);
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("Done.");
        translator.translate(messageEnd("Done."), state);

        List<UIMessageChunk> out = translator.translate(agentEnd("", "Done."), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("Finish");
    }

    @Test
    void shouldFailWhenAgentEndOrdersTheStreamedMessagesDifferently() throws Exception {
        streamMessage("First.");
        streamMessage("Second.");

        assertThat(translator.translate(agentEnd("Second.", "First."), state))
                .extracting(c -> c.getClass().getSimpleName())
                .containsExactly("Error");
    }

    @Test
    void shouldFailWhenARetriedTextHidesAMissingFinalMessage() throws Exception {
        // The failed attempt and the final one both said "Let me check."; the final "Done." never streamed.
        streamMessage("Let me check.");
        streamMessage("Let me check.");

        assertThat(translator.translate(agentEnd("Let me check.", "Done."), state))
                .extracting(c -> c.getClass().getSimpleName())
                .containsExactly("Error");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"citation_delta\",\"contentIndex\":0}}",
                "{\"type\":\"message_update\"}",
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"contentIndex\":0}}"
            })
    void shouldFailTheTurnOnAnInnerEventItCannotAccountFor(String event) throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("Hi");

        List<UIMessageChunk> out = translator.translate(mapper.readTree(event), state);
        translator.translate(messageEnd("Hi"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(state.isStreamBroken()).isTrue();
    }

    private void streamMessage(String text) throws IOException {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas(text);
        translator.translate(messageEnd(text), state);
    }

    @Test
    void shouldFailWhenALinkedObservationCannotBeRead() throws Exception {
        translator.translate(fixture("message_start_assistant.json"), state);
        streamDeltas("See this");

        List<UIMessageChunk> out = translator.translate(
                mapper.createObjectNode().put("type", "link_observation").put("observationId", "not-a-uuid"), state);

        assertThat(out).extracting(c -> c.getClass().getSimpleName()).containsExactly("TextEnd", "Error");
        assertThat(state.linkedObservationIds()).isEmpty();
    }

    private JsonNode agentEnd(String... texts) {
        ObjectNode end = mapper.createObjectNode().put("type", "agent_end");
        var messages = end.putArray("messages");
        for (String text : texts) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant").put("stopReason", "stop");
            var content = message.putArray("content");
            if (!text.isEmpty()) {
                content.addObject().put("type", "text").put("text", text);
            }
        }
        return end;
    }

    private void streamDeltas(String... deltas) throws IOException {
        for (String delta : deltas) {
            ObjectNode update =
                    (ObjectNode) fixture("message_update_text_delta.json").deepCopy();
            ((ObjectNode) update.get("assistantMessageEvent")).put("delta", delta);
            translator.translate(update, state);
        }
    }

    private JsonNode messageEnd(String... textBlocks) throws IOException {
        ObjectNode end = (ObjectNode) fixture("message_end_assistant.json").deepCopy();
        var content = ((ObjectNode) end.get("message")).putArray("content");
        for (String text : textBlocks) {
            content.addObject().put("type", "text").put("text", text);
        }
        return end;
    }

    @Test
    void toolEventsAreNotPersistedAsMessageParts() throws Exception {
        ObjectNode start = mapper.createObjectNode();
        start.put("type", "tool_execution_start");
        start.put("toolCallId", "tc-1");
        start.put("toolName", "fetch_context");
        start.putObject("args").put("path", "inputs/context/workspace.json");
        translator.translate(start, state);

        ObjectNode end = mapper.createObjectNode();
        end.put("type", "tool_execution_end");
        end.put("toolCallId", "tc-1");
        end.put("toolName", "fetch_context");
        end.put("isError", false);
        end.putObject("result")
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "ok");
        translator.translate(end, state);

        assertThat(state.partsSnapshot()).isEmpty();
    }
}
