package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.ConversationLoopCredentials;
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
    private final ConversationLoopCredentials loops = mock(ConversationLoopCredentials.class);
    private final AttemptTokenVerifier verifier = mock(AttemptTokenVerifier.class);
    private final JobTokenAuthenticationFilter filter = new JobTokenAuthenticationFilter(jobs, turns, loops, verifier);

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
        when(jobs.hasLiveLease(job.getId(), job.getWorkspaceId(), job.getAttempt(), jwt.leaseId()))
                .thenReturn(true);
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
        verify(jobs).hasLiveLease(job.getId(), job.getWorkspaceId(), job.getAttempt(), jwt.leaseId());
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void turnModelCallAuthenticatesWhileItsAttemptHoldsItsLease() throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(mock(MentorTurn.class)));
        when(turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.attempt(), jwt.leaseId()))
                .thenReturn(true);
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

    @Test
    void turnModelCallOfAnAttemptThatLostItsLeaseGetsConflict() throws Exception {
        // The database decides which lease is live (ConversationAttemptLeaseDatabaseTest); here the turn holds none.
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(mock(MentorTurn.class)));
        when(turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.attempt(), jwt.leaseId()))
                .thenReturn(false);
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
    @ValueSource(strings = {"other-turn", "runtime"})
    void turnTokenReachesNoOtherTurnAndNoSandboxRuntime(String target) throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        var path = target.equals("other-turn")
                ? "/internal/llm/turn/" + UUID.randomUUID() + "/end"
                : "/internal/llm/runtime/" + jwt.id();
        var response = new MockHttpServletResponse();
        filter.doFilter(request(path, "Bearer turn-token"), response, (incoming, outgoing) -> {
            throw new AssertionError("Turn token served outside its turn");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"end", "tools"})
    void jobTokenReachesNoTurnEndpoint(String endpoint) throws Exception {
        var job = running();
        when(verifier.verify("valid-token")).thenReturn(jwt(job.getId(), job.getWorkspaceId(), job.getAttempt()));
        var path = "/internal/llm/turn/" + UUID.randomUUID() + "/" + endpoint;
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
    void conversationLoopTokenReachesOnlyTheAssignments() throws Exception {
        when(verifier.verify("loop-token")).thenThrow(new AttemptTokenInvalidException("not signed", "malformed"));
        when(loops.authenticates("loop-token")).thenReturn(true);
        var principal = new AtomicReference<Authentication>();
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request(JobTokenAuthenticationFilter.ASSIGNMENTS_PATH, "Bearer loop-token"),
                response,
                (incoming, outgoing) -> principal.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        assertThat(response.getStatus()).isEqualTo(200);
        var identity = ((JobTokenAuthentication) principal.get()).getPrincipal();
        assertThat(identity.loop()).isNotNull();
        assertThat(identity.job()).isNull();
        assertThat(identity.turn()).isNull();
        assertThat(principal.get().getAuthorities()).as("no model scope").isEmpty();
        SecurityContextHolder.clearContext();

        UUID other = UUID.randomUUID();
        for (var path : new String[] {
            "/internal/llm/runtime/" + other,
            "/internal/llm/runtime/" + other + "/workspace",
            "/internal/llm/runtime/" + other + "/result",
            "/internal/llm/turn/" + other + "/context",
            "/internal/llm/turn/" + other + "/end",
            "/internal/llm/practice-script/1/run",
            "/internal/llm/responses",
            "/internal/llm/chat/completions"
        }) {
            var refused = new MockHttpServletResponse();
            filter.doFilter(request(path, "Bearer loop-token"), refused, (incoming, outgoing) -> {
                throw new AssertionError("The loop's token reached " + path);
            });
            assertThat(refused.getStatus()).as(path).isEqualTo(401);
        }
        var pulled = request(JobTokenAuthenticationFilter.ASSIGNMENTS_PATH, "Bearer loop-token");
        pulled.setMethod("GET");
        var notAPull = new MockHttpServletResponse();
        filter.doFilter(pulled, notAPull, (incoming, outgoing) -> {
            throw new AssertionError("Only a POST pulls an assignment");
        });
        assertThat(notAPull.getStatus()).isEqualTo(401);
    }

    @Test
    void attemptTokenNeverReachesTheAssignments() throws Exception {
        when(verifier.verify("turn-token")).thenReturn(turnJwt());
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request(JobTokenAuthenticationFilter.ASSIGNMENTS_PATH, "Bearer turn-token"),
                response,
                (incoming, outgoing) -> {
                    throw new AssertionError("An attempt token pulled an assignment");
                });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(loops);
    }

    @Test
    void refusedLoopTokenIsUnauthorized() throws Exception {
        when(verifier.verify("stale-token")).thenThrow(new AttemptTokenInvalidException("not signed", "malformed"));
        when(loops.authenticates("stale-token")).thenReturn(false);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request(JobTokenAuthenticationFilter.ASSIGNMENTS_PATH, "Bearer stale-token"),
                response,
                (incoming, outgoing) -> {
                    throw new AssertionError("A refused loop token pulled an assignment");
                });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static AttemptJwt turnJwt() {
        return turnJwt(UUID.randomUUID(), 7L);
    }

    private static AttemptJwt turnJwt(UUID id, long workspace) {
        var now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.TURN,
                id,
                workspace,
                2,
                UUID.randomUUID(),
                Set.of("llm_proxy"),
                "jti",
                now,
                now.plusSeconds(60),
                null);
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

    @Test
    void modelCallOfAnAttemptThatNoLongerHoldsItsLeaseGetsTheAttemptEndedConflict() throws Exception {
        // A requeue, another lease or a terminal status all leave no live lease for this credential
        // (ReviewJobLeaseIntegrationTest); the gateway asks only that.
        var job = running();
        var jwt = jwt(job.getId(), job.getWorkspaceId(), job.getAttempt());
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.hasLiveLease(job.getId(), job.getWorkspaceId(), job.getAttempt(), jwt.leaseId()))
                .thenReturn(false);
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
        // The job runs, but no longer under this credential's lease.
        var job = running();
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
        var jwt = jwt(job.getId(), job.getWorkspaceId(), job.getAttempt());
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.hasLiveLease(job.getId(), job.getWorkspaceId(), job.getAttempt(), jwt.leaseId()))
                .thenReturn(true);
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
                UUID.randomUUID(),
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
