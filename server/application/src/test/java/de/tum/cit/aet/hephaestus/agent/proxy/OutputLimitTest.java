package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OutputLimitTest extends BaseUnitTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC_MESSAGES, max_tokens",
        "OPENAI_COMPLETIONS, max_tokens",
        "OPENAI_COMPLETIONS, max_completion_tokens",
        "OPENAI_RESPONSES, max_output_tokens"
    })
    void shouldClampTheProtocolsFieldToTheLimitWhenTheRequestAsksForMore(LlmApiProtocol protocol, String field) {
        ObjectNode request = object("{\"" + field + "\":16384}");

        assertThat(OutputLimit.clamp(request, protocol, 4096)).isEqualTo(4096);

        assertThat(request.toString()).isEqualTo("{\"" + field + "\":4096}");
    }

    @Test
    void shouldClampAnIntegerTooLargeForALongToTheLimit() {
        ObjectNode request = object("{\"max_tokens\":100000000000000000000}");

        assertThat(OutputLimit.clamp(request, LlmApiProtocol.ANTHROPIC_MESSAGES, 4096))
                .isEqualTo(4096);
        assertThat(request.path("max_tokens").asLong()).isEqualTo(4096);
    }

    @Test
    void shouldKeepALimitBelowTheModelsLimit() {
        ObjectNode request = object("{\"max_tokens\":100}");

        assertThat(OutputLimit.clamp(request, LlmApiProtocol.OPENAI_COMPLETIONS, 4096))
                .isEqualTo(100);

        assertThat(request.toString()).isEqualTo("{\"max_tokens\":100}");
    }

    @Test
    void shouldKeepAndClampMaxCompletionTokensWhenTheCallerSentIt() {
        ObjectNode request = object("{\"max_completion_tokens\":9000}");

        OutputLimit.clamp(request, LlmApiProtocol.OPENAI_COMPLETIONS, 4096);

        assertThat(request.toString())
                .as("no second field is written beside the caller's")
                .isEqualTo("{\"max_completion_tokens\":4096}");
    }

    @Test
    void shouldBoundTheLargestFieldWhenAChatCompletionsRequestNamesBoth() {
        ObjectNode request = object("{\"max_tokens\":300,\"max_completion_tokens\":9000}");

        assertThat(OutputLimit.clamp(request, LlmApiProtocol.OPENAI_COMPLETIONS, 4096))
                .isEqualTo(4096);
        assertThat(request.path("max_tokens").asLong()).isEqualTo(300);
        assertThat(request.path("max_completion_tokens").asLong()).isEqualTo(4096);
    }

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC_MESSAGES, max_tokens",
        "OPENAI_COMPLETIONS, max_tokens",
        "OPENAI_RESPONSES, max_output_tokens"
    })
    void shouldWriteTheLimitInTheProtocolsDefaultFieldWhenTheRequestNamesNone(LlmApiProtocol protocol, String field) {
        ObjectNode request = object("{\"messages\":[]}");

        assertThat(OutputLimit.clamp(request, protocol, 4096)).isEqualTo(4096);

        assertThat(request.path(field).asLong()).isEqualTo(4096);
        assertThat(request.properties()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC_MESSAGES, max_output_tokens, max_tokens",
        "ANTHROPIC_MESSAGES, max_completion_tokens, max_tokens",
        "OPENAI_COMPLETIONS, max_output_tokens, max_tokens",
        "OPENAI_RESPONSES, max_tokens, max_output_tokens",
        "OPENAI_RESPONSES, max_completion_tokens, max_output_tokens"
    })
    void shouldWriteTheProtocolsFieldWhenTheRequestNamesOnlyAForeignField(
            LlmApiProtocol protocol, String foreign, String own) {
        ObjectNode request = object("{\"" + foreign + "\":10}");

        assertThat(OutputLimit.clamp(request, protocol, 4096)).isEqualTo(4096);

        assertThat(request.path(own).asLong()).isEqualTo(4096);
        assertThat(request.path(foreign).asLong())
                .as("a foreign field is left as it is")
                .isEqualTo(10);
    }

    @Test
    void shouldWriteTheProtocolsFieldWhenTheRequestNamesOnlyAGenerationConfigLimit() {
        ObjectNode request = object("{\"generationConfig\":{\"maxOutputTokens\":10}}");

        assertThat(OutputLimit.clamp(request, LlmApiProtocol.OPENAI_COMPLETIONS, 4096))
                .isEqualTo(4096);
        assertThat(request.path("max_tokens").asLong()).isEqualTo(4096);
    }

    @Test
    void shouldTreatANullFieldAsAbsent() {
        ObjectNode request = object("{\"max_tokens\":null}");

        assertThat(OutputLimit.clamp(request, LlmApiProtocol.ANTHROPIC_MESSAGES, 4096))
                .isEqualTo(4096);
        assertThat(request.path("max_tokens").asLong()).isEqualTo(4096);

        ObjectNode both = object("{\"max_tokens\":null,\"max_completion_tokens\":200}");
        assertThat(OutputLimit.clamp(both, LlmApiProtocol.OPENAI_COMPLETIONS, 4096))
                .isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "ANTHROPIC_MESSAGES | {\"max_tokens\":0}",
                "OPENAI_COMPLETIONS | {\"max_completion_tokens\":-1}",
                "OPENAI_COMPLETIONS | {\"max_tokens\":12.5}",
                "OPENAI_RESPONSES | {\"max_output_tokens\":\"100\"}",
                "ANTHROPIC_MESSAGES | {\"max_tokens\":-100000000000000000000}"
            })
    void shouldRefuseAProtocolFieldThatIsNotAPositiveWholeNumber(LlmApiProtocol protocol, String request) {
        assertThatThrownBy(() -> OutputLimit.bound(protocol, 4096L, json(request)))
                .isInstanceOf(OutputLimit.Refused.class);
    }

    @Test
    void shouldIgnoreAForeignFieldThatIsNotAPositiveWholeNumber() {
        assertThat(OutputLimit.bound(LlmApiProtocol.ANTHROPIC_MESSAGES, 4096L, json("{\"max_output_tokens\":-1}")))
                .isEqualTo(4096);
    }

    @Test
    void shouldBoundTheEffectiveLimitWithoutChangingTheRequest() {
        JsonNode request = json("{\"max_tokens\":16384}");

        assertThat(OutputLimit.bound(LlmApiProtocol.OPENAI_COMPLETIONS, 4096L, request))
                .isEqualTo(4096);
        assertThat(OutputLimit.bound(LlmApiProtocol.OPENAI_COMPLETIONS, 4096L, json("{\"max_tokens\":10}")))
                .isEqualTo(10);
        assertThat(request.toString()).isEqualTo("{\"max_tokens\":16384}");
    }

    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"ANTHROPIC_MESSAGES", "OPENAI_COMPLETIONS", "OPENAI_RESPONSES"})
    void shouldRefuseAGenerativeCallWhenItsModelHasNoLimit(LlmApiProtocol protocol) {
        assertThatThrownBy(() -> OutputLimit.bound(protocol, null, json("{\"max_tokens\":10}")))
                .isInstanceOf(OutputLimit.Refused.class)
                .hasMessageContaining("no output limit");
    }

    @Test
    void shouldBoundNoOutputWhenACallWritesNone() {
        assertThat(OutputLimit.bound(LlmApiProtocol.OPENAI_EMBEDDINGS, null, json("{\"max_tokens\":1}")))
                .isZero();
        assertThat(OutputLimit.bound(LlmApiProtocol.COHERE_RERANK, null, json("{}")))
                .isZero();
        assertThat(OutputLimit.bound(LlmApiProtocol.OPENAI_DECISIONS, 64L, json("{}")))
                .isEqualTo(64);
        assertThat(OutputLimit.bound(LlmApiProtocol.OPENAI_DECISIONS, null, json("{}")))
                .as("a decision model without a limit leaves its call without a bound")
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"OPENAI_DECISIONS", "OPENAI_EMBEDDINGS", "COHERE_RERANK"})
    void shouldWriteNoLimitWhenTheCallWritesNoOutput(LlmApiProtocol protocol) {
        assertThat(OutputLimit.generative(protocol)).isFalse();
    }

    @Test
    void shouldBoundEveryRequestedCompletionWhenTheRequestNamesMoreThanOne() {
        JsonNode request = json("{\"max_tokens\":16384,\"n\":128}");
        assertThat(OutputLimit.bound(LlmApiProtocol.OPENAI_COMPLETIONS, 4096L, request))
                .isEqualTo(524288L);
        assertThat(request.path("max_tokens").asLong()).isEqualTo(16384L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "\"2\""})
    void shouldRefuseAnInvalidCompletionCount(String count) {
        assertThatThrownBy(() ->
                        OutputLimit.bound(LlmApiProtocol.OPENAI_COMPLETIONS, 4096L, json("{\"n\":" + count + "}")))
                .isInstanceOf(OutputLimit.Refused.class);
    }

    @Test
    void shouldPreserveTheCauseWhenTheTotalOutputLimitOverflows() {
        assertThatThrownBy(() -> OutputLimit.bound(
                        LlmApiProtocol.OPENAI_COMPLETIONS, 4096L, json("{\"n\":9223372036854775807}")))
                .isInstanceOf(OutputLimit.Refused.class)
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    private static JsonNode json(String body) {
        return MAPPER.readTree(body);
    }

    private static ObjectNode object(String body) {
        return (ObjectNode) json(body);
    }
}
