package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LlmConnectionProbeServiceTest extends BaseUnitTest {

    private MockWebServer upstream;
    private LlmConnectionRepository connectionRepository;
    private EgressPolicy egressPolicy;
    private LlmConnectionProbeService service;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        connectionRepository = mock(LlmConnectionRepository.class);
        egressPolicy = mock(EgressPolicy.class);
        service = new LlmConnectionProbeService(
                connectionRepository,
                egressPolicy,
                new ObjectMapper(),
                // Loopback allowed: the probe target is a MockWebServer on localhost.
                new LlmProperties(
                        "", new LlmProperties.Egress(true), new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL)));
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.close();
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

    @Test
    void shouldProbeAStoredConnectionFromAProjectionRatherThanTheEntity() {
        // The probe blocks for up to its full network timeout, so it runs with no transaction and no
        // JDBC connection held — a few admins testing one stalled provider must not starve the pool.
        when(connectionRepository.findProbeTargetById(5L))
                .thenReturn(
                        Optional.of(new LlmProbeTarget(upstream.url("/v1").toString(), LlmAuthMode.BEARER, "sk-abc")));
        upstream.enqueue(jsonResponse("{\"data\":[{\"id\":\"gpt-5\"}]}"));

        LlmProbeResultDTO result = service.probeStored(5L);

        assertThat(result.reachable()).isTrue();
        assertThat(result.models()).containsExactly("gpt-5");
        verify(connectionRepository, never()).findById(any());
    }

    @Test
    void shouldReportNotFoundForAnUnknownStoredConnection() {
        when(connectionRepository.findProbeTargetById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.probeStored(9L)).isInstanceOf(EntityNotFoundException.class);
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

    @Test
    void aStoredProbeRefusesAForbiddenUrlWithoutDiallingIt() {
        // A connection stored before the allowlist tightened is re-checked on every probe, not trusted
        // because it once passed.
        when(connectionRepository.findProbeTargetById(5L))
                .thenReturn(
                        Optional.of(new LlmProbeTarget(upstream.url("/v1").toString(), LlmAuthMode.BEARER, "sk-abc")));
        doThrow(new IllegalArgumentException("Provider host must be a public HTTPS URL"))
                .when(egressPolicy)
                .validate(any());

        assertThatThrownBy(() -> service.probeStored(5L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(upstream.getRequestCount()).isZero();
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
