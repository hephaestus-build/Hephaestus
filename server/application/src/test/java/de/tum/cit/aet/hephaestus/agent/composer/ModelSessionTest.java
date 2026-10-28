package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ModelSessionTest extends BaseUnitTest {
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

    @Test
    void shouldPreserveNativeAnthropicToolTurnsAndUseAutoThroughoutComposition() {
        var mapper = new ObjectMapper();
        var requests = new ArrayList<JsonNode>();
        var session = new ModelSession(
                body -> {
                    requests.add(mapper.readTree(body));
                    String response = requests.size() == 1
                            ? "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"Check evidence\",\"signature\":\"signature\"},{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"evidence\",\"input\":{\"id\":1}}]}"
                            : "{\"content\":[{\"type\":\"text\",\"text\":\"Feedback composed.\"}]}";
                    return new ModelSession.Answer(200, response.getBytes(StandardCharsets.UTF_8), true, null);
                },
                mapper,
                new ModelSession.Settings(LlmApiProtocol.ANTHROPIC_MESSAGES, "alias", null, 256, null),
                "Compose feedback from admitted evidence.",
                List.of(new ModelSession.Tool(
                        "evidence", "Read evidence", mapper.createObjectNode().put("type", "object"))));
        var reply = session.send("Compose this review.");
        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().getFirst().arguments().path("id").asInt()).isEqualTo(1);
        var composed = session.answer(List.of(new ModelSession.ToolResult("call-1", "Evidence found")), "Continue.");
        assertThat(composed.text()).isEqualTo("Feedback composed.");
        var second = requests.get(1);
        assertThat(second.path("system").asString()).isEqualTo("Compose feedback from admitted evidence.");
        assertThat(second.path("tools").path(0).has("input_schema")).isTrue();
        assertThat(second.path("messages")
                        .path(1)
                        .path("content")
                        .path(0)
                        .path("signature")
                        .asString())
                .isEqualTo("signature");
        assertThat(second.path("messages")
                        .path(2)
                        .path("content")
                        .path(0)
                        .path("tool_use_id")
                        .asString())
                .isEqualTo("call-1");
        assertThat(second.path("messages")
                        .path(2)
                        .path("content")
                        .path(1)
                        .path("text")
                        .asString())
                .isEqualTo("Continue.");
        for (var request : requests) {
            assertThat(request.path("tool_choice").path("type").asString()).isEqualTo("auto");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(request.has("store")).isFalse();
        }
        assertThat(mapper.readTree(session.requestText("Next")).path("messages").size())
                .isEqualTo(5);
    }
}
