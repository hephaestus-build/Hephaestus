package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A Heph turn's credential on the worker's gateway: it acts for its own attempt only, and only while this worker
 * holds the attempt's lease. The filter is built by hand because the worker role is off in the test context.
 */
class TurnTokenAuthenticationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String WORKER = "worker-turn-auth";

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private WorkerJwtVerifier jwtVerifier;

    @Autowired
    private WorkerJwtIssuer jwtIssuer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactions;

    private Workspace workspace;
    private User developer;
    private MentorConversation conversation;
    private MentorTurn turn;

    private record Result(int status, @Nullable Authentication authentication) {}

    @BeforeEach
    void claimATurn() {
        SecurityContextHolder.clearContext();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        developer = persistUser("turn-auth-" + suffix);
        workspace = createWorkspace("turn-auth-" + suffix, "Turn Auth", "turn-auth-org", AccountType.ORG, developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
        turn = MentorTurnStarts.askAndStart(
                turns, metrics, workspace.getId(), conversation.getId(), "Question", WORKER);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String token(UUID turnId, long workspaceId, int attempt) {
        return jwtIssuer.issueForTurn(
                turnId,
                workspaceId,
                developer.getId(),
                conversation.getId(),
                attempt,
                Instant.now().plus(Duration.ofMinutes(5)));
    }

    private String token() {
        return token(turn.getId(), workspace.getId(), turn.getAttempt());
    }

    private Result call(String worker, String method, String path, String token) throws Exception {
        var filter = new JobTokenAuthenticationFilter(
                jobRepository, turnRepository, new SandboxGatewaySessions(), jwtVerifier, objectMapper, worker);
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        var seen = new AtomicReference<@Nullable Authentication>();
        filter.doFilter(
                request,
                response,
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return new Result(response.getStatus(), seen.get());
    }

    @Test
    void shouldRouteAModelCallToTheAttemptAndItsSpendWhenTheWorkerHoldsTheLease() throws Exception {
        transactions.executeWithoutResult(tx -> turnRepository.accumulateUsage(
                turn.getId(), turn.getAttempt(), new MentorTurnUsage(100_000, 0, 0, 0, 0, 1)));

        Result result = call(WORKER, "POST", "/internal/llm/responses", token());

        assertThat(result.status()).isEqualTo(200);
        Authentication authentication = Objects.requireNonNull(result.authentication());
        ProxyRouting routing = (ProxyRouting) Objects.requireNonNull(authentication.getPrincipal());
        ProxyRouting.BilledAttempt attempt = routing.attempt();
        assertThat(routing.workspaceId()).isEqualTo(workspace.getId());
        assertThat(routing.apiProtocol()).isEqualTo("openai-responses");
        assertThat(attempt.sourceType()).isEqualTo(LlmUsageSourceType.MENTOR_TURN);
        assertThat(attempt.sourceId()).isEqualTo(turn.getId());
        assertThat(attempt.number()).isEqualTo(turn.getAttempt());
        // $10 per million input tokens, frozen at admission.
        assertThat(routing.inFlightSpendUsd()).isEqualByComparingTo("1.00");
        assertThat(authentication.getDetails()).isInstanceOf(TurnJwt.class);
    }

    @Test
    void shouldAuthenticateTheTurnOnItsOwnEndpointsOnlyWhenItCallsThem() throws Exception {
        assertThat(call(WORKER, "GET", "/internal/llm/turn/" + turn.getId() + "/context", token())
                        .status())
                .isEqualTo(200);
        assertThat(call(WORKER, "GET", "/internal/llm/turn/" + UUID.randomUUID() + "/context", token())
                        .status())
                .isEqualTo(401);
        assertThat(call(WORKER, "GET", "/internal/llm/runtime/" + turn.getId() + "/workspace", token())
                        .status())
                .isEqualTo(401);
    }

    @Test
    void shouldRefuseAModelCallWhenAnotherWorkerOrAttemptOrWorkspaceHoldsTheTurn() throws Exception {
        String path = "/internal/llm/responses";

        assertThat(call("worker-other", "GET", path, token()).status()).isEqualTo(409);
        assertThat(call(WORKER, "GET", path, token(turn.getId(), workspace.getId(), turn.getAttempt() + 1))
                        .status())
                .isEqualTo(409);
        assertThat(call(WORKER, "GET", path, token(turn.getId(), workspace.getId() + 1, turn.getAttempt()))
                        .status())
                .isEqualTo(409);
    }

    @Test
    void shouldLeaveTheAttemptCheckToTheTurnEndpointWhenTheTurnCallsOne() throws Exception {
        turns.end(turn.getId(), turn.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(), null);

        Result result = call(WORKER, "PUT", "/internal/llm/turn/" + turn.getId() + "/partial", token());

        assertThat(result.status()).isEqualTo(200);
        assertThat(Objects.requireNonNull(result.authentication()).getDetails()).isInstanceOf(TurnJwt.class);
    }

    @Test
    void shouldRefuseTheTurnWhenItsAttemptEnded() throws Exception {
        turns.end(turn.getId(), turn.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(), null);

        Result result = call(WORKER, "POST", "/internal/llm/responses", token());

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.authentication()).isNull();
    }
}
