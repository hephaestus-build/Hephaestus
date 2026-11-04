package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/** A provider's own reason for stopping decides whether its answer was complete, cut short or refused. */
class ModelStopReasonTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // Buffered answers.
                "ANTHROPIC_MESSAGES | {\"stop_reason\":\"end_turn\"} | completed",
                "ANTHROPIC_MESSAGES | {\"stop_reason\":\"refusal\"} | refused",
                "ANTHROPIC_MESSAGES | {\"stop_reason\":\"max_tokens\"} | length",
                "OPENAI_COMPLETIONS | {\"choices\":[{\"finish_reason\":\"stop\"}]} | completed",
                "OPENAI_COMPLETIONS | {\"choices\":[{\"finish_reason\":\"content_filter\"}]} | refused",
                "OPENAI_COMPLETIONS | {\"choices\":[{\"finish_reason\":\"length\"}]} | length",
                "OPENAI_RESPONSES | {\"status\":\"completed\",\"output\":[]} | completed",
                "OPENAI_RESPONSES | {\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}} | length",
                "OPENAI_RESPONSES | {\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"}} | refused",
                "OPENAI_RESPONSES | {\"output\":[{\"content\":[{\"type\":\"refusal\",\"refusal\":\"No.\"}]}]} | refused",
                // Streamed events.
                "ANTHROPIC_MESSAGES | {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}} | refused",
                "OPENAI_COMPLETIONS | {\"choices\":[{\"delta\":{},\"finish_reason\":null}]} | completed",
                "OPENAI_RESPONSES | {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}} | length",
            })
    void shouldTellACompleteAnswerFromACutOrRefusedOne(LlmApiProtocol protocol, String json, String outcome) {
        assertThat(ModelStopReason.outcome(ModelStopReason.of(MAPPER.readTree(json), protocol)))
                .isEqualTo(outcome);
    }
}
