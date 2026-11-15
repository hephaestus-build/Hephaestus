package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrorFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnEvidence;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.JobTokenAuthenticationFilter;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnClaims;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The routes of one conversation turn run through the production sandbox credential filter, run resolver and
 * controller advice. The test profile disables the sandbox runtime, so these controllers use the context's beans.
 * Requests send the contract files in {@code heph-turn-contract/} with the sandbox credential and run fence.
 */
class HephTurnControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MentorTurnClaims claims;

    @Autowired
    private EntityManager entityManager;

    private static final String QUESTION = "How should I pin the deployment image?";

    private static final String RUN_PATH = "/internal/agent/runs/{runId}/";
    private static final String TURN_PATH = RUN_PATH + "turn/";

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnAccess turnAccess;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MentorTurnProgress progress;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private AgentRuns agentRuns;

    /** This fixture's signing key; the test profile disables the sandbox runtime. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));

    private final AttemptTokenVerifier verifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry());

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private LlmUsageRecorder usage;

    private HephTurns hephTurns;
    private final MutableClock turnClock = new MutableClock(Instant.now(), ZoneOffset.UTC);
    private HephTurnController controller;
    private MockMvc gateway;
    private MockMvc direct;
    private User developer;
    private long developerAccount;
    private Workspace workspace;
    private MentorConversation conversation;
    private MentorTurn turn;
    private AgentSandboxes sandboxes;
    private AgentSandboxes.LiveSandbox sandbox;
    private TurnAttempt attempt;

    @BeforeEach
    void claimATurn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        developer = persistUser("heph-turn-" + suffix);
        workspace =
                createWorkspace("heph-turn-" + suffix, "Hephaestus Turn", "heph-turn-org", AccountType.ORG, developer);
        developerAccount = accountId(developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), developerAccount);
        turn = MentorTurnStarts.askAndStart(beans, metrics, workspace.getId(), conversation.getId(), QUESTION);
        attempt = new TurnAttempt(
                turn.getId(),
                workspace.getId(),
                developerAccount,
                conversation.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()));
        sandboxes = SandboxOwnerFixtures.owner(beans, issuer);
        sandbox = sandboxes.ensure(
                workspace.getId(), "conversation:" + conversation.getId(), AgentSandboxKind.CONVERSATION);
        hephTurns = HephTurnFixtures.turns(beans, sandboxes, turnClock);
        controller = controller(HephTurnFixtures.contexts(beans));
        gateway = gateway(controller);
        direct = gateway;
    }

    private HephTurnController controller(HephTurnContexts contexts) {
        return new HephTurnController(contexts, completion, hephTurns, usage);
    }

    /** The controller behind the gateway's own credential filter. */
    private MockMvc gateway(HephTurnController served) throws Exception {
        // The gateway chain hands the controller its Authentication through the servlet API, as Spring Security does.
        var servletApi = new SecurityContextHolderAwareRequestFilter();
        servletApi.afterPropertiesSet();
        var kind = new TurnSandboxRunKind(
                MentorTurnStarts.admitting(beans, metrics),
                beans.getBean(TurnRunsAdapter.class),
                agentRuns,
                hephTurns,
                HephTurnFixtures.contexts(beans),
                turnAccess,
                turns,
                completion,
                beans.getBean(Clock.class),
                mapper,
                metrics);
        var runController =
                new AgentRunController(agentRuns, sandboxes, beans.getBean(TransactionTemplate.class), List.of(kind));
        return MockMvcBuilders.standaloneSetup(served, runController)
                .setCustomArgumentResolvers(new SandboxRunArgumentResolver(agentRuns, beans.getBean(Tracer.class)))
                .defaultRequest(get("/").header(SandboxRunArgumentResolver.RUN_HEADER, turn.getRunId())
                        .header(SandboxRunArgumentResolver.EPOCH_HEADER, turn.getAttempt()))
                .setControllerAdvice(
                        AgentGatewayErrorFixtures.errors(),
                        new AgentControllerAdvice(),
                        problemDetails(),
                        new GlobalControllerAdvice())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(SandboxGatewayFixtures.sandboxCredentialFilter(verifier, sandboxes), servletApi)
                .build();
    }

    /** What {@code spring.mvc.problemdetails.enabled} registers: a status exception answers with its status. */
    private static ResponseEntityExceptionHandler problemDetails() {
        return new ProblemDetails();
    }

    @ControllerAdvice
    @Order(0)
    private static final class ProblemDetails extends ResponseEntityExceptionHandler {}

    private static String piId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** The signed token of this conversation sandbox, valid for five more minutes. */
    private String credential() {
        return issuer.issueForSandbox(
                sandbox.id(),
                sandbox.workspaceId(),
                sandbox.subjectKey(),
                Instant.now().plusSeconds(300));
    }

    private ResultActions call(String method, String endpoint, int attempt, String body) throws Exception {
        var request = switch (method) {
            case "POST" -> post((endpoint.equals("lease") ? RUN_PATH : TURN_PATH) + endpoint, turn.getRunId());
            case "PUT" -> put(TURN_PATH + endpoint, turn.getRunId());
            default -> throw new IllegalArgumentException(method);
        };
        return gateway.perform(request.header(SandboxRunArgumentResolver.EPOCH_HEADER, attempt)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * A request body exactly as the Hephaestus host sends it, or the context it reads. {@code heph-turn-api.spec.ts} checks
     * that the host's own HTTP client sends and reads these same files.
     */
    private static String contract(String name) throws IOException {
        return new ClassPathResource("agent/heph-turn-contract/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    /** The names of every object field, nested ones as {@code parent.child}. */
    private static Set<String> fieldNames(JsonNode node, String prefix) {
        Set<String> names = new TreeSet<>();
        for (String name : node.propertyNames()) {
            names.add(prefix + name);
            if (node.path(name).isObject()) {
                names.addAll(fieldNames(node.path(name), prefix + name + "."));
            }
        }
        return names;
    }

    /** A Pi message entry as the host's session manager writes it. */
    private static String piMessage(String id, String parentId, String message) {
        return """
                {"type":"message","id":"%s","parentId":"%s","timestamp":"2026-10-08T07:00:01.000Z","message":%s}""".formatted(id, parentId, message);
    }

    private static String assistantText(String text) {
        return """
                {"role":"assistant","content":[{"type":"text","text":"%s"}],"api":"openai-responses",\
                "provider":"hephaestus","model":"test-model","usage":{"input":120,"output":40,"cacheRead":0,\
                "cacheWrite":0,"totalTokens":160},"stopReason":"stop","timestamp":1791529201000}""".formatted(text);
    }

    /** The entry this turn stored for the Pi entry {@code piId}. */
    private MentorEntry stored(String piId) {
        return storedFor(piId).orElseThrow(() -> new AssertionError("No entry stored for Pi entry " + piId));
    }

    private Optional<MentorEntry> storedFor(String piId) {
        return MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()).stream()
                .filter(entry -> {
                    JsonNode payload = entry.getPayload();
                    return payload != null && piId.equals(payload.path("id").asString(""));
                })
                .findFirst();
    }

    /** When the lease of the turn's run ends. */
    private Instant runLeasedUntil() {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT leased_until FROM agent_run WHERE id = ?", Timestamp.class, turn.getRunId()))
                .toInstant();
    }

    private MentorTurn reloaded() {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    /** Takes the lease back from this attempt as the lease reaper does once it expires; the turn is queued again. */
    private void loseTheLease() {
        claims.takeBack(turn);
    }

    @Test
    void shouldServeTheContextInTheShapeTheHostReadsWhenItAsksWithTheTurnCredential() throws Exception {
        String served = gateway.perform(get(RUN_PATH + "context", turn.getRunId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(conversation.getId().toString()))
                .andExpect(jsonPath("$.prompt").value(containsString(QUESTION)))
                .andExpect(jsonPath("$.model.apiProtocol").value("openai-responses"))
                .andExpect(jsonPath("$.model.modelId").value("test-model"))
                .andExpect(jsonPath("$.situation").value(containsString(MentorTurnEvidence.START)))
                .andExpect(jsonPath("$.contextFolder").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // A null field is left out of the response; the host reads a missing one as null.
        assertThat(fieldNames(mapper.readTree(served), ""))
                .isSubsetOf(fieldNames(mapper.readTree(contract("context.response.json")), ""))
                .contains(
                        "sessionId",
                        "entries",
                        "prompt",
                        "situation",
                        "model.apiProtocol",
                        "model.modelId",
                        "systemPrompt");
    }

    /**
     * Every model call of a turn sends the system prompt again. Detail that only some answers need belongs in the
     * context folder's {@code FIELDS.md}, which Heph reads on demand. The tool descriptions are sent with every call
     * too, and this budget does not count them.
     */
    @Test
    void shouldKeepTheSystemPromptWithinItsBudgetWhenItServesTheContext() {
        assertThat(HephTurnContexts.SYSTEM_PROMPT.getBytes(StandardCharsets.UTF_8).length)
                .as("system prompt bytes")
                .isLessThanOrEqualTo(15 * 1024);
        assertThat(HephTurnFixtures.contexts(beans).context(attempt).systemPrompt())
                .isEqualTo(HephTurnContexts.SYSTEM_PROMPT);
    }

    @Test
    void shouldRenewTheLeaseOfTheTurnsRunWhenTheLoopRenewsIt() throws Exception {
        Instant claimedUntil = runLeasedUntil();

        call("POST", "lease", turn.getAttempt(), "{}").andExpect(status().isOk());

        assertThat(runLeasedUntil()).isAfter(claimedUntil);
        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldAnswerThatTheTurnStoppedAndStillTakeItsEndWhenTheDeveloperStopsIt() throws Exception {
        turns.stop(workspace.getId(), turn.getId());

        call("POST", "partial", turn.getAttempt(), """
                        {"sequence":1,"baseSequence":0,"prefix":0,"suffix":0,"text":"{}"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value(ProblemType.MENTOR_TURN_STOPPED.uri().toString()))
                .andExpect(jsonPath("$.stop").value("developer"));
        call("POST", "lease", turn.getAttempt(), "{}").andExpect(status().isOk());
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"STOPPED","entries":[%s],"reason":null}""".formatted(
                                piMessage("s0a1b2c3", "question", assistantText("First, tag the"))))
                .andExpect(status().isNoContent());

        MentorTurn stopped = reloaded();
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turns.isHeld(stopped)).isFalse();
        assertThat(stored("s0a1b2c3").getText()).isEqualTo("First, tag the");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void shouldAnswerThatTheDeadlinePassedAndStillTakeTheEndWhenTheAttemptRunsPastIt(long millisPastDeadline)
            throws Exception {
        Instant deadline = Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT deadline FROM agent_run WHERE id = ?", Timestamp.class, turn.getRunId()))
                .toInstant()
                .minus(TurnRunsAdapter.GRACE);
        turnClock.advance(Duration.between(turnClock.instant(), deadline).plusMillis(millisPastDeadline));

        direct.perform(post(TURN_PATH + "partial", turn.getRunId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sequence":1,"baseSequence":0,"prefix":0,"suffix":0,"text":"{}"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.stop").value("deadline"));
        direct.perform(post(TURN_PATH + "end", turn.getRunId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contract("end-deadline.request.json")))
                .andExpect(status().isNoContent());

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.FAILED);
    }

    @Test
    void shouldRefuseAPartialWhenTheStartedRunHasNoDeadline() throws Exception {
        // The database permits clearing a deadline only on requeue.
        claims.takeBackUnbilled(turn);
        turn = claims.claim(turn);

        call("POST", "partial", turn.getAttempt(), """
                        {"sequence":1,"baseSequence":0,"prefix":0,"suffix":0,"text":"{}"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A started turn's run has a deadline"));

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    /** One result in the developer's history, about {@code developerAccountId}, carrying {@code problems}. */
    private static ResultHistoryEntry result(UUID resultId, long developerAccountId, UUID... problems) {
        ResultHistoryEntry entry = mock(ResultHistoryEntry.class);
        when(entry.resultId()).thenReturn(resultId);
        when(entry.developerAccountId()).thenReturn(developerAccountId);
        List<ProblemHistoryEntry> carried = new ArrayList<>();
        for (UUID problemId : problems) {
            ProblemHistoryEntry problem = mock(ProblemHistoryEntry.class);
            when(problem.problemId()).thenReturn(problemId);
            carried.add(problem);
        }
        when(entry.problems()).thenReturn(carried);
        return entry;
    }

    /** The gateway in front of a controller whose turn contexts read these results and claim through this store. */
    private MockMvc gatewayWith(ResultHistoryQuery results, ConversationClaims feedback) throws Exception {
        gateway = gateway(controller(HephTurnFixtures.contexts(beans, results, feedback)));
        return gateway;
    }

    @Test
    void shouldLinkTheDevelopersOwnResultAndRefuseAnyOtherWhenTheTurnLinksAResult() throws Exception {
        UUID theirs = UUID.randomUUID();
        UUID colleaguesResult = UUID.randomUUID();
        ResultHistoryEntry own = result(theirs, developerAccount);
        ResultHistoryEntry colleagues = result(colleaguesResult, developerAccount + 1);
        ResultHistoryQuery results = mock(ResultHistoryQuery.class);
        when(results.find(workspace.getId(), theirs, developerAccount, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(own));
        when(results.find(
                        workspace.getId(),
                        colleaguesResult,
                        developerAccount,
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(colleagues));
        gatewayWith(results, mock(ConversationClaims.class));

        call(
                        "POST",
                        "link-result",
                        turn.getAttempt(),
                        contract("link-result.request.json").replaceAll("[0-9a-f-]{36}", theirs.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultId").value(theirs.toString()));
        call("POST", "link-result", turn.getAttempt(), """
                        {"resultId":"%s"}""".formatted(colleaguesResult))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").isString());
        call("POST", "link-result", turn.getAttempt(), """
                        {"resultId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void shouldLinkAProblemThroughTheDevelopersResultThatCarriesItWhenTheTurnLinksAProblem() throws Exception {
        UUID resultId = UUID.randomUUID();
        UUID problemId = UUID.randomUUID();
        ResultHistoryQuery results = mock(ResultHistoryQuery.class);
        ResultHistoryEntry carrier = result(resultId, developerAccount, problemId);
        when(results.forDeveloper(eq(workspace.getId()), eq(developerAccount), any(), anyInt()))
                .thenReturn(List.of(carrier));
        when(results.find(workspace.getId(), resultId, developerAccount, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(carrier));
        gatewayWith(results, mock(ConversationClaims.class));

        call("POST", "link-problem", turn.getAttempt(), """
                        {"problemId":"%s"}""".formatted(problemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problemId").value(problemId.toString()));
        call("POST", "link-problem", turn.getAttempt(), """
                        {"problemId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void shouldShowFeedbackOnlyWhileTheAttemptHoldsItsClaimWhenTheTurnShowsFeedback() throws Exception {
        UUID ready = UUID.randomUUID();
        UUID taken = UUID.randomUUID();
        ConversationClaims store = mock(ConversationClaims.class);
        when(store.claimConversationCopy(workspace.getId(), ready, turn.getId(), turn.getAttempt()))
                .thenReturn(Optional.of(new CopyWriteFence(ready, UUID.randomUUID(), turn.getId(), Instant.now())));
        when(store.claimConversationCopy(workspace.getId(), taken, turn.getId(), turn.getAttempt()))
                .thenReturn(Optional.empty());
        gatewayWith(mock(ResultHistoryQuery.class), store);

        call("POST", "show-feedback", turn.getAttempt(), """
                        {"feedbackId":"%s"}""".formatted(ready))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackId").value(ready.toString()));
        call("POST", "show-feedback", turn.getAttempt(), """
                        {"feedbackId":"%s"}""".formatted(taken))
                .andExpect(status().isUnprocessableContent());
        verify(store).claimConversationCopy(workspace.getId(), ready, turn.getId(), turn.getAttempt());
    }

    @Test
    void shouldFindTheQuestionWithEveryFilterTheHostSendsWhenTheTurnSearches() throws Exception {
        String query = contract("search.request.json");

        call("POST", "search", turn.getAttempt(), query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits.length()").value(1))
                .andExpect(jsonPath("$.hits[0].branch").value("this"))
                .andExpect(jsonPath("$.hits[0].kind").value("USER"))
                .andExpect(jsonPath("$.hits[0].excerpt").value(containsString("deployment")))
                // In the developer's zone with its weekday, as every time a turn reads.
                .andExpect(jsonPath("$.hits[0].createdAt")
                        .value(matchesPattern("[A-Z][a-z]{2} \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} [+-]\\d{2}:\\d{2}")));
        // A field the worker does not bind would be dropped without an error, and the search would ignore it.
        assertThat(mapper.readValue(query, HephTurnController.SearchRequest.class))
                .hasNoNullFieldsOrProperties();
    }

    @Test
    void shouldRetryTheEndAfterAnErasureFenceWithoutLosingTheLeaseOrAnswer() throws Exception {
        var erasure = beans.getBean(PersonDataCopyFence.class).erase();
        try (erasure) {
            call("POST", "end", turn.getAttempt(), contract("end.request.json"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"));
            assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
            assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                    .hasSize(1);
        }
        call("POST", "end", turn.getAttempt(), contract("end.request.json")).andExpect(status().isNoContent());
        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(stored("a0a1b2c3").getText()).isEqualTo("Pin it by digest.");
    }

    @Test
    void shouldStoreTheEntriesWhenTheHostEndsTheTurn() throws Exception {
        String done = contract("end.request.json");

        call("POST", "end", turn.getAttempt(), done).andExpect(status().isNoContent());

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                .extracting(MentorEntry::getKind)
                .containsExactly(
                        MentorEntry.Kind.USER,
                        MentorEntry.Kind.HEPH,
                        MentorEntry.Kind.TOOL_RESULT,
                        MentorEntry.Kind.TOOL_RESULT,
                        MentorEntry.Kind.HEPH);
        assertThat(stored("q0a1b2c3").getText()).isEqualTo(QUESTION);
        MentorEntry fetched = stored("f0a1b2c3");
        assertThat(Objects.requireNonNull(fetched.getPayload()).path("parentId").asString(""))
                .isEqualTo("c0a1b2c3");
        assertThat(stored("a0a1b2c3").getText()).isEqualTo("Pin it by digest.");

        call("POST", "end", turn.getAttempt(), done).andExpect(status().isConflict());
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                .as("a repeated end stores nothing twice")
                .hasSize(5);
    }

    @Test
    void shouldRefuseATokenOfAnotherWorkspaceAndReadOrWriteNothingOfTheTurn() throws Exception {
        String foreign = issuer.issueForSandbox(
                sandbox.id(),
                workspace.getId() + 1,
                sandbox.subjectKey(),
                Instant.now().plusSeconds(300));
        int entries = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId())
                .size();

        // The turn's row holds no lease for that workspace, as for an attempt that ended.
        gateway.perform(get(RUN_PATH + "context", turn.getRunId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
        gateway.perform(post(TURN_PATH + "end", turn.getRunId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contract("end.request.json")))
                .andExpect(status().isUnauthorized());

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                .as("the foreign end stored nothing")
                .hasSize(entries);
    }

    @Test
    void shouldRefuseTheEndAndStoreNothingWhenAnEntryHasNoTypeOrId() throws Exception {
        String withoutType = piId();
        String withoutId = piId();

        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"DONE","reason":null,"entries":[{"id":"%s","parentId":"question",\
                        "timestamp":"2026-10-08T07:00:01.000Z","message":%s}]}""".formatted(withoutType, assistantText("No type")))
                .andExpect(status().isBadRequest());
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"DONE","reason":null,"entries":[{"type":"message","parentId":"%s",\
                        "timestamp":"2026-10-08T07:00:01.000Z","message":%s}]}""".formatted(withoutId, assistantText("No id")))
                .andExpect(status().isBadRequest());

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(storedFor(withoutType)).isEmpty();
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                .noneMatch(entry -> "No id".equals(entry.getText()));
    }

    @Test
    void shouldWordTheFailureForTheDeveloperWhenTheHostEndsATurnPastItsDeadline() throws Exception {
        call("POST", "end", turn.getAttempt(), contract("end-deadline.request.json"))
                .andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getError()).isEqualTo(MentorTurnCompletion.TOOK_TOO_LONG);
        assertThat(ended.getFailureCause()).isEqualTo("its conversation sandbox reported DEADLINE: deadline");
        assertThat(ended.getFailedCall())
                .as("no model call of the attempt failed")
                .isNull();
        assertThat(ended.getFailedCallStatus()).isNull();
    }

    @Test
    void shouldRecordTheLastFailedModelCallOnTheTurnWhenTheHostEndsItFailed() throws Exception {
        UUID firstCall = failedCall(new MentorTurn.FailedCall("WebClientRequestException", null));
        UUID lastCall = failedCall(new MentorTurn.FailedCall("upstream_status", 503));

        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"ERROR","detail":"provider_unavailable"}""").andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getFailureCause()).isEqualTo("its conversation sandbox reported ERROR: provider_unavailable");
        assertThat(ended.getFailedCall()).isEqualTo("upstream_status");
        assertThat(ended.getFailedCallStatus()).isEqualTo(503);
        assertThat(usage.latestFailure(workspace.getId(), Objects.requireNonNull(turn.getRunId()), turn.getAttempt()))
                .as("the committed end retains the durable failure")
                .isEqualTo(new MentorTurn.FailedCall("upstream_status", 503));
        assertThat(jdbc.queryForObject(
                        "SELECT call_failure ->> 'kind' FROM llm_usage_event WHERE id = ?", String.class, firstCall))
                .isEqualTo("WebClientRequestException");
        assertThat(jdbc.queryForObject(
                        "SELECT (call_failure ->> 'status')::integer FROM llm_usage_event WHERE id = ?",
                        Integer.class,
                        lastCall))
                .isEqualTo(503);
    }

    @Test
    void shouldStillRecordTheFailedModelCallWhenTheEndRollsBackAndTheHostRepeatsIt() throws Exception {
        UUID failedCall = failedCall(new MentorTurn.FailedCall("upstream_status", 503));
        MentorTurnCompletion failingOnce = mock(MentorTurnCompletion.class);
        doThrow(new DataAccessResourceFailureException("the database went away"))
                .doAnswer(AdditionalAnswers.delegatesTo(completion))
                .when(failingOnce)
                .end(anyLong(), any(), anyInt(), any(), any());
        gateway = gateway(new HephTurnController(HephTurnFixtures.contexts(beans), failingOnce, hephTurns, usage));
        String end = """
                {"outcome":"FAILED","entries":[],"reason":"ERROR","detail":"provider_unavailable"}""";

        call("POST", "end", turn.getAttempt(), end).andExpect(status().is5xxServerError());
        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(jdbc.queryForObject(
                        "SELECT (call_failure ->> 'status')::integer FROM llm_usage_event WHERE id = ?",
                        Integer.class,
                        failedCall))
                .isEqualTo(503);
        call("POST", "end", turn.getAttempt(), end).andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getFailedCall()).isEqualTo("upstream_status");
        assertThat(ended.getFailedCallStatus()).isEqualTo(503);
    }

    private UUID failedCall(MentorTurn.FailedCall failure) {
        return Objects.requireNonNull(beans.getBean(TransactionTemplate.class).execute(tx -> {
            UUID id = usage.open(new LlmUsageRecorder.OpenCall(
                    workspace.getId(),
                    Objects.requireNonNull(turn.getRunId()),
                    turn.getAttempt(),
                    "MENTOR_TURN",
                    UsageSourceType.MENTOR_TURN,
                    turn.getId(),
                    turn.getAttempt(),
                    null,
                    ModelKind.CHAT,
                    null,
                    "test-model",
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.NO_CHARGE,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    null));
            usage.complete(id, workspace.getId(), null, failure);
            return id;
        }));
    }

    @Test
    void shouldRecordAHostDetailOutsideTheClosedSetAsUnrecognized() throws Exception {
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"ERROR","detail":"provider said: rate limited for alice@example.com"}""").andExpect(status().isNoContent());

        assertThat(reloaded().getFailureCause()).isEqualTo("its conversation sandbox reported ERROR: unrecognized");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldTellTheDeveloperThatTheModelStoppedRespondingWhenTheHostEndsAStalledTurn(CapturedOutput output)
            throws Exception {
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"STALLED","detail":"upstream_stalled"}""").andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getError()).isEqualTo(HephTurns.MODEL_STOPPED_ANSWERING);
        assertThat(output.getAll()).contains("failed: its conversation sandbox reported STALLED: upstream_stalled");
    }

    /**
     * An end in the shape a host of the previous release sends, whose detail is the provider's error text. The worker
     * log names the reason and that the detail is unrecognized; neither the key nor the question it quotes reach it.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLogNoneOfTheHostsWordsWhenAFailedEndCarriesFreeText(CapturedOutput output) throws Exception {
        String key = "sk-canary-7Qx9";
        String quoted = "my private question about the outage";

        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"ERROR",\
                        "detail":"Incorrect API key provided: %s for \\"%s\\""}""".formatted(key, quoted)).andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getError()).isEqualTo(HephTurns.COULD_NOT_FINISH);
        assertThat(output.getAll())
                .contains("conversation turn " + turn.getId() + " attempt " + turn.getAttempt()
                        + " failed: its conversation sandbox reported ERROR: unrecognized")
                .doesNotContain(key)
                .doesNotContain(quoted);
    }

    /**
     * The loop makes each change of its partial answer, so any server forwards it: here a whole snapshot, then a change
     * made from it, rebuild the answer for every reader.
     */
    @Test
    void shouldForwardTheLoopsChangesSoReadersRebuildTheWholeAnswer() throws Exception {
        String first = "{\"activity\":\"writing\",\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"Pin\"}]}]}";
        String next = first.replace("\"Pin\"", "\"Pin the image\"");
        int prefix = first.indexOf("Pin") + 3;
        int suffix = first.length() - prefix;

        call("POST", "partial", turn.getAttempt(), update(10, 0, 0, 0, first)).andExpect(status().isNoContent());
        call("POST", "partial", turn.getAttempt(), update(11, 10, prefix, suffix, " the image"))
                .andExpect(status().isNoContent());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(progress.snapshot(reloaded()))
                        .isEqualTo(JsonMapper.shared().readTree(next)));
    }

    @Test
    void shouldRefuseAPartialAnswerThatCouldBeNoChangeOfItsAttempt() throws Exception {
        call("POST", "partial", turn.getAttempt(), update(0, 0, 0, 0, "{}")).andExpect(status().isBadRequest());
        call("POST", "partial", turn.getAttempt(), update(5, 5, 0, 0, "x")).andExpect(status().isBadRequest());
    }

    /** The loop stops sending an answer that readers would drop; both sides hold the one limit. */
    @Test
    void shouldStopThePartialAnswerAtTheLengthReadersKeep() throws Exception {
        String protocol =
                new ClassPathResource("agent/heph-host-protocol.js").getContentAsString(StandardCharsets.UTF_8);
        Matcher limit = Pattern.compile("MAX_PARTIAL_CHARS = ([0-9 *]+);").matcher(protocol);
        assertThat(limit.find())
                .as("heph-host-protocol.js declares MAX_PARTIAL_CHARS")
                .isTrue();
        long chars = 1;
        Matcher factor = Pattern.compile("[0-9]+").matcher(limit.group(1));
        while (factor.find()) {
            chars *= Long.parseLong(factor.group());
        }

        assertThat(chars).isEqualTo(MentorTurnProgress.MAX_SNAPSHOT_CHARS);
    }

    private static String update(long sequence, long base, int prefix, int suffix, String text) {
        return JsonMapper.shared()
                .createObjectNode()
                .put("sequence", sequence)
                .put("baseSequence", base)
                .put("prefix", prefix)
                .put("suffix", suffix)
                .put("text", text)
                .toString();
    }

    /** The worker logs exactly the kinds of failure the host names; a kind only one side knows is lost. */
    @Test
    void shouldKnowEveryKindOfFailureWhenTheHostNamesIt() throws Exception {
        String protocol =
                new ClassPathResource("agent/heph-host-protocol.js").getContentAsString(StandardCharsets.UTF_8);
        int start = protocol.indexOf("FAILURE_DETAILS = [");
        assertThat(start).as("heph-host-protocol.js declares FAILURE_DETAILS").isNotNegative();
        String list = protocol.substring(start, protocol.indexOf("];", start));
        Set<String> named = new TreeSet<>();
        Matcher kind = Pattern.compile("\"([a-z_]+)\"").matcher(list);
        while (kind.find()) {
            named.add(kind.group(1));
        }

        assertThat(named).isEqualTo(new TreeSet<>(HephTurnController.FAILURE_DETAILS));
    }

    @Test
    void shouldRefuseTheEndWhenItNamesNoOutcome() throws Exception {
        call("POST", "end", turn.getAttempt(), """
                        {"entries":[]}""").andExpect(status().isBadRequest());

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldAnswerConflictAndStoreNothingWhenTheAttemptLostItsLease() throws Exception {
        loseTheLease();
        String late = piId();

        call("POST", "end", turn.getAttempt(), lateEnd(late)).andExpect(status().isConflict());

        assertThat(storedFor(late)).isEmpty();
        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
    }

    @Test
    void shouldAnswerAConflictProblemWhenTheLeaseIsLostAfterTheGatewayAdmittedTheCall() throws Exception {
        loseTheLease();
        String late = piId();

        direct.perform(post(TURN_PATH + "end", turn.getRunId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lateEnd(late)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Lease lost"))
                .andExpect(jsonPath("$.detail").value(JobTokenAuthenticationFilter.ATTEMPT_ENDED))
                .andExpect(result -> {
                    var problem = mapper.readValue(result.getResponse().getContentAsString(), ProblemDetail.class);
                    assertThat(Optional.ofNullable(problem.getType()).orElse(URI.create("about:blank")))
                            .hasToString("about:blank");
                });

        assertThat(storedFor(late)).isEmpty();
    }

    @Test
    void shouldAnswerNotFoundWhenTheCredentialIsForAnotherTurn() throws Exception {
        direct.perform(post(TURN_PATH + "end", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"DONE","entries":[],"reason":null}"""))
                .andExpect(status().isNotFound());

        assertThat(reloaded().getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    /** An end that carries one answer. */
    private static String lateEnd(String piId) {
        return """
                {"outcome":"DONE","reason":null,"entries":[%s]}""".formatted(piMessage(piId, "question", assistantText("Too late.")));
    }
}
