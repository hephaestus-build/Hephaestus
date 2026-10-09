package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalFeedbackFixtures;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
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
    private AgentJobRepository jobRepository;

    @Autowired
    private WorkerJwtIssuer issuer;

    @Autowired
    private WorkerJwtVerifier verifier;

    @Autowired
    private JsonMapper mapper;

    private HephTurnDispatcher dispatcher;
    private HephTurnController controller;
    private MockMvc gateway;
    private MockMvc direct;
    private User developer;
    private Workspace workspace;
    private MentorConversation conversation;
    private MentorTurn turn;
    private Authentication authentication;

    @BeforeEach
    void claimATurn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        developer = persistUser("heph-turn-" + suffix);
        workspace = createWorkspace("heph-turn-" + suffix, "Heph Turn", "heph-turn-org", AccountType.ORG, developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
        turn = MentorTurnStarts.askAndStart(turns, metrics, workspace.getId(), conversation.getId(), QUESTION, WORKER);
        authentication = HephTurnFixtures.authenticated(new TurnJwt(
                turn.getId(),
                workspace.getId(),
                developer.getId(),
                conversation.getId(),
                turn.getAttempt(),
                Set.of("llm_proxy"),
                UUID.randomUUID().toString(),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(5))));
        dispatcher = mock(HephTurnDispatcher.class);
        when(dispatcher.runs(turn.getId(), turn.getAttempt())).thenReturn(true);
        controller = new HephTurnController(
                HephTurnFixtures.contexts(beans),
                completion,
                dispatcher,
                new ProxyCallsInFlight(),
                WorkerPropertiesFixtures.minimal("1", "4"));
        // The gateway chain hands the controller its Authentication through the servlet API, as Spring Security does.
        var servletApi = new SecurityContextHolderAwareRequestFilter();
        servletApi.afterPropertiesSet();
        gateway = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentControllerAdvice(), problemDetails(), new GlobalControllerAdvice())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(
                        SandboxGatewayFixtures.credentialFilter(
                                jobRepository, turnRepository, verifier, mapper, WORKER),
                        servletApi)
                .build();
        direct = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentControllerAdvice(), problemDetails(), new GlobalControllerAdvice())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
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
        return issuer.issueForTurn(
                turn.getId(),
                workspace.getId(),
                developer.getId(),
                conversation.getId(),
                attempt,
                Instant.now().plus(Duration.ofMinutes(5)));
    }

    private ResultActions call(String method, String endpoint, int attempt, String body) throws Exception {
        var request =
                switch (method) {
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
                .andExpect(jsonPath("$.situation").value(containsString(HephTurnContexts.EVIDENCE_HEADING)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // A null field is left out of the response; the host reads a missing one as null.
        assertThat(fieldNames(mapper.readTree(served), ""))
                .isSubsetOf(fieldNames(mapper.readTree(contract("context.response.json")), ""))
                .contains("sessionId", "entries", "prompt", "situation", "model.apiProtocol", "model.modelId");
    }

    @Test
    void shouldServeAContextDocumentAndRefuseAnUnknownPathWhenTheTurnFetchesContext() throws Exception {
        call("POST", "fetch-context", turn.getAttempt(), contract("fetch-context.request.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isMap());
        call("POST", "fetch-context", turn.getAttempt(), """
                        {"path":"../etc/passwd"}""").andExpect(status().isUnprocessableContent());
    }

    @Test
    void shouldLinkAnObservationAboutTheDeveloperAndRefuseOthersWhenTheTurnLinksThem() throws Exception {
        ConversationalFeedbackFixtures feedback = new ConversationalFeedbackFixtures(beans, workspace, "Pin images");
        AgentJob job = feedback.job();
        Observation theirs = feedback.observation(job, developer, "occ-theirs");
        Observation colleagues = feedback.observation(job, persistUser("heph-turn-colleague"), "occ-colleague");

        call("POST", "link-observation", turn.getAttempt(), """
                        {"observationId":"%s"}""".formatted(theirs.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value(theirs.getId().toString()));
        call("POST", "link-observation", turn.getAttempt(), """
                        {"observationId":"%s"}""".formatted(colleagues.getId()))
                .andExpect(status().isUnprocessableContent());
        call("POST", "link-observation", turn.getAttempt(), """
                        {"observationId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void shouldFindTheQuestionWithEveryFilterTheHostSendsWhenTheTurnSearches() throws Exception {
        String query = contract("search.request.json");

        call("POST", "search", turn.getAttempt(), query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits.length()").value(1))
                .andExpect(jsonPath("$.hits[0].branch").value("this"))
                .andExpect(jsonPath("$.hits[0].kind").value("USER"))
                .andExpect(jsonPath("$.hits[0].excerpt").value(containsString("deployment")));
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
    }

    @Test
    void shouldTellTheDeveloperThatTheModelStoppedRespondingWhenTheHostEndsAStalledTurn() throws Exception {
        call("POST", "end", turn.getAttempt(), """
                        {"outcome":"FAILED","entries":[],"reason":"STALLED",\
                        "detail":"a model call sent nothing for the host's idle bound"}""").andExpect(status().isNoContent());

        MentorTurn ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ended.getError()).isEqualTo(HephTurnDispatcher.MODEL_STOPPED_ANSWERING);
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
