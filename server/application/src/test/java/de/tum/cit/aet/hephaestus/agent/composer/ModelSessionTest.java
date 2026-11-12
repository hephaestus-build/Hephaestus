package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class ModelSessionTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"OPENAI_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void shouldOfferNoToolsWhenTheSessionHasNone(LlmApiProtocol protocol) {
        var mapper = new ObjectMapper();
        var session = new ModelSession(
                body -> new ModelSession.Answer(200, new byte[0], true, null),
                mapper,
                new ModelSession.Settings(protocol, "model", null, 100, null),
                "Draft.",
                List.of());

        var request = mapper.readTree(session.requestText("Report."));

        assertThat(request.has("tools")).isFalse();
        assertThat(request.has("tool_choice")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReasoningEffort.class)
    void shouldUseNativeCompositionDefaultsWithoutGuessingOptionsFromAnAlias(ReasoningEffort effort) {
        var mapper = new ObjectMapper();
        var session = new ModelSession(
                body -> new ModelSession.Answer(200, "{\"content\":[]}".getBytes(StandardCharsets.UTF_8), true, null),
                mapper,
                new ModelSession.Settings(LlmApiProtocol.ANTHROPIC_MESSAGES, "opaque-alias", effort, 4096, 0.2),
                "Compose.",
                List.of());
        var request = mapper.readTree(session.requestText("Feedback."));
        assertThat(request.has("thinking")).isFalse();
        assertThat(request.has("output_config")).isFalse();
        assertThat(request.has("temperature")).isFalse();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
    }
}
