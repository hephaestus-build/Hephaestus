package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailures;
import de.tum.cit.aet.hephaestus.agent.job.AttemptServerErrors;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
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
import tools.jackson.databind.json.JsonMapper;

class ResultAdmissionControllerTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    @Test
    void shouldRejectCredentialsThatDoNotIdentifyAReviewWorker() {
        var controller = new ResultAdmissionController(
                mock(ResultAdmissionService.class),
                mock(AttemptCaptureFailures.class),
                mock(AttemptServerErrors.class),
                mock(AgentRunAuthority.class));
        var request = new SealResultsRequestDTO(3, UUID.randomUUID(), UUID.randomUUID(), 0);
        assertThatThrownBy(
                        () -> controller.admitResults(request, new TestingAuthenticationToken("mentor", "credential")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void shouldRefuseToReadTheReviewsSourcesWhenItsRunsAuthorityNarrowed() {
        var admission = mock(ResultAdmissionService.class);
        var authority = mock(AgentRunAuthority.class);
        var id = UUID.randomUUID();
        var now = Instant.now();
        var jwt = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                1L,
                1,
                LEASE,
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(60));
        var refusal = new AuthorityRefusal(
                AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED);
        doThrow(new AuthorityRefusedException(refusal))
                .when(authority)
                .require(new AgentRunFence(LEASE, 1L, 1), AuthorityBoundary.CONTEXT_READ);
        var controller = new ResultAdmissionController(
                admission, mock(AttemptCaptureFailures.class), mock(AttemptServerErrors.class), authority);

        assertThatThrownBy(() -> controller.admitResults(
                        new SealResultsRequestDTO(3, UUID.randomUUID(), id, 0),
                        new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes())))
                .isInstanceOf(AuthorityRefusedException.class);
        verifyNoInteractions(admission);
    }

    @Test
    void shouldRefuseTheAnswerWhenTheRunsAuthorityNarrowedDuringTheAdmission() {
        var admission = mock(ResultAdmissionService.class);
        var authority = mock(AgentRunAuthority.class);
        var serverErrors = mock(AttemptServerErrors.class);
        var jwt = reviewToken(UUID.randomUUID());
        var refusal = new AuthorityRefusal(
                AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED);
        // Authority holds when the request arrives, and has narrowed once the admission returns.
        doNothing()
                .doThrow(new AuthorityRefusedException(refusal))
                .when(authority)
                .require(new AgentRunFence(LEASE, 1L, 1), AuthorityBoundary.CONTEXT_READ);
        var controller =
                new ResultAdmissionController(admission, mock(AttemptCaptureFailures.class), serverErrors, authority);

        assertThatThrownBy(() -> controller.admitResults(
                        new SealResultsRequestDTO(3, UUID.randomUUID(), jwt.id(), 0),
                        new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes())))
                .isInstanceOf(AuthorityRefusedException.class);
        verify(admission).admit(any(), any(), any());
        verifyNoInteractions(serverErrors);
    }

    @Test
    void shouldNotRecordARefusalDuringTheAdmissionAsAServerError() {
        var admission = mock(ResultAdmissionService.class);
        var serverErrors = mock(AttemptServerErrors.class);
        var jwt = reviewToken(UUID.randomUUID());
        when(admission.admit(any(), any(), any()))
                .thenThrow(new AuthorityRefusedException(new AuthorityRefusal(
                        AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED)));
        var controller = new ResultAdmissionController(
                admission, mock(AttemptCaptureFailures.class), serverErrors, mock(AgentRunAuthority.class));

        assertThatThrownBy(() -> controller.admitResults(
                        new SealResultsRequestDTO(3, UUID.randomUUID(), jwt.id(), 0),
                        new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes())))
                .isInstanceOf(AuthorityRefusedException.class);
        verifyNoInteractions(serverErrors);
    }

    private static AttemptJwt reviewToken(UUID id) {
        var now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                1L,
                1,
                LEASE,
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(60));
    }

    @ParameterizedTest
    @MethodSource("attemptConflicts")
    void shouldReportAnAttemptConflictWithoutAdmittingAnotherPayload(RuntimeException conflict) {
        var admission = mock(ResultAdmissionService.class);
        var id = UUID.randomUUID();
        var request = new SealResultsRequestDTO(3, UUID.randomUUID(), id, 0);
        var now = Instant.now();
        var jwt = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                1L,
                1,
                LEASE,
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
        var identity = ProxyIdentity.of(jwt);
        var authentication = new JobTokenAuthentication(identity, jwt.scopes());
        when(admission.admit(eq(new ResultAdmissionService.AdmissionIdentity(id, 1L, 0, LEASE)), eq(request), any()))
                .thenThrow(conflict);
        assertThatThrownBy(() -> new ResultAdmissionController(
                                admission,
                                mock(AttemptCaptureFailures.class),
                                mock(AttemptServerErrors.class),
                                mock(AgentRunAuthority.class))
                        .admitResults(request, authentication))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void shouldKeepARebuildIoFailureRetryableWithoutEndingTheAttempt() {
        var admission = mock(ResultAdmissionService.class);
        var failures = mock(AttemptCaptureFailures.class);
        var id = UUID.randomUUID();
        var request = new SealResultsRequestDTO(3, UUID.randomUUID(), id, 0);
        var now = Instant.now();
        var jwt = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                1L,
                1,
                LEASE,
                Set.of("llm_proxy"),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
        when(admission.admit(eq(new ResultAdmissionService.AdmissionIdentity(id, 1L, 0, LEASE)), eq(request), any()))
                .thenThrow(new UncheckedIOException(new IOException("disk temporarily unavailable")));
        var authentication = new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes());
        assertThatThrownBy(() -> new ResultAdmissionController(
                                admission, failures, mock(AttemptServerErrors.class), mock(AgentRunAuthority.class))
                        .admitResults(request, authentication))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error ->
                                Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(failures);
    }

    @Test
    void shouldUseTheSameSealDigestAsTheRunner() {
        var request = new SealResultsRequestDTO(
                SandboxLayout.PRACTICE_CONTRACT_VERSION,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                1);
        Assertions.assertThat(ProvenanceDigest.sha256Hex(new JsonMapper().writeValueAsBytes(request)))
                .isEqualTo("bcfdeaa2d6beeb2c11618f54111222cc92b2ee55b48eef3c586a84b344c9a6a4");
    }

    private static Stream<RuntimeException> attemptConflicts() {
        return Stream.of(new ResultAdmissionService.StaleAttemptException(), new AdmissionConflictException());
    }
}
