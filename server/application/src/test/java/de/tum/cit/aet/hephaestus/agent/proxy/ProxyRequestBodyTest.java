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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ProxyRequestBodyTest extends BaseUnitTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"image_url", "input_image", "input_audio", "input_file", "document", "item_reference"})
    void shouldRefuseReferencedOrNonTextInputWhenBytesCannotBoundItsTokens(String type) {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"" + type
                + "\",\"url\":\"https://example.com/content\"}]}]}";
        for (LlmApiProtocol protocol : List.of(
                LlmApiProtocol.OPENAI_COMPLETIONS,
                LlmApiProtocol.OPENAI_RESPONSES,
                LlmApiProtocol.ANTHROPIC_MESSAGES)) {
            assertThat(ProxyRequestBody.prepare(
                            OBJECT_MAPPER, body.getBytes(StandardCharsets.UTF_8), "model", protocol, 4096L))
                    .as("%s on %s", type, protocol)
                    .isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"previous_response_id", "conversation", "prompt"})
    void shouldRefuseProviderStoredInputWhenTheRequestNamesIt(String field) {
        String body = "{\"" + field + "\":\"stored-id\",\"input\":\"Hi\"}";
        assertThat(ProxyRequestBody.prepare(
                        OBJECT_MAPPER,
                        body.getBytes(StandardCharsets.UTF_8),
                        "model",
                        LlmApiProtocol.OPENAI_RESPONSES,
                        4096L))
                .isNull();
    }

    @Nested
    class BodyLocking {

        @Test
        void shouldForceAuthoritativeModel() {
            byte[] input = "{\"model\":\"runner-controlled\",\"service_tier\":\"priority\",\"messages\":[]}"
                    .getBytes(StandardCharsets.UTF_8);

            var prepared = ProxyRequestBody.prepare(
                    OBJECT_MAPPER, input, "catalog-model", LlmApiProtocol.OPENAI_RESPONSES, null);
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
                            LlmApiProtocol.OPENAI_RESPONSES,
                            null))
                    .isNull();
        }

        @Test
        void shouldRejectJsonThatIsNotAnObject() {
            assertThat(ProxyRequestBody.prepare(
                            OBJECT_MAPPER,
                            "[]".getBytes(StandardCharsets.UTF_8),
                            "model",
                            LlmApiProtocol.OPENAI_RESPONSES,
                            null))
                    .isNull();
        }

        @Test
        void shouldAddStreamingUsageForChatCompletions() {
            byte[] input = "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

            var prepared = ProxyRequestBody.prepare(
                    OBJECT_MAPPER, input, "catalog-model", LlmApiProtocol.OPENAI_COMPLETIONS, null);
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
                    OBJECT_MAPPER,
                    body.getBytes(StandardCharsets.UTF_8),
                    "model",
                    LlmApiProtocol.OPENAI_RESPONSES,
                    null);

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
                LlmApiProtocol.ANTHROPIC_MESSAGES,
                null);
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
                            LlmApiProtocol.ANTHROPIC_MESSAGES,
                            null))
                    .isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC_MESSAGES, max_tokens",
        "OPENAI_COMPLETIONS, max_tokens",
        "OPENAI_RESPONSES, max_output_tokens"
    })
    void shouldForwardTheModelsOutputLimitWhenTheRequestNamesNone(LlmApiProtocol protocol, String field) {
        byte[] input = "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

        var prepared = ProxyRequestBody.prepare(OBJECT_MAPPER, input, "catalog-model", protocol, 4096L);

        assertThat(prepared).isNotNull();
        assertThat(OBJECT_MAPPER.readTree(prepared.body()).path(field).asLong()).isEqualTo(4096);
        byte[] retried = prepared.withoutUsageRequestOrBody();
        assertThat(OBJECT_MAPPER.readTree(retried).path(field).asLong())
                .as("the retry without the usage request keeps the limit")
                .isEqualTo(4096);
    }

    @Test
    void shouldForwardTheLimitThatTheRequestNames() {
        byte[] input = "{\"max_tokens\":100,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

        var prepared = ProxyRequestBody.prepare(
                OBJECT_MAPPER, input, "catalog-model", LlmApiProtocol.OPENAI_COMPLETIONS, 4096L);

        assertThat(prepared).isNotNull();
        var forwarded = OBJECT_MAPPER.readTree(prepared.body());
        assertThat(forwarded.path("max_tokens").asLong()).isEqualTo(100);
        assertThat(forwarded.has("max_completion_tokens")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC_MESSAGES, max_tokens",
        "OPENAI_COMPLETIONS, max_completion_tokens",
        "OPENAI_RESPONSES, max_output_tokens"
    })
    void shouldForwardTheRequestClampedToTheModelsOutputLimitWhenItAsksForMore(LlmApiProtocol protocol, String field) {
        byte[] input = ("{\"" + field + "\":16384,\"messages\":[]}").getBytes(StandardCharsets.UTF_8);

        var prepared = ProxyRequestBody.prepare(OBJECT_MAPPER, input, "catalog-model", protocol, 4096L);

        assertThat(prepared).isNotNull();
        assertThat(OBJECT_MAPPER.readTree(prepared.body()).path(field).asLong()).isEqualTo(4096);
    }

    @Test
    void shouldForwardAnEmbeddingCallWithoutAnOutputLimit() {
        byte[] input = "{\"input\":[\"a\"]}".getBytes(StandardCharsets.UTF_8);

        var prepared = ProxyRequestBody.prepare(
                OBJECT_MAPPER, input, "catalog-model", LlmApiProtocol.OPENAI_EMBEDDINGS, 4096L);

        assertThat(prepared).isNotNull();
        assertThat(OBJECT_MAPPER.readTree(prepared.body()).toString())
                .isEqualTo("{\"input\":[\"a\"],\"model\":\"catalog-model\"}");
    }
}
