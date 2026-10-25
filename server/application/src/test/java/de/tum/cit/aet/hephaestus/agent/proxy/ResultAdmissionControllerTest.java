package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobJwt;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class ResultAdmissionControllerTest extends BaseUnitTest {
    @Test
    void shouldRejectCredentialsThatDoNotIdentifyAReviewWorker() {
        var controller = new ResultAdmissionController(mock(ResultAdmissionService.class));
        assertThatThrownBy(() ->
                        controller.readPublicFeedbackHistory(new TestingAuthenticationToken("mentor", "credential")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @ParameterizedTest
    @MethodSource("attemptConflicts")
    void shouldReportAnAttemptConflictWithoutAdmittingAnotherPayload(RuntimeException conflict) {
        var admission = mock(ResultAdmissionService.class);
        var id = UUID.randomUUID();
        var request = new AdmissionRequestDTO(3, UUID.randomUUID(), id, 0, List.of(), List.of());
        var now = Instant.now();
        var jwt = new JobJwt(id, 1L, 0, Set.of("llm_proxy"), "test", now, now.plusSeconds(60), now.plusSeconds(30));
        var identity = ProxyIdentity.of(jwt, "worker");
        var authentication = new JobTokenAuthentication(identity, jwt.scopes(), null);
        when(admission.admit(new ResultAdmissionService.AdmissionIdentity(id, 1L, 0, "worker"), request))
                .thenThrow(conflict);
        assertThatThrownBy(() -> new ResultAdmissionController(admission).admitResults(request, authentication))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void shouldNameTheStaleHistoryInAPreparationRefusalThatPreparedNothing() {
        var admission = mock(ResultAdmissionService.class);
        var id = UUID.randomUUID();
        var request = new PrepareFeedbackRequestDTO(UUID.randomUUID(), id, 0, List.of(), List.of(), true, null);
        var now = Instant.now();
        var jwt = new JobJwt(id, 1L, 0, Set.of("llm_proxy"), "test", now, now.plusSeconds(60), now.plusSeconds(30));
        var authentication = new JobTokenAuthentication(ProxyIdentity.of(jwt, "worker"), jwt.scopes(), null);
        when(admission.prepareFeedback(new ResultAdmissionService.AdmissionIdentity(id, 1L, 0, "worker"), request))
                .thenThrow(new PublicationContextRefusedException(FeedbackStopReason.PUBLIC_HISTORY_CHANGED));
        assertThatThrownBy(() -> new ResultAdmissionController(admission).prepareFeedback(request, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    Assertions.assertThat(error.getBody().getProperties())
                            .containsEntry("code", "PUBLIC_HISTORY_CHANGED");
                });
    }

    @Test
    void shouldNameTheRuleAndFieldButNotTheTextInATextPolicyRefusal() {
        var admission = mock(ResultAdmissionService.class);
        var id = UUID.randomUUID();
        var request = new PrepareFeedbackRequestDTO(UUID.randomUUID(), id, 0, List.of(), List.of(), true, null);
        var now = Instant.now();
        var jwt = new JobJwt(id, 1L, 0, Set.of("llm_proxy"), "test", now, now.plusSeconds(60), now.plusSeconds(30));
        var authentication = new JobTokenAuthentication(ProxyIdentity.of(jwt, "worker"), jwt.scopes(), null);
        when(admission.prepareFeedback(new ResultAdmissionService.AdmissionIdentity(id, 1L, 0, "worker"), request))
                .thenThrow(new FeedbackTextRefusedException(FeedbackDestination.ON_WORK, "LINK", "notes[1]"));
        assertThatThrownBy(() -> new ResultAdmissionController(admission).prepareFeedback(request, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    Assertions.assertThat(error.getBody().getProperties())
                            .containsExactlyInAnyOrderEntriesOf(Map.of(
                                    "code", "FEEDBACK_TEXT_REFUSED",
                                    "destination", "ON_WORK",
                                    "rule", "LINK",
                                    "field", "notes[1]"));
                });
    }

    private static Stream<RuntimeException> attemptConflicts() {
        return Stream.of(new ResultAdmissionService.StaleAttemptException(), new AdmissionConflictException());
    }
}
