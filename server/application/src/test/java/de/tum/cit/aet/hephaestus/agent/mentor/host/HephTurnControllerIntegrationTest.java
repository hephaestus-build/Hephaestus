package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextGenerations;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnEvidence;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker endpoints a Heph host calls for one turn. The worker role is off in the test context, so the controller
 * is built by hand from the context's own beans. The gateway client sends the contract files in
 * {@code heph-turn-contract/} with a signed turn credential, authenticated by the gateway's own filter, and maps
 * errors with the production controller advice. The direct client passes an authenticated credential as the gateway
 * would, to reach the controller after the lease is lost.
 */
class HephTurnControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    /** The worker id of {@link WorkerPropertiesFixtures#minimal}. */
    private static final String WORKER = "test-worker";

    private static final String QUESTION = "How should I pin the deployment image?";

    private static final String TURN_PATH = "/internal/llm/turn/{turnId}/";

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private ReviewJobRepository jobRepository;

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(KEY);

    private final AttemptTokenVerifier verifier = new AttemptTokenVerifier(KEY, new SimpleMeterRegistry());

    @Autowired
    private JsonMapper mapper;

    private final ProxyCallsInFlight callsInFlight = new ProxyCallsInFlight(3);
    private HephTurnDispatcher dispatcher;
    private HephTurnController controller;
    private MockMvc gateway;
    private MockMvc direct;
    private User developer;
    private long developerAccount;
    private Workspace workspace;
    private MentorConversation conversation;
    private MentorTurn turn;
    private Authentication authentication;
    private TurnAttempt attempt;

    @BeforeEach
    void claimATurn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        developer = persistUser("heph-turn-" + suffix);
        workspace = createWorkspace("heph-turn-" + suffix, "Heph Turn", "heph-turn-org", AccountType.ORG, developer);
        developerAccount = accountId(developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), developerAccount);
        turn = MentorTurnStarts.askAndStart(turns, metrics, workspace.getId(), conversation.getId(), QUESTION, WORKER);
        attempt = new TurnAttempt(
                turn.getId(), workspace.getId(), developerAccount, conversation.getId(), turn.getAttempt());
        authentication = HephTurnFixtures.authenticated(attempt);
        dispatcher = mock(HephTurnDispatcher.class);
        when(dispatcher.running(turn.getId(), turn.getAttempt())).thenReturn(Optional.of(attempt));
        controller = controller(HephTurnFixtures.contexts(beans));
        gateway = gateway(controller);
        direct = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentControllerAdvice(), problemDetails(), new GlobalControllerAdvice())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    @Test
    void shouldBuildTheDocumentsAgainWhenTheWorkspaceContextChangedAfterTheyWereCached() {
        TurnContextGenerations generations = new TurnContextGenerations();
        HephTurnContexts contexts = HephTurnFixtures.contexts(beans, generations);
        TurnAttempt credential = attempt;
        Map<String, byte[]> first = contexts.documents(credential);
        assertThat(contexts.documents(credential)).as("cached for the attempt").isSameAs(first);

        generations.changed(workspace.getId());

        Map<String, byte[]> second = contexts.documents(credential);
        assertThat(second)
                .as("an erasure or a consent change drops the cached build")
                .isNotSameAs(first);
        generations.changedAll();
        assertThat(contexts.documents(credential))
                .as("a reconnect that may have missed changes too")
                .isNotSameAs(second);
    }

    private HephTurnController controller(HephTurnContexts contexts) {
        return new HephTurnController(
                contexts, completion, dispatcher, callsInFlight, WorkerPropertiesFixtures.minimal("1", "4"));
    }

    /** The controller behind the gateway's own credential filter. */
    private MockMvc gateway(HephTurnController served) throws Exception {
        // The gateway chain hands the controller its Authentication through the servlet API, as Spring Security does.
        var servletApi = new SecurityContextHolderAwareRequestFilter();
        servletApi.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(served)
                .setControllerAdvice(new AgentControllerAdvice(), problemDetails(), new GlobalControllerAdvice())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(
                        SandboxGatewayFixtures.credentialFilter(jobRepository, turnRepository, verifier, WORKER),
                        servletApi)
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

    /** The signed credential the worker issues for this attempt of the turn. */
    private String credential(int attempt) {
        return issuer.issueForAttempt(
                AttemptJwt.Kind.TURN,
                turn.getId(),
                workspace.getId(),
                attempt,
                null,
                Instant.now().plus(Duration.ofMinutes(5)));
    }

    private ResultActions call(String method, String endpoint, int attempt, String body) throws Exception {
        var request = switch (method) {
            case "POST" -> post(TURN_PATH + endpoint, turn.getId());
            case "PUT" -> put(TURN_PATH + endpoint, turn.getId());
            default -> throw new IllegalArgumentException(method);
        };
        return gateway.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential(attempt))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * A request body exactly as the Heph host sends it, or the context it reads. {@code heph-turn-api.spec.ts} checks
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

    private MentorTurn reloaded() {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    /** Takes the lease back from this attempt as the lease reaper does once it expires; the turn is queued again. */
    private void loseTheLease() {
        turns.renewLease(turn.getId(), turn.getAttempt(), WORKER, Duration.ofSeconds(-1));
        assertThat(turns.expireLease(turn.getId())).isPresent();
    }

    @Test
    void shouldServeTheContextInTheShapeTheHostReadsWhenItAsksWithTheTurnCredential() throws Exception {
        String served = gateway.perform(get(TURN_PATH + "context", turn.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential(turn.getAttempt())))
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
                .contains("sessionId", "entries", "prompt", "situation", "model.apiProtocol", "model.modelId");
    }

    /** A turn that this worker cannot give a folder is told so, or its instructions would send it to read files. */
    @Test
    void shouldTellATurnWithoutAContextFolderThatItCannotReadFiles() throws Exception {
        var folder = mock(TurnContextFolder.class);
        when(folder.available()).thenReturn(false);
        gateway(controller(HephTurnFixtures.contexts(beans, folder)))
                .perform(get(TURN_PATH + "context", turn.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential(turn.getAttempt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextFolder").value(false))
                .andExpect(jsonPath("$.situation").value(containsString(HephTurnContexts.NO_CONTEXT_FOLDER)));
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
    private MockMvc gatewayWith(ResultHistoryQuery results, FeedbackStore feedback) throws Exception {
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
        gatewayWith(results, mock(FeedbackStore.class));

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
        gatewayWith(results, mock(FeedbackStore.class));

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
        FeedbackStore store = mock(FeedbackStore.class);
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
        String foreign = issuer.issueForAttempt(
                AttemptJwt.Kind.TURN,
                turn.getId(),
                workspace.getId() + 1,
                turn.getAttempt(),
                null,
                Instant.now().plus(Duration.ofMinutes(5)));
        int entries = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId())
                .size();

        gateway.perform(get(TURN_PATH + "context", turn.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isNotFound());
        gateway.perform(post(TURN_PATH + "end", turn.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contract("end.request.json")))
                .andExpect(status().isNotFound());

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
        assertThat(ended.getError()).isEqualTo(HephTurnDispatcher.TOOK_TOO_LONG);
        assertThat(ended.getFailureCause()).isEqualTo("its Heph host reported DEADLINE: deadline");
        assertThat(ended.getFailedCall())
                .as("no model call of the attempt failed")
                .isNull();
        assertThat(ended.getFailedCallStatus()).isNull();
    }

    @Test
    void shouldRecordTheLastFailedModelCallOnTheTurnWhenTheHostEndsItFailed() throws Exception {
        var attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt(), BigDecimal.ZERO, WORKER);
        try (var call = callsInFlight.begin(attempt)) {
            call.failed(new MentorTurn.FailedCall("WebClientRequestException", null));
        }
        try (var call = callsInFlight.begin(attempt)) {
            call.failed(new MentorTurn.FailedCall("upstream_status", 503));
        }

        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"ERROR","detail":"provider_unavailable"}""").andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getFailureCause()).isEqualTo("its Heph host reported ERROR: provider_unavailable");
        assertThat(ended.getFailedCall()).isEqualTo("upstream_status");
        assertThat(ended.getFailedCallStatus()).isEqualTo(503);
        assertThat(callsInFlight.lastFailed(turn.getId(), turn.getAttempt()))
                .as("the committed end forgot it")
                .isNull();
    }

    @Test
    void shouldStillRecordTheFailedModelCallWhenTheEndRollsBackAndTheHostRepeatsIt() throws Exception {
        var attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt(), BigDecimal.ZERO, WORKER);
        try (var call = callsInFlight.begin(attempt)) {
            call.failed(new MentorTurn.FailedCall("upstream_status", 503));
        }
        MentorTurnCompletion failingOnce = mock(MentorTurnCompletion.class);
        doThrow(new DataAccessResourceFailureException("the database went away"))
                .doAnswer(AdditionalAnswers.delegatesTo(completion))
                .when(failingOnce)
                .end(any(), anyInt(), any(), any());
        gateway = gateway(new HephTurnController(
                HephTurnFixtures.contexts(beans),
                failingOnce,
                dispatcher,
                callsInFlight,
                WorkerPropertiesFixtures.minimal("1", "4")));
        String end = """
                {"outcome":"FAILED","entries":[],"reason":"ERROR","detail":"provider_unavailable"}""";

        call("POST", "end", turn.getAttempt(), end).andExpect(status().is5xxServerError());
        call("POST", "end", turn.getAttempt(), end).andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getFailedCall()).isEqualTo("upstream_status");
        assertThat(ended.getFailedCallStatus()).isEqualTo(503);
    }

    @Test
    void shouldRecordAHostDetailOutsideTheClosedSetAsUnrecognized() throws Exception {
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"ERROR","detail":"provider said: rate limited for alice@example.com"}""").andExpect(status().isNoContent());

        assertThat(reloaded().getFailureCause()).isEqualTo("its Heph host reported ERROR: unrecognized");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldTellTheDeveloperThatTheModelStoppedRespondingWhenTheHostEndsAStalledTurn(CapturedOutput output)
            throws Exception {
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"STALLED","detail":"upstream_stalled"}""").andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getError()).isEqualTo(HephTurnDispatcher.MODEL_STOPPED_ANSWERING);
        assertThat(output.getAll()).contains("failed: its Heph host reported STALLED: upstream_stalled");
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
        assertThat(ended.getError()).isEqualTo(HephTurnDispatcher.COULD_NOT_FINISH);
        assertThat(output.getAll())
                .contains("Heph turn " + turn.getId() + " attempt " + turn.getAttempt()
                        + " failed: its Heph host reported ERROR: unrecognized")
                .doesNotContain(key)
                .doesNotContain(quoted);
    }

    /** The worker logs exactly the kinds of failure the host names; a kind only one side knows is lost. */
    @Test
    void shouldKnowEveryKindOfFailureWhenTheHostNamesIt() throws Exception {
        String protocol =
                new ClassPathResource("agent/heph-host-protocol.ts").getContentAsString(StandardCharsets.UTF_8);
        int start = protocol.indexOf("FAILURE_DETAILS = [");
        assertThat(start).as("heph-host-protocol.ts declares FAILURE_DETAILS").isNotNegative();
        String list = protocol.substring(start, protocol.indexOf("] as const", start));
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

        direct.perform(post(TURN_PATH + "end", turn.getId())
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lateEnd(late)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/mentor-lease-lost"));

        assertThat(storedFor(late)).isEmpty();
    }

    @Test
    void shouldAnswerNotFoundWhenTheCredentialIsForAnotherTurn() throws Exception {
        direct.perform(post(TURN_PATH + "end", UUID.randomUUID())
                        .principal(authentication)
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
