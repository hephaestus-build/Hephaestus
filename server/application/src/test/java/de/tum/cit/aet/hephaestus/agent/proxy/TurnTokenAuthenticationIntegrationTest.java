package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * A Heph turn's credential on the worker's gateway: it acts for its own attempt only, and only while it names
 * the attempt's live lease. The filter and the routing are built by hand because the worker role is off in the test
 * context; {@code LlmProxySecurityConfigTest} runs the whole chain.
 */
class TurnTokenAuthenticationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private AgentRuns agentRuns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MeterRegistry metrics;

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenVerifier jwtVerifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry());

    private final AttemptTokenIssuer jwtIssuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));

    @Autowired
    private ObjectMapper objectMapper;

    private Workspace workspace;
    private User developer;
    private long developerAccount;
    private MentorConversation conversation;
    private MentorTurn turn;

    private record Result(int status, @Nullable Authentication authentication) {}

    @BeforeEach
    void claimATurn() {
        SecurityContextHolder.clearContext();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        developer = persistUser("turn-auth-" + suffix);
        workspace = createWorkspace("turn-auth-" + suffix, "Turn Auth", "turn-auth-org", AccountType.ORG, developer);
        developerAccount = accountId(developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), developerAccount);
        turn = MentorTurnStarts.askAndStart(beans, metrics, workspace.getId(), conversation.getId(), "Question");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String token(UUID turnId, long workspaceId, int attempt) {
        return token(turnId, workspaceId, attempt, Objects.requireNonNull(turn.getRunId()));
    }

    private String token(UUID turnId, long workspaceId, int attempt, UUID leaseId) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        return jwtIssuer.issueForTurn(
                turnId, workspaceId, attempt, leaseId, deadline, deadline.plus(Duration.ofMinutes(1)));
    }

    private String token() {
        return token(turn.getId(), workspace.getId(), turn.getAttempt());
    }

    private Result call(String method, String path, String token) throws Exception {
        var filter = new JobTokenAuthenticationFilter(
                jobRepository,
                agentRuns,
                turnRepository,
                new ConversationLoopProperties(null),
                jwtVerifier,
                null,
                Map.of(),
                Mockito.mock(ProxyAccounting.class));
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
    void shouldRouteAModelCallToTheAttemptWhenTheWorkerHoldsTheLease() throws Exception {
        Result result = call("POST", "/internal/llm/responses", token());

        assertThat(result.status()).isEqualTo(200);
        Authentication authentication = Objects.requireNonNull(result.authentication());
        ProxyIdentity identity = (ProxyIdentity) Objects.requireNonNull(authentication.getPrincipal());
        ProxyRouting routing = new ProxyRoutingService(
                        jobRepository, turnRepository, objectMapper, Mockito.mock(ReviewSnapshotQuery.class), agentRuns)
                .resolve(identity);
        ProxyRouting.BilledAttempt attempt = routing.attempt();
        assertThat(routing.workspaceId()).isEqualTo(workspace.getId());
        assertThat(routing.apiProtocol()).isEqualTo("openai-responses");
        assertThat(attempt.sourceType()).isEqualTo(UsageSourceType.MENTOR_TURN);
        assertThat(attempt.sourceId()).isEqualTo(turn.getId());
        assertThat(attempt.number()).isEqualTo(turn.getAttempt());
        assertThat(attempt.epoch()).as("a turn attempt is its claim").isEqualTo(turn.getAttempt());
        assertThat(authentication.getDetails()).isInstanceOf(AttemptJwt.class);
    }

    @Test
    void shouldAuthenticateTheTurnOnItsOwnEndpointsOnlyWhenItCallsThem() throws Exception {
        assertThat(call("GET", "/internal/llm/turn/" + turn.getId() + "/context", token())
                        .status())
                .isEqualTo(200);
        assertThat(call("GET", "/internal/llm/turn/" + UUID.randomUUID() + "/context", token())
                        .status())
                .isEqualTo(401);
        assertThat(call("GET", "/internal/llm/runtime/" + turn.getId() + "/workspace", token())
                        .status())
                .isEqualTo(401);
    }

    @Test
    void shouldRefuseAModelCallWhenAnotherLeaseOrAttemptOrWorkspaceHoldsTheTurn() throws Exception {
        String path = "/internal/llm/responses";

        assertThat(call("GET", path, token(turn.getId(), workspace.getId(), turn.getAttempt(), UUID.randomUUID()))
                        .status())
                .as("a credential of another lease of the same attempt")
                .isEqualTo(409);
        assertThat(call("GET", path, token(turn.getId(), workspace.getId(), turn.getAttempt() + 1))
                        .status())
                .isEqualTo(409);
        assertThat(call("GET", path, token(turn.getId(), workspace.getId() + 1, turn.getAttempt()))
                        .status())
                .as("the turn is not one of that workspace's")
                .isEqualTo(401);
    }

    @Test
    void shouldLeaveTheAttemptCheckToTheTurnEndpointWhenTheTurnCallsOne() throws Exception {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null));

        Result result = call("PUT", "/internal/llm/turn/" + turn.getId() + "/partial", token());

        assertThat(result.status()).isEqualTo(200);
        assertThat(Objects.requireNonNull(result.authentication()).getDetails()).isInstanceOf(AttemptJwt.class);
    }

    @Test
    void shouldRefuseTheTurnWhenItsAttemptEnded() throws Exception {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null));

        Result result = call("POST", "/internal/llm/responses", token());

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.authentication()).isNull();
    }
}
