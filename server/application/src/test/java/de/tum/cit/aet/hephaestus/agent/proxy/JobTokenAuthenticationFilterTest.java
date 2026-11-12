package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Instant;
import java.util.Map;
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
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JobTokenAuthenticationFilterTest extends BaseUnitTest {
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private static final String LOOP_SECRET = "0123456789abcdef".repeat(4);

    private final AttemptTokenVerifier verifier = mock(AttemptTokenVerifier.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final JobTokenAuthenticationFilter filter = new JobTokenAuthenticationFilter(
            jobs,
            runs,
            turns,
            new ConversationLoopProperties(LOOP_SECRET),
            verifier,
            null,
            Map.of(),
            mock(ProxyAccounting.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void shouldScopeGatewayLogsToTheClaimAndRestoreTheCallerEvenWhenTheRequestFails() {
        var job = running();
        var jwt = jwt(job);
        var ref = new AgentRunRef(jwt.runId(), job.getWorkspaceId());
        var run = mock(AgentRun.class);
        String subject = "review:" + job.getReviewId();
        when(run.getConcurrencyKey()).thenReturn(subject);
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(runs.isCurrent(ref, jwt.epoch())).thenReturn(true);
        when(runs.find(ref)).thenReturn(Optional.of(run));
        var previous = Map.of("run.id", "parent", "review.id", "parent-review", "custom", "kept");
        MDC.setContextMap(previous);

        assertThatThrownBy(() -> filter.doFilter(
                        request("/internal/llm/responses", "Bearer valid-token"),
                        new MockHttpServletResponse(),
                        (incoming, outgoing) -> {
                            assertThat(MDC.get("run.id")).isEqualTo(jwt.runId().toString());
                            assertThat(MDC.get("job.id")).isEqualTo(job.getId().toString());
                            assertThat(MDC.get("run.epoch")).isEqualTo(Integer.toString(jwt.epoch()));
                            assertThat(MDC.get("job.attempt"))
                                    .isEqualTo(Integer.toString(ReviewRunKind.attempt(jwt.epoch())));
                            assertThat(MDC.get("subject.key")).isEqualTo(subject);
                            assertThat(MDC.get("review.id"))
                                    .isEqualTo(job.getReviewId().toString());
                            throw new ServletException("Request failed");
                        }))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
    }

    @Test
    void admissionAuthenticatesWithoutConfigurationOrSpendQueries() throws Exception {
        var job = running();
        var jwt = jwt(job);
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(runs.isCurrent(new AgentRunRef(jwt.runId(), job.getWorkspaceId()), ReviewRunKind.epoch(job.getAttempt())))
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
                .isEqualTo(ProxyAttempt.of(jwt));
        assertThat(job.getConfigSnapshot()).isNull();
        verify(jobs).findById(job.getId());
        verify(runs)
                .isCurrent(new AgentRunRef(jwt.runId(), job.getWorkspaceId()), ReviewRunKind.epoch(job.getAttempt()));
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void turnModelCallAuthenticatesWhileItsAttemptHoldsItsLease() throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(mock(MentorTurn.class)));
        when(turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.epoch(), jwt.runId()))
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
        assertThat(authenticated.getPrincipal().turn()).isEqualTo(ProxyAttempt.of(jwt));
        assertThat(authenticated.getPrincipal().description()).isEqualTo("mentor-turn:" + jwt.id());
        assertThat(authenticated.getDetails()).isEqualTo(jwt);
        verifyNoInteractions(jobs);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCorrelateATurnAttemptWithoutInheritingReviewFields(boolean fail) throws Exception {
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(mock(MentorTurn.class)));
        when(turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.epoch(), jwt.runId()))
                .thenReturn(true);
        var run = mock(AgentRun.class);
        when(run.getConcurrencyKey()).thenReturn("conversation:42");
        when(runs.find(new AgentRunRef(jwt.runId(), jwt.workspaceId()))).thenReturn(Optional.of(run));
        var previous = Map.of("review.id", "enclosing-review", "job.id", "enclosing-job", "job.attempt", "7");
        MDC.setContextMap(previous);
        var response = new MockHttpServletResponse();
        FilterChain chain = (incoming, outgoing) -> {
            assertThat(MDC.getCopyOfContextMap())
                    .containsEntry("workspace.id", Long.toString(jwt.workspaceId()))
                    .containsEntry("run.id", jwt.runId().toString())
                    .containsEntry("run.epoch", Integer.toString(jwt.epoch()))
                    .containsEntry("subject.key", "conversation:42")
                    .doesNotContainKeys("review.id", "job.id", "job.attempt");
            if (fail) throw new ServletException("Request failed");
        };
        if (fail) {
            assertThatThrownBy(() ->
                            filter.doFilter(request("/internal/llm/responses", "Bearer turn-token"), response, chain))
                    .isInstanceOf(ServletException.class);
        } else {
            filter.doFilter(request("/internal/llm/responses", "Bearer turn-token"), response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
    }

    @Test
    void turnModelCallOfAnAttemptThatLostItsLeaseGetsConflict() throws Exception {
        // The database decides which lease is live; here the turn holds none.
        var jwt = turnJwt();
        when(verifier.verify("turn-token")).thenReturn(jwt);
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(mock(MentorTurn.class)));
        when(turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.epoch(), jwt.runId()))
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
    @ValueSource(strings = {"turn", "assignments"})
    void jobTokenReachesNoTurnEndpointAndNoAssignmentPull(String target) throws Exception {
        var job = running();
        when(verifier.verify("valid-token")).thenReturn(jwt(job));
        var path = target.equals("turn")
                ? "/internal/llm/turn/" + UUID.randomUUID() + "/end"
                : JobTokenAuthenticationFilter.ASSIGNMENTS_PATH;
        var request = request(path, "Bearer valid-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Job token served outside its job");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(jobs);
    }

    @Test
    void loopCredentialReachesOnlyTheAssignmentPull() throws Exception {
        var principal = new AtomicReference<Authentication>();
        var pull = request(JobTokenAuthenticationFilter.ASSIGNMENTS_PATH, "Bearer " + LOOP_SECRET);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                pull,
                response,
                (incoming, outgoing) -> principal.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((JobTokenAuthentication) principal.get()).getPrincipal().loop())
                .isTrue();
        assertThat(principal.get().getAuthorities()).as("no model scope").isEmpty();
        verifyNoInteractions(verifier, jobs, turns);
        SecurityContextHolder.clearContext();

        when(verifier.verify(LOOP_SECRET)).thenThrow(new AttemptTokenInvalidException("not signed", "malformed"));
        for (var path : new String[] {"/internal/llm/turn/" + UUID.randomUUID() + "/end", "/internal/llm/responses"}) {
            var refused = new MockHttpServletResponse();
            filter.doFilter(request(path, "Bearer " + LOOP_SECRET), refused, (incoming, outgoing) -> {
                throw new AssertionError("The loop's credential served outside its pull: " + path);
            });
            assertThat(refused.getStatus()).as(path).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assignmentPullRefusesAWrongCredentialAndAnAttemptToken(boolean attemptToken) throws Exception {
        String token = attemptToken ? "turn-token" : "f".repeat(64);
        if (attemptToken) {
            lenient().when(verifier.verify(token)).thenReturn(turnJwt());
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request(JobTokenAuthenticationFilter.ASSIGNMENTS_PATH, "Bearer " + token),
                response,
                (incoming, outgoing) -> {
                    throw new AssertionError("A pull served without the loop's credential");
                });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(jobs, turns);
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
                now.plusSeconds(30));
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
        var jwt = jwt(job);
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(runs.isCurrent(new AgentRunRef(jwt.runId(), job.getWorkspaceId()), ReviewRunKind.epoch(job.getAttempt())))
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
        when(verifier.verify("valid-token")).thenReturn(jwt(job));
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
        var jwt = jwt(job);
        when(verifier.verify("valid-token")).thenReturn(jwt);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(runs.isCurrent(new AgentRunRef(jwt.runId(), job.getWorkspaceId()), ReviewRunKind.epoch(job.getAttempt())))
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
        RuntimeJobTestData.attempt(job, 3);
        return job;
    }

    private static AttemptJwt jwt(ReviewJob job) {
        return jwt(job.getId(), job.getWorkspaceId(), job.getAttempt(), job.getRunId());
    }

    private static AttemptJwt jwt(UUID id, long workspace, int attempt) {
        return jwt(id, workspace, attempt, UUID.randomUUID());
    }

    private static AttemptJwt jwt(UUID id, long workspace, int attempt, UUID runId) {
        var now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                id,
                workspace,
                ReviewRunKind.epoch(attempt),
                runId,
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
