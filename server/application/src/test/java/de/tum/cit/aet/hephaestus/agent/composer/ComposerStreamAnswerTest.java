package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A composer's streamed answer assembles into the body the same call answers without streaming. */
@Tag("unit")
class ComposerStreamAnswerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldAssembleAChatCompletionIntoTheReplyItAnswersWithoutStreaming() throws Exception {
        var streamed = feed(LlmApiProtocol.OPENAI_COMPLETIONS, """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Look at the "}}]}

                data: {"choices":[{"index":0,"delta":{"reasoning_content":"catch."}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"check_package","arguments":"{\\"pie"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ces\\":[]}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

                data: [DONE]

                """);
        var unstreamed = MAPPER.readTree("""
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call-1","type":"function","function":{"name":"check_package","arguments":"{\\"pieces\\":[]}"}}],
                 "reasoning_content":"Look at the catch."}}]}""");

        JsonNode answer = Objects.requireNonNull(streamed.answer());

        assertThat(answer).isEqualTo(unstreamed);
        var requests = new ComposerRequests(MAPPER);
        assertThat(requests.reply(LlmApiProtocol.OPENAI_COMPLETIONS, answer))
                .isEqualTo(requests.reply(LlmApiProtocol.OPENAI_COMPLETIONS, unstreamed));
    }

    @Test
    void shouldAssembleAMessageWithItsThinkingTextAndToolInput() throws Exception {
        var streamed = feed(LlmApiProtocol.ANTHROPIC_MESSAGES, """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-1","role":"assistant","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The catch drops it."}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Checking it."}}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu-1","name":"check_package","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"pieces\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"[]}"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}

                event: message_stop
                data: {"type":"message_stop"}

                """);

        assertThat(streamed.answer()).isEqualTo(MAPPER.readTree("""
                {"type":"message","role":"assistant","content":[
                 {"type":"thinking","thinking":"The catch drops it.","signature":"sig"},
                 {"type":"text","text":"Checking it."},
                 {"type":"tool_use","id":"toolu-1","name":"check_package","input":{"pieces":[]}}],
                 "stop_reason":"tool_use"}"""));
    }

    @Test
    void shouldTakeTheCompletedResponseAsTheAnswer() throws Exception {
        var streamed = feed(LlmApiProtocol.OPENAI_RESPONSES, """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"Checking"}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","output":[{"type":"function_call","call_id":"call-1","name":"check_package","arguments":"{}"}]}}

                """);

        assertThat(streamed.answer()).isEqualTo(MAPPER.readTree("""
                {"id":"resp-1","status":"completed","output":[
                 {"type":"function_call","call_id":"call-1","name":"check_package","arguments":"{}"}]}"""));
    }

    @Test
    void shouldHaveNoAnswerWhenTheStreamEndsBeforeItsProtocolsEnd() {
        assertThat(feed(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Half an\"}}]}\n\n")
                        .answer())
                .isNull();
        assertThat(feed(
                                LlmApiProtocol.ANTHROPIC_MESSAGES,
                                "data: {\"type\":\"content_block_start\",\"index\":0,"
                                        + "\"content_block\":{\"type\":\"text\",\"text\":\"Half\"}}\n\n")
                        .answer())
                .isNull();
        assertThat(feed(
                                LlmApiProtocol.OPENAI_RESPONSES,
                                "data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"}}\n\n")
                        .answer())
                .isNull();
    }

    @Test
    void shouldCountALastEventThatTheProviderDidNotEndWithANewline() {
        var completion = feed(
                LlmApiProtocol.OPENAI_COMPLETIONS,
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Done.\"},\"finish_reason\":\"stop\"}]}");
        assertThat(Objects.requireNonNull(completion.answer())
                        .at("/choices/0/message/content")
                        .asString())
                .isEqualTo("Done.");
        var message = feed(
                LlmApiProtocol.ANTHROPIC_MESSAGES,
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Done.\"}}\n\n"
                        + "data: {\"type\":\"message_stop\"}");
        assertThat(message.answer()).isNotNull();
    }

    @Test
    void shouldReadLinesEndedByALoneCarriageReturnOrCrLf() {
        String events = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Do\"}}]}%n%n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ne.\"},\"finish_reason\":\"stop\"}]}%n%n";
        for (String newline : new String[] {"\r", "\r\n"}) {
            JsonNode answer = feed(LlmApiProtocol.OPENAI_COMPLETIONS, events.replace("%n", newline))
                    .answer();
            assertThat(answer).isNotNull();
            assertThat(Objects.requireNonNull(answer)
                            .at("/choices/0/message/content")
                            .asString())
                    .isEqualTo("Done.");
        }
    }

    @Test
    void shouldSetAToolCallsNameOnceAndContinueADeltaWithoutAnIndexByItsId() {
        var streamed = feed(LlmApiProtocol.OPENAI_COMPLETIONS, """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"id":"a","function":{"name":"check_package","arguments":"{"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"id":"a","function":{"name":"check_package","arguments":"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"id":"b","function":{"name":"publish_package","arguments":"{}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                """);

        JsonNode calls = Objects.requireNonNull(streamed.answer()).at("/choices/0/message/tool_calls");
        assertThat(calls.valueStream().map(call -> call.at("/function/name").asString()))
                .containsExactly("check_package", "publish_package");
        assertThat(calls.at("/0/function/arguments").asString()).isEqualTo("{}");
    }

    @Test
    void shouldHaveNoAnswerWhenTheProviderFailsMidStream() {
        assertThat(feed(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Half\"},\"finish_reason\":\"error\"}]}\n\n")
                        .answer())
                .isNull();
        assertThat(feed(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Half\"}}]}\n\n"
                                        + "data: {\"error\":{\"message\":\"overloaded\"}}\n\n"
                                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                        .answer())
                .isNull();
        assertThat(feed(
                                LlmApiProtocol.ANTHROPIC_MESSAGES,
                                "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}\n\n"
                                        + "data: {\"type\":\"message_stop\"}\n\n")
                        .answer())
                .isNull();
    }

    @Test
    void shouldDropAnAnswerWhoseTextPassesTheAssemblyBound() {
        var answer = new ComposerStreamAnswer(LlmApiProtocol.OPENAI_COMPLETIONS, MAPPER);
        String piece = "x".repeat(1024 * 1024);
        for (int i = 0; i < 5; i++)
            answer.accept(
                    ("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"" + piece + "\"}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
        answer.accept("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(answer.answer()).isNull();
    }

    /** Feeds the stream in seven-byte pieces, so events and lines split across reads as they do on the wire. */
    private static ComposerStreamAnswer feed(LlmApiProtocol protocol, String stream) {
        var answer = new ComposerStreamAnswer(protocol, MAPPER);
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        for (int from = 0; from < bytes.length; from += 7)
            answer.accept(Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + 7)));
        return answer;
    }
}
