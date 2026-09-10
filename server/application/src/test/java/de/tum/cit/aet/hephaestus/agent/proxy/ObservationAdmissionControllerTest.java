package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class ObservationAdmissionControllerTest {

    private final ObservationAdmissionService service = mock(ObservationAdmissionService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationAdmissionController controller =
            new ObservationAdmissionController(service, new PracticeReviewRefusalMetrics(meterRegistry));
    private final JsonMapper mapper = JsonMapper.builder().build();

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
        when(service.admit(eq(id), any())).thenReturn(response);

        var actual = controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id));

        assertThat(actual).isSameAs(response);
        verify(service).admit(id, validRequest().path("observations"));
    }

    @Test
    void aRefusedReviewIsAnsweredAsADecisionAndCounted() {
        UUID id = UUID.randomUUID();
        when(service.admit(eq(id), any()))
                .thenThrow(new ObservationsRefusedException(
                        "did_not_read_the_diff", "No observation decided anything or quoted the diff"));

        assertThatThrownBy(() -> controller.admit(validRequest(), authentication(LlmUsageSourceType.AGENT_JOB, id)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    // Not 5xx: a 5xx is what the sandbox repeats, and repeating puts the same question.
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getReason()).contains("quoted the diff");
                });
        assertThat(meterRegistry
                        .counter("practice.review.refused", "phase", "execution", "reason", "did_not_read_the_diff")
                        .count())
                .isEqualTo(1d);
        // The sandbox is gone once it reads this answer, so the reason has to outlive it on the job.
        verify(service)
                .recordRefusal(id, "did_not_read_the_diff", "No observation decided anything or quoted the diff");
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
                new ProxyRouting.BilledAttempt(sourceType, sourceId, 0, BigDecimal.ZERO));
        return new TestingAuthenticationToken(routing, "[REDACTED]");
    }

    private static void assertStatus(HttpStatus status, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
