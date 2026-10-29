package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class ProxyRequestBodyTest extends BaseUnitTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Nested
    class BodyLocking {

        @Test
        void shouldForceAuthoritativeModel() {
            byte[] input = "{\"model\":\"runner-controlled\",\"service_tier\":\"priority\",\"messages\":[]}"
                    .getBytes(StandardCharsets.UTF_8);

            var prepared =
                    ProxyRequestBody.prepare(OBJECT_MAPPER, input, "catalog-model", LlmApiProtocol.OPENAI_RESPONSES);
            assertThat(prepared).isNotNull();

            assertThat(prepared.body()).isNotNull();
            var tree = OBJECT_MAPPER.readTree(prepared.body());
            assertThat(tree.path("model").asString()).isEqualTo("catalog-model");
            assertThat(tree.has("service_tier")).isFalse();
        }

        @Test
        void shouldRejectMalformedJson() {
            assertThat(ProxyRequestBody.prepare(
                            OBJECT_MAPPER,
                            "not-json".getBytes(StandardCharsets.UTF_8),
                            "model",
                            LlmApiProtocol.OPENAI_RESPONSES))
                    .isNull();
        }

        @Test
        void shouldRejectJsonThatIsNotAnObject() {
            assertThat(ProxyRequestBody.prepare(
                            OBJECT_MAPPER,
                            "[]".getBytes(StandardCharsets.UTF_8),
                            "model",
                            LlmApiProtocol.OPENAI_RESPONSES))
                    .isNull();
        }

        @Test
        void shouldAddStreamingUsageForChatCompletions() {
            byte[] input = "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

            var prepared =
                    ProxyRequestBody.prepare(OBJECT_MAPPER, input, "catalog-model", LlmApiProtocol.OPENAI_COMPLETIONS);
            assertThat(prepared).isNotNull();

            assertThat(prepared.body()).isNotNull();
            var tree = OBJECT_MAPPER.readTree(prepared.body());
            assertThat(tree.path("model").asString()).isEqualTo("catalog-model");
            assertThat(tree.path("stream_options").path("include_usage").asBoolean())
                    .isTrue();
            // The un-augmented body is kept so a provider that rejects the flag can be retried on
            // exactly what the caller sent.
            assertThat(OBJECT_MAPPER.readTree(prepared.withoutUsageRequest()).has("stream_options"))
                    .isFalse();
        }

        static Stream<Arguments> requestedCapabilities() {
            return Stream.of(
                    Arguments.of(
                            "{\"tools\":[{\"type\":\"function\"},{\"type\":\"custom\"}]}", true, "caller-run tools"),
                    Arguments.of("{\"tools\":[{\"type\":\"web_search_preview\"}]}", false, "a provider-hosted tool"),
                    Arguments.of("{\"web_search_options\":{}}", false, "hosted search asked for outside tools"),
                    Arguments.of("{\"modalities\":[\"text\",\"audio\"],\"audio\":{}}", false, "audio output"),
                    Arguments.of("{\"modalities\":[\"text\"]}", true, "an explicit text-only modality"));
        }

        @ParameterizedTest(name = "{2}")
        @MethodSource("requestedCapabilities")
        void locksTheRequestedCapabilitySurface(String body, boolean allowed, String what) {
            var prepared = ProxyRequestBody.prepare(
                    OBJECT_MAPPER, body.getBytes(StandardCharsets.UTF_8), "model", LlmApiProtocol.OPENAI_RESPONSES);

            if (allowed) {
                assertThat(prepared).as(what).isNotNull();
            } else {
                assertThat(prepared).as(what).isNull();
            }
        }
    }

    @Test
    void shouldAllowNativeCustomToolsAndRefuseProviderExecution() {
        var input =
                "{\"model\":\"caller-model\",\"stream\":true,\"tools\":[{\"name\":\"submit_result\",\"input_schema\":{\"type\":\"object\"},\"strict\":true}]}";
        var prepared = ProxyRequestBody.prepare(
                OBJECT_MAPPER,
                input.getBytes(StandardCharsets.UTF_8),
                "catalog-model",
                LlmApiProtocol.ANTHROPIC_MESSAGES);
        assertThat(prepared).isNotNull();
        var body = OBJECT_MAPPER.readTree(prepared.body());
        assertThat(body.path("model").asString()).isEqualTo("catalog-model");
        assertThat(body.has("stream_options")).isFalse();
        assertThat(body.path("tools").path(0).path("strict").asBoolean()).isTrue();
        assertThat(prepared.withoutUsageRequest()).isNull();
        for (String forbidden : List.of(
                "{\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]}",
                "{\"mcp_servers\":[]}",
                "{\"container\":\"previous-container\"}"))
            assertThat(ProxyRequestBody.prepare(
                            OBJECT_MAPPER,
                            forbidden.getBytes(StandardCharsets.UTF_8),
                            "model",
                            LlmApiProtocol.ANTHROPIC_MESSAGES))
                    .isNull();
    }
}
