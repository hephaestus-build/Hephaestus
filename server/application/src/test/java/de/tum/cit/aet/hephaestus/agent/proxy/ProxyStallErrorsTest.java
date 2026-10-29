package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ProxyStallErrorsTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"OPENAI_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void shouldEmitTheSelectedProtocolsStallFrame(LlmApiProtocol protocol) throws Exception {
        var response = new MockHttpServletResponse();
        new ProxyStallErrors(new ObjectMapper()).writeStallFrame(response, protocol, "provider idle");
        String frame = response.getContentAsString();
        assertThat(frame).contains("upstream_stalled: provider idle").endsWith("\n\n");
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
            assertThat(frame).startsWith("event: error\ndata: ");
            var payload = new ObjectMapper().readTree(frame.substring(frame.indexOf("data: ") + 6));
            assertThat(payload.path("type").asString()).isEqualTo("error");
            assertThat(payload.path("error").path("type").asString()).isEqualTo("upstream_stalled");
        } else {
            assertThat(frame).startsWith("data: ");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"OPENAI_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"})
    void shouldEmitTheSelectedProtocolsCapacityRefusal(LlmApiProtocol protocol) throws Exception {
        var response = new MockHttpServletResponse();
        new ProxyStallErrors(new ObjectMapper())
                .writeErrorFrame(response, protocol, "upstream_paused", "The model capacity lease ended.");
        String frame = response.getContentAsString();
        assertThat(frame)
                .contains("upstream_paused: The model capacity lease ended.")
                .endsWith("\n\n");
        var payload = new ObjectMapper().readTree(frame.substring(frame.indexOf("data: ") + 6));
        assertThat(payload.path("error").path("type").asString()).isEqualTo("upstream_paused");
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
            assertThat(frame).startsWith("event: error\ndata: ");
            assertThat(payload.path("type").asString()).isEqualTo("error");
        } else {
            assertThat(frame).startsWith("data: ");
        }
    }
}
