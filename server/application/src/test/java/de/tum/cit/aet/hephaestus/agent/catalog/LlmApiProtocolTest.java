package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LlmApiProtocolTest extends BaseUnitTest {

    @ParameterizedTest(name = "\"{0}\" is not a protocol")
    @NullAndEmptySource
    @ValueSource(strings = {"anthropic-messages", "azure-openai-responses", "OPENAI_COMPLETIONS", "rerank"})
    void shouldParseToNothingWhenTheValueNamesNoProtocol(@Nullable String wire) {
        assertThat(LlmApiProtocol.parse(wire)).isEmpty();
    }
}
