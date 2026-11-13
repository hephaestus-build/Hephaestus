package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.SsrfGuardedResolverGroup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class LlmConnectionProbeServiceTest extends BaseUnitTest {

    private MockWebServer upstream;
    private EgressPolicy egressPolicy;
    private LlmConnectionProbeService service;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        egressPolicy = mock(EgressPolicy.class);
        // Loopback allowed: the probe target is a MockWebServer on localhost.
        when(egressPolicy.resolverGroup()).thenReturn(SsrfGuardedResolverGroup.LOOPBACK_EXEMPT_INSTANCE);
        service = new LlmConnectionProbeService(egressPolicy, new ObjectMapper(), RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.close();
    }

    @Test
    void shouldListNativeAnthropicModelsWithTheConnectionsKeyAndFixedVersion() throws Exception {
        upstream.enqueue(jsonResponse("{\"data\":[{\"id\":\"review-alias\"}]}"));
        var result = service.probeDraft(new ProbeLlmConnectionRequestDTO(
                upstream.url("/").toString(), LlmApiProtocol.ANTHROPIC_MESSAGES, LlmAuthMode.API_KEY, "native-key"));
        assertThat(result.reachable()).isTrue();
        assertThat(result.models()).containsExactly("review-alias");
        var sent = upstream.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        assertThat(sent.getUrl().encodedPath()).isEqualTo("/v1/models");
        assertThat(sent.getHeaders().get("x-api-key")).isEqualTo("native-key");
        assertThat(sent.getHeaders().get("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(sent.getHeaders().get("Authorization")).isNull();
    }

    @Test
    void shouldFailSafelyWhenProviderResponseExceedsByteLimit() {
        upstream.enqueue(jsonResponse("{\"data\":[],\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}"));

        LlmProbeResultDTO result = service.probeDraft(request());

        assertThat(result.reachable()).isFalse();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.message()).containsIgnoringCase("too large");
        assertThat(result.models()).isEmpty();
    }

    @Test
    void shouldLimitNumberOfReturnedModels() {
        String models = IntStream.range(0, 1_001)
                .mapToObj(i -> "{\"id\":\"model-" + i + "\"}")
                .collect(Collectors.joining(","));
        upstream.enqueue(jsonResponse("{\"data\":[" + models + "]}"));

        LlmProbeResultDTO result = service.probeDraft(request());

        assertThat(result.reachable()).isTrue();
        assertThat(result.models()).hasSize(1_000);
    }

    @Test
    void shouldIgnoreModelIdsThatCannotFitCatalogColumn() {
        String tooLong = "x".repeat(257);
        upstream.enqueue(jsonResponse("{\"data\":[{\"id\":\"" + tooLong + "\"},{\"id\":\"valid-model\"}]}"));

        LlmProbeResultDTO result = service.probeDraft(request());

        assertThat(result.reachable()).isTrue();
        assertThat(result.models()).containsExactly("valid-model");
    }

    /**
     * Asserted on the upstream's request count rather than on the mock: "the guard was consulted" is not
     * the invariant — "no packet left the process" is.
     */
    @Test
    void aDraftProbeRefusesAForbiddenUrlWithoutDiallingIt() {
        doThrow(new IllegalArgumentException("Provider host must be a public HTTPS URL"))
                .when(egressPolicy)
                .validate(any());

        assertThatThrownBy(() -> service.probeDraft(request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public HTTPS URL");
        assertThat(upstream.getRequestCount())
                .as("the forbidden host must never be contacted")
                .isZero();
    }

    private ProbeLlmConnectionRequestDTO request() {
        return new ProbeLlmConnectionRequestDTO(
                upstream.url("/v1").toString(), LlmApiProtocol.OPENAI_COMPLETIONS, LlmAuthMode.BEARER, null);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
