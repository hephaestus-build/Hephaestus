package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JobTokenAuthenticationFilterTest extends BaseUnitTest {
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private final SandboxGatewaySessions sessions = mock(SandboxGatewaySessions.class);
    private final AttemptTokenVerifier verifier = mock(AttemptTokenVerifier.class);
    private final JobTokenAuthenticationFilter filter =
            new JobTokenAuthenticationFilter(jobs, turns, sessions, verifier, "worker-1");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void admissionAuthenticatesWithoutConfigurationOrSpendQueries() throws Exception {
        var job = running();
        var jwt = jwt(job.getId(), job.getWorkspaceId(), job.getAttempt());
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var request = request("/internal/llm/admit-results", "Bearer valid-token");
        var response = new MockHttpServletResponse();
        var principal = new AtomicReference<Authentication>();
        filter.doFilter(
                request,
                response,
                (incoming, outgoing) -> principal.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(principal.get()).isInstanceOf(JobTokenAuthentication.class);
        assertThat(((JobTokenAuthentication) principal.get()).getPrincipal().job())
                .isEqualTo(jwt);
        assertThat(job.getConfigSnapshot()).isNull();
        verify(jobs).findById(job.getId());
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void turnModelCallAuthenticatesWhileItsAttemptHoldsTheLeaseOnThisWorker() throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        var turn = leased(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(turn));
        var principal = new AtomicReference<Authentication>();
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request("/internal/llm/responses", "Bearer turn-token"),
                response,
                (incoming, outgoing) -> principal.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        assertThat(response.getStatus()).isEqualTo(200);
        var authenticated = (JobTokenAuthentication) principal.get();
        assertThat(authenticated.getPrincipal().turn()).isEqualTo(jwt);
        assertThat(authenticated.getPrincipal().description()).isEqualTo("mentor-turn:" + jwt.id());
        assertThat(authenticated.getDetails()).isEqualTo(jwt);
        verifyNoInteractions(jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "worker", "released"})
    void turnModelCallOfAnAttemptThatLostItsLeaseGetsConflict(String mismatch) throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        var turn = leased(jwt);
        if (mismatch.equals("attempt")) when(turn.getAttempt()).thenReturn(jwt.attempt() + 1);
        if (mismatch.equals("worker")) when(turn.getWorkerId()).thenReturn("other-worker");
        if (mismatch.equals("released")) when(turn.isLeased()).thenReturn(false);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(turn));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/llm/responses", "Bearer turn-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("Lost attempt served");
        });
        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void reviewTokenNamingATurnIsUnauthorized() throws Exception {
        // One token type for both kinds: the kind decides which rows the id may name.
        var jwt = jwt(UUID.randomUUID(), 7L, 2);
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(jwt.id())).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/llm/responses", "Bearer valid-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("A review token served as a turn");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(turns);
    }

    @Test
    void turnTokenNamingAReviewJobIsUnauthorized() throws Exception {
        var job = running();
        var jwt = turnJwt(job.getId(), job.getWorkspaceId());
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/llm/responses", "Bearer turn-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("A turn token served as a review");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(jobs);
    }

    @Test
    void turnModelCallOfAnUnknownTurnIsUnauthorized() throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/llm/responses", "Bearer turn-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("Unknown turn served");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void turnTokenReachesItsOwnTurnEndpointWithoutReadingTheTurn() throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        var served = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request("/internal/llm/turn/" + jwt.id() + "/end", "Bearer turn-token"),
                response,
                (incoming, outgoing) -> served.set(true));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(served).isTrue();
        verifyNoInteractions(turns, jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-turn", "runtime", "commands"})
    void turnTokenReachesNoOtherTurnAndNoSandboxRuntime(String target) throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        var path = switch (target) {
            case "other-turn" -> "/internal/llm/turn/" + UUID.randomUUID() + "/end";
            case "runtime" -> "/internal/llm/runtime/" + jwt.id();
            default -> "/internal/llm/runtime/" + jwt.id() + "/commands";
        };
        var response = new MockHttpServletResponse();
        filter.doFilter(request(path, "Bearer turn-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("Turn token served outside its turn");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"turn", "commands"})
    void jobTokenReachesNoTurnEndpointAndNoCommandChannel(String target) throws Exception {
        var job = running();
        when(verifier.verify("valid-token")).thenReturn(jwt(job.getId(), job.getWorkspaceId(), job.getAttempt()));
        var path = target.equals("turn")
                ? "/internal/llm/turn/" + UUID.randomUUID() + "/end"
                : "/internal/llm/runtime/" + job.getId() + "/commands";
        var request = request(path, "Bearer valid-token");
        request.setMethod("GET");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Job token served outside its job");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(jobs);
    }

    @Test
    void hostSessionTokenReachesOnlyItsOwnRuntimeReadsAndCommandChannel() throws Exception {
        UUID host = UUID.randomUUID();
        when(verifier.verify("session-token")).thenThrow(new AttemptTokenInvalidException("not signed", "malformed"));
        when(sessions.unsignedSessionFor("session-token"))
                .thenReturn(Optional.of(
                        new SandboxGatewaySessions.UnsignedSession(host, SandboxGatewaySessions.Kind.HEPH_HOST)));
        // The host's workspace initializer reads discovery and the workspace archive before the host starts.
        for (var path : new String[] {
            "/internal/llm/runtime/" + host,
            "/internal/llm/runtime/" + host + "/workspace",
            "/internal/llm/runtime/" + host + "/commands"
        }) {
            var principal = new AtomicReference<Authentication>();
            var own = request(path, "Bearer session-token");
            own.setMethod("GET");
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    own,
                    response,
                    (incoming, outgoing) -> principal.set(Objects.requireNonNull(
                            SecurityContextHolder.getContext().getAuthentication())));
            assertThat(response.getStatus()).as(path).isEqualTo(200);
            var identity = ((JobTokenAuthentication) principal.get()).getPrincipal();
            assertThat(identity.hostSession()).isEqualTo(host);
            assertThat(principal.get().getAuthorities()).as("no model scope").isEmpty();
            SecurityContextHolder.clearContext();
        }
        UUID other = UUID.randomUUID();
        for (var path : new String[] {
            "/internal/llm/runtime/" + other + "/commands",
            "/internal/llm/runtime/" + other,
            "/internal/llm/runtime/" + other + "/workspace",
            "/internal/llm/runtime/" + host + "/result",
            "/internal/llm/turn/" + host + "/end",
            "/internal/llm/responses"
        }) {
            var refused = request(path, "Bearer session-token");
            refused.setMethod(
                    path.endsWith("/commands") || path.endsWith("/workspace") || path.endsWith(other.toString())
                            ? "GET"
                            : "POST");
            var response = new MockHttpServletResponse();
            filter.doFilter(refused, response, (incoming, outgoing) -> {
                throw new AssertionError("Session token served outside its own runtime: " + path);
            });
            assertThat(response.getStatus()).as(path).isEqualTo(401);
        }
    }

    private static AttemptJwt turnJwt() {
        return turnJwt(UUID.randomUUID(), 7L);
    }

    private static AttemptJwt turnJwt(UUID id, long workspace) {
        var now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.TURN, id, workspace, 2, Set.of("llm_proxy"), "jti", now, now.plusSeconds(60), null);
    }

    /** The turn row as this worker's attempt holds it. */
    private static MentorTurn leased(AttemptJwt jwt) {
        var turn = mock(MentorTurn.class);
        // The check stops at the first mismatch, so a case reads only some of these.
        lenient().when(turn.isLeased()).thenReturn(true);
        lenient().when(turn.getAttempt()).thenReturn(jwt.attempt());
        lenient().when(turn.getWorkerId()).thenReturn("worker-1");
        return turn;
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "path"})
    void rejectsCredentialOutsideItsOwnJob(String mismatch) throws Exception {
        var job = running();
        var jwt = jwt(job.getId(), mismatch.equals("workspace") ? 999L : job.getWorkspaceId(), job.getAttempt());
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var request = request(
                mismatch.equals("path") ? "/internal/llm/runtime/" + UUID.randomUUID() : "/internal/llm/admit-results",
                "Bearer valid-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Unauthenticated request served");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requeued", "worker", "terminal"})
    void modelCallOfAnAttemptThatNoLongerRunsHereGetsTheAttemptEndedConflict(String mismatch) throws Exception {
        var job = running();
        when(verifier.verify("valid-token")).thenReturn(jwt(job.getId(), job.getWorkspaceId(), job.getAttempt()));
        if (mismatch.equals("requeued")) job.setAttempt(job.getAttempt() + 1);
        if (mismatch.equals("worker")) job.setWorkerId("other-worker");
        if (mismatch.equals("terminal")) job.setStatus(ReviewJobStatus.COMPLETED);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/llm/responses", "Bearer valid-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("Lost attempt served");
        });
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).isEqualTo(JobTokenAuthenticationFilter.ATTEMPT_ENDED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonOwnerResultUploadReportsConflictOnlyForItsOwnRuntimeUrl() throws Exception {
        var job = running();
        job.setWorkerId("other-worker");
        when(verifier.verify("valid-token")).thenReturn(jwt(job.getId(), job.getWorkspaceId(), job.getAttempt()));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        for (var id : new UUID[] {job.getId(), UUID.randomUUID()}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request("/internal/llm/runtime/" + id + "/result", "Bearer valid-token"),
                    response,
                    (incoming, outgoing) -> {
                        throw new AssertionError("Non-owner upload served");
                    });
            assertThat(response.getStatus()).isEqualTo(id.equals(job.getId()) ? 409 : 401);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer one two", "Basic one", "bearer "})
    void rejectsMalformedAuthorizationWithoutLookingUpAJob(String header) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/internal/llm/admit-results", header), response, (incoming, outgoing) -> {
            throw new AssertionError("Malformed credential served");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(verifier, jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "deadbeef", "localhost", "2001:db8::1", "fec0::1"})
    void rejectsNonprivateLiteralsAndHostnamesBeforeCredentialVerification(String address) throws Exception {
        var request = request("/internal/llm/admit-results", "Bearer valid-token");
        request.setRemoteAddr(address);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Public gateway served");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(verifier, jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fc00::1", "fd12:3456:789a::2"})
    void shouldAuthenticateAnIpv6PrivateGateway(String address) throws Exception {
        var job = running();
        when(verifier.verify("valid-token")).thenReturn(jwt(job.getId(), job.getWorkspaceId(), job.getAttempt()));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var request = request("/internal/llm/admit-results", "Bearer valid-token");
        request.setRemoteAddr(address);
        var response = new MockHttpServletResponse();
        var served = new AtomicBoolean();
        filter.doFilter(request, response, (incoming, outgoing) -> served.set(true));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(served).isTrue();
    }

    private static ReviewJob running() {
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        RuntimeJobTestData.initialize(job, 7L);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setAttempt(3);
        return job;
    }

    private static AttemptJwt jwt(UUID id, long workspace, int attempt) {
        var now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                workspace,
                attempt,
                Set.of("llm_proxy"),
                UUID.randomUUID().toString(),
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", authorization);
        return request;
    }
}
