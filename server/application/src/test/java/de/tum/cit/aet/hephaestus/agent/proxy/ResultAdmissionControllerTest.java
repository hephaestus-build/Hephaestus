package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
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
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    @Test
    void shouldRejectCredentialsThatDoNotIdentifyAReviewWorker() {
        var controller = new ResultAdmissionController(mock(ResultAdmissionService.class));
        var request = new AdmissionRequestDTO(3, UUID.randomUUID(), UUID.randomUUID(), 0, List.of(), List.of());
        assertThatThrownBy(
                        () -> controller.admitResults(request, new TestingAuthenticationToken("mentor", "credential")))
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
        var jwt = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                1L,
                0,
                LEASE,
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
        var identity = ProxyIdentity.of(jwt);
        var authentication = new JobTokenAuthentication(identity, jwt.scopes(), null);
        when(admission.admit(new ResultAdmissionService.AdmissionIdentity(id, 1L, 0, LEASE), request))
                .thenThrow(conflict);
        assertThatThrownBy(() -> new ResultAdmissionController(admission).admitResults(request, authentication))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static Stream<RuntimeException> attemptConflicts() {
        return Stream.of(new ResultAdmissionService.StaleAttemptException(), new AdmissionConflictException());
    }
}
