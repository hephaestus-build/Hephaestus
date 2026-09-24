package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ObservationAdmissionControllerTest extends BaseUnitTest {

    @Mock
    private ObservationAdmissionService service;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private ObservationAdmissionController controller;

    @BeforeEach
    void setUp() {
        controller = new ObservationAdmissionController(service, new PracticeReviewRefusalMetrics(meterRegistry));
    }

    @Test
    void malformedRequestIsBadRequest() {
        assertStatus(
                HttpStatus.BAD_REQUEST,
                () -> controller.admit(mapper.createObjectNode(), new TestingAuthenticationToken("x", "x")));
    }

    @Test
    void agentJobCredentialAdmitsItsObservations() {
        UUID id = UUID.randomUUID();
        var response = mapper.createObjectNode().put("admissionDigest", "sha256:digest");
        when(service.admit(eq(identity(id)), any())).thenReturn(response);

        var actual = controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id));

        assertThat(actual).isSameAs(response);
        verify(service).admit(identity(id), validRequest().path("observations"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"did_not_read_the_diff", "incoherent_assessment"})
    void aRefusedReviewIsAnsweredAsADecisionAndCounted(String reasonCode) {
        UUID id = UUID.randomUUID();
        when(service.admit(eq(identity(id)), any()))
                .thenThrow(
                        new ObservationsRefusedException(reasonCode, "The submitted observations cannot be admitted"));

        assertThatThrownBy(() -> controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    // Not 5xx: a 5xx is what the sandbox repeats, and repeating puts the same question.
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getReason()).contains("cannot be admitted");
                });
        assertThat(meterRegistry
                        .counter("practice.review.refused", "phase", "execution", "reason", reasonCode)
                        .count())
                .isEqualTo(1d);
    }

    @Test
    void shouldAnswerAnInadmissibleSubmissionAsADecisionRatherThanAFault() {
        UUID id = UUID.randomUUID();
        when(service.admit(eq(identity(id)), any()))
                .thenThrow(new JobDeliveryException("Observation cited unavailable evidence source"));

        assertThatThrownBy(() -> controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    // Deterministic for this submission, so a 5xx would only have the sandbox ask again.
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getReason()).contains("unavailable evidence source");
                });
        assertThat(meterRegistry
                        .counter(
                                "practice.review.refused",
                                "phase",
                                "execution",
                                "reason",
                                ObservationAdmissionService.INADMISSIBLE_REASON_CODE)
                        .count())
                .isEqualTo(1d);
    }

    @Test
    void shouldAnswerAVerifierFailureAsAServerFaultTheRunnerRepeats() {
        UUID id = UUID.randomUUID();
        when(service.admit(eq(identity(id)), any()))
                .thenThrow(new IllegalStateException("Captured evidence digest mismatch"));

        assertStatus(
                HttpStatus.INTERNAL_SERVER_ERROR,
                () -> controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id)));
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void unexpectedPrincipalCannotAdmitObservations() {
        assertStatus(
                HttpStatus.FORBIDDEN,
                () -> controller.admit(validRequest(), new TestingAuthenticationToken("unexpected", "[REDACTED]")));
        verifyNoInteractions(service);
    }

    @Test
    void mentorCredentialCannotAdmitAgentObservations() {
        UUID id = UUID.randomUUID();
        assertStatus(
                HttpStatus.FORBIDDEN,
                () -> controller.admit(validRequest(), authentication(LlmUsageSourceType.MENTOR_TURN, id)));
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectStaleAttemptWithoutCountingRefusalWhenOwnershipChangesDuringAdmission() {
        UUID id = UUID.randomUUID();
        when(service.admit(eq(identity(id)), any())).thenThrow(new ObservationAdmissionService.StaleAttemptException());

        assertStatus(
                HttpStatus.CONFLICT,
                () -> controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id)));
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    private static ObservationAdmissionService.AdmissionIdentity identity(UUID id) {
        return new ObservationAdmissionService.AdmissionIdentity(id, 1L, 0, "worker-1");
    }

    private ObjectNode validRequest() {
        var request = mapper.createObjectNode().put("schemaVersion", 1);
        request.putArray("observations");
        return request;
    }

    private static TestingAuthenticationToken authentication(LlmUsageSourceType sourceType, UUID sourceId) {
        ProxyRouting routing = new ProxyRouting(
                "agent",
                "responses",
                "http://model",
                null,
                null,
                null,
                1L,
                new ProxyRouting.BilledAttempt(sourceType, sourceId, 0, BigDecimal.ZERO, "worker-1"));
        return new TestingAuthenticationToken(routing, "[REDACTED]");
    }

    private static void assertStatus(HttpStatus status, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
