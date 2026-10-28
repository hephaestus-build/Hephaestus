package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.SsrfGuardedResolverGroup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class LlmModelCapabilityProbeTest extends BaseUnitTest {
    private MockWebServer upstream;
    private LlmModelCapabilityProbe probe;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        var egress = mock(EgressPolicy.class);
        when(egress.resolverGroup()).thenReturn(SsrfGuardedResolverGroup.LOOPBACK_EXEMPT_INSTANCE);
        probe = new LlmModelCapabilityProbe(egress, RestClient.builder(), mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.close();
    }

    private LlmProbeTarget target(LlmApiProtocol protocol) {
        return new LlmProbeTarget(
                upstream.url(protocol == LlmApiProtocol.ANTHROPIC_MESSAGES ? "/" : "/v1")
                        .toString(),
                LlmAuthMode.BEARER,
                "test-key",
                protocol);
    }

    private static MockResponse response(int status, String body) {
        return new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"ANTHROPIC_MESSAGES", "OPENAI_RESPONSES", "OPENAI_COMPLETIONS"})
    void shouldEstablishForcedToolChoiceFromActualToolCallsForOpaqueAliases(LlmApiProtocol protocol) throws Exception {
        upstream.enqueue(response(200, "{}"));
        String answer = switch (protocol) {
            case ANTHROPIC_MESSAGES -> "{\"content\":[{\"type\":\"tool_use\",\"name\":\"probe\"}]}";
            case OPENAI_RESPONSES -> "{\"output\":[{\"type\":\"function_call\",\"name\":\"probe\"}]}";
            default -> "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"probe\"}}]}}]}";
        };
        upstream.enqueue(response(200, answer));
        assertThat(probe.probe(target(protocol), "review-alias").forcedToolChoice())
                .isTrue();
        var first = upstream.takeRequest(1, TimeUnit.SECONDS);
        var second = upstream.takeRequest(1, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getUrl().encodedPath())
                .isEqualTo((protocol == LlmApiProtocol.ANTHROPIC_MESSAGES ? "" : "/v1") + protocol.upstreamPath());
        assertThat(mapper.readTree(Objects.requireNonNull(second.getBody()).utf8())
                        .path("model")
                        .asString())
                .isEqualTo("review-alias");
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
            assertThat(second.getHeaders().get("x-api-key")).isEqualTo("test-key");
            assertThat(second.getHeaders().get("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(second.getHeaders().get("Authorization")).isNull();
        }
    }

    @Test
    void shouldRecordUnsupportedOnlyAfterTheSameModelAcceptsAuto() {
        upstream.enqueue(response(200, "{}"));
        upstream.enqueue(response(400, "{}"));
        assertThat(probe.probe(target(LlmApiProtocol.ANTHROPIC_MESSAGES), "alias")
                        .forcedToolChoice())
                .isFalse();
    }

    @Test
    void shouldLeaveCapabilityUnknownWhenTheCredentialFails() {
        upstream.enqueue(response(401, "{}"));
        assertThat(probe.probe(target(LlmApiProtocol.ANTHROPIC_MESSAGES), "alias")
                        .forcedToolChoice())
                .isNull();
        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldLeaveCapabilityUnknownWhenTheProviderFailsOrIgnoresForcedChoice() {
        upstream.enqueue(response(200, "{}"));
        upstream.enqueue(response(503, "{}"));
        assertThat(probe.probe(target(LlmApiProtocol.OPENAI_RESPONSES), "alias").forcedToolChoice())
                .isNull();
        upstream.enqueue(response(200, "{}"));
        upstream.enqueue(response(200, "{}"));
        assertThat(probe.probe(target(LlmApiProtocol.OPENAI_RESPONSES), "alias").forcedToolChoice())
                .isNull();
    }
}
