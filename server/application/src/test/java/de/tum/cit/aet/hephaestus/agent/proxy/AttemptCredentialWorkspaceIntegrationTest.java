package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerConversations;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscript;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrorFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailures;
import de.tum.cit.aet.hephaestus.agent.job.AttemptServerErrors;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyViolations;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.review.AbstractResultAdmissionIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The results routes and the model route take their workspace from the caller's credential alone: a review attempt's
 * token for {@code start-practice}, {@code admit-results} and {@code stage-result}, and a sandbox's token for the
 * composer's model calls. Each call goes over real HTTP through the gateway's security chain to the real controllers,
 * with the tenancy check throwing on any statement that names no workspace. A start catches a refused source sync and
 * only logs it, so each call also leaves the count of refused statements as it was. Another workspace holds a practice of the
 * same slug on the same work, or a composition of the same subject, and its rows stay as they were. Only the model
 * provider behind the proxy is a stand-in. The worker role is off in the test context, so the gateway runs as a small
 * application of its own.
 */
class AttemptCredentialWorkspaceIntegrationTest extends AbstractResultAdmissionIntegrationTest {

    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();
    private static final LlmApiProtocol COMPLETIONS = LlmApiProtocol.OPENAI_COMPLETIONS;
    private static final SourceKind SOURCE = new SourceKind("scm.pull-request.core");
    private static final String CAPTURED = "a".repeat(64);

    /** Every table that a results route or a composer's model call reads or writes, by its workspace. */
    private static final List<String> TOUCHED = List.of(
            "review",
            "review_practice",
            "review_item",
            "review_job",
            "review_composition_basis",
            "result",
            "agent_run",
            "composer_transcript_part");

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ReviewAdmissionData admissions;

    @Autowired
    private ReviewPracticeRepository pinnedPractices;

    @Autowired
    private ComposerTranscript transcript;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private EntityManager entities;

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));
    private final HttpClient http = HttpClient.newHttpClient();
    /** The model provider behind the proxy; the proxy's own routing and ledger have their own tests. */
    private final LlmProxyService provider = mock(LlmProxyService.class);

    private AgentSandboxes sandboxes;
    private ConfigurableApplicationContext gateway;
    private int port;

    /** A running review attempt and the credential its sandbox holds. */
    private record Attempt(ReviewAdmissionData.Pending pending, String token) {
        long workspaceId() {
            return pending.review().getWorkspaceId();
        }
    }

    @BeforeEach
    void startTheGateway() {
        sandboxes = SandboxOwnerFixtures.owner(beans, issuer, runs);
        var controller = new LlmProxyController(provider, transcript, mock(ProxyAccounting.class), json);
        var results = new ResultAdmissionController(
                beans.getBean(ResultAdmissionService.class),
                mock(AttemptCaptureFailures.class),
                mock(AttemptServerErrors.class));
        var application = new SpringApplication(GatewayApplication.class, LlmProxySecurityConfig.class);
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton("llmProxyController", controller);
            factory.registerSingleton("resultAdmissionController", results);
            factory.registerSingleton("composeRuns", new ComposeRuns());
            factory.registerSingleton("sandboxGatewaySessions", new SandboxGatewaySessions());
            factory.registerSingleton("agentSandboxes", sandboxes);
            factory.registerSingleton("agentRuns", runs);
            factory.registerSingleton("reviewJobRepository", beans.getBean(ReviewJobRepository.class));
            factory.registerSingleton("tracer", beans.getBean(Tracer.class));
            factory.registerSingleton(
                    "attemptTokenVerifier",
                    new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry()));
            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            factory.registerSingleton("bucketResolver", beans.getBean(BucketResolver.class));
            factory.registerSingleton("agentGatewayErrors", AgentGatewayErrorFixtures.errors());
            factory.registerSingleton("agentControllerAdvice", new AgentControllerAdvice());
            factory.registerSingleton("globalControllerAdvice", new GlobalControllerAdvice());
        });
        gateway = application.run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                "--" + RuntimeRole.AGENT_ENABLED_PROPERTY + "=true",
                "--hephaestus.sandbox.gateway.port=0");
        port = gateway.getBean(SandboxGatewayConnector.class).port();
    }

    @AfterEach
    void stopTheGateway() {
        gateway.close();
    }

    @Test
    void shouldStartThePracticeOfTheCredentialsWorkspaceAndLeaveTheOtherWorkspaceAlone() throws Exception {
        var scenario = data.scenario();
        var attempt = attempt(scenario);
        long other = attempt(data.inAnotherWorkspace(scenario)).workspaceId();
        sourcesNow(attempt, CAPTURED);
        var before = rows(other);
        double refused = TenancyViolations.count(meters);

        var started = post(attempt.token(), "start-practice", start(scenario));

        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(json.readTree(started.body()).path("current").asBoolean()).isTrue();
        assertThat(practiceStatus(attempt)).isEqualTo("RUNNING");
        assertThat(rows(other)).isEqualTo(before);
        assertThat(TenancyViolations.count(meters)).isEqualTo(refused);
    }

    /**
     * The guard on the assertion above: a source sync that reads every workspace's practices is refused, and the start
     * only logs that and goes on, so the count of refused statements is what shows it.
     */
    @Test
    void shouldCountARefusedSourceSyncThatThePracticeStartOnlyLogs() throws Exception {
        var scenario = data.scenario();
        var attempt = attempt(scenario);
        long other = attempt(data.inAnotherWorkspace(scenario)).workspaceId();
        sourcesNow(attempt, CAPTURED);
        doAnswer(call -> entities.createNativeQuery("SELECT count(*) FROM review_practice")
                        .getSingleResult())
                .when(productItems)
                .sync(any(), anySet(), any());
        var before = rows(other);
        double refused = TenancyViolations.count(meters, "review_practice");

        var started = post(attempt.token(), "start-practice", start(scenario));

        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(practiceStatus(attempt)).isEqualTo("RUNNING");
        assertThat(rows(other)).isEqualTo(before);
        assertThat(TenancyViolations.count(meters, "review_practice")).isGreaterThan(refused);
    }

    @Test
    void shouldSealTheAttemptOfTheCredentialsWorkspaceAndLeaveTheOtherWorkspaceAlone() throws Exception {
        var scenario = data.scenario();
        var attempt = attempt(scenario);
        long other = attempt(data.inAnotherWorkspace(scenario)).workspaceId();
        // The work moved on after its capture, so the practice is withheld at its start and the attempt seals at once.
        sourcesNow(attempt, "b".repeat(64));
        var before = rows(other);
        double refused = TenancyViolations.count(meters);

        var started = post(attempt.token(), "start-practice", start(scenario));
        var sealed = post(attempt.token(), "admit-results", envelope(attempt));
        // A stage after the seal is refused: the attempt admitted its results.
        var late = post(attempt.token(), "stage-result", stage(attempt, scenario));

        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(json.readTree(started.body()).path("current").asBoolean()).isFalse();
        assertThat(sealed.statusCode()).as(sealed.body()).isEqualTo(200);
        assertThat(json.readTree(sealed.body()).path("reviewId").asString())
                .isEqualTo(attempt.pending().review().getId().toString());
        assertThat(jdbc.queryForObject(
                        "SELECT admission_digest FROM review_job WHERE workspace_id = ? AND id = ?",
                        String.class,
                        attempt.workspaceId(),
                        attempt.pending().jobId()))
                .isNotNull();
        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat(rows(other)).isEqualTo(before);
        assertThat(TenancyViolations.count(meters)).isEqualTo(refused);
    }

    @Test
    void shouldServeAndReplayTheComposersAnswerOfTheSandboxsWorkspaceAndLeaveTheOtherWorkspaceAlone() throws Exception {
        long workspace = data.scenario().workspace().getId();
        long other = data.scenario().workspace().getId();
        String subject = "review:" + UUID.randomUUID();
        var own = composing(workspace, subject);
        var foreign = composing(other, subject);
        JsonNode first = ComposerConversations.conversation(COMPLETIONS).getFirst();
        byte[] request = bytes(first.get("request"));
        byte[] answer = bytes(first.get("answer"));
        ObjectNode foreignAnswer = (ObjectNode) first.get("answer").deepCopy();
        foreignAnswer.put("system_fingerprint", "another-workspace");
        var session = ComposerConversations.session(COMPLETIONS, "Compose the review.");
        // The same conversation of the same subject in another workspace, already served there with its own answer.
        transactions.executeWithoutResult(status -> {
            transcript.issued(own.fence(), session);
            transcript.issued(foreign.fence(), session);
        });
        var forward = (ComposerTranscript.Forward)
                transcript.check(other, foreign.runId(), foreign.epoch(), COMPLETIONS, request);
        transactions.executeWithoutResult(status ->
                transcript.served(other, foreign.runId(), foreign.epoch(), COMPLETIONS, forward, bytes(foreignAnswer)));
        var live = sandboxes.ensure(workspace, subject, AgentSandboxKind.REVIEW);
        String token = issuer.issueForSandbox(
                live.id(), workspace, subject, Instant.now().plusSeconds(600));
        doReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(answer))
                .when(provider)
                .proxy(any(), any(), any(), any(), any());
        var before = rows(other);
        double refused = TenancyViolations.count(meters);

        var served = model(token, own, request);
        // The composer lost the answer and sends the conversation again: it gets the answer this claim was served.
        var replayed = model(token, own, request);

        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).isEqualTo(answer);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.body()).isEqualTo(answer);
        verify(provider, times(1)).proxy(any(), any(), any(), any(), any());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM composer_transcript_part"
                                + " WHERE workspace_id = ? AND run_id = ? AND kind = 'SERVED'",
                        Long.class,
                        workspace,
                        own.runId()))
                .isOne();
        assertThat(rows(other)).isEqualTo(before);
        assertThat(TenancyViolations.count(meters)).isEqualTo(refused);
    }

    /** The scenario's pending review with its job running at attempt 0, as the runtime reports it. */
    private Attempt attempt(ReviewIntegrationData.Scenario scenario) {
        var pending = admissions.pending(
                scenario,
                Instant.now(),
                Objects.requireNonNull(scenario.account().getId()));
        admissions.running(pending, 0);
        long workspaceId = pending.review().getWorkspaceId();
        UUID reviewId = pending.review().getId();
        // The attempt's copies of person data are complete and vouch for it, so admission keeps their authority.
        when(custody.find(eq(workspaceId), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
        when(runtime.activeJobIds(workspaceId, reviewId)).thenReturn(Set.of(pending.jobId()));
        when(runtime.unsettledJobIds(workspaceId, reviewId)).thenReturn(Set.of());
        when(runtime.runtime(workspaceId, reviewId))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        0,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "test-trace")))));
        var capture = pinnedPractices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        workspaceId, reviewId, scenario.practice().getId())
                .orElseThrow();
        var facts = json.createObjectNode();
        facts.putObject("sourceFingerprints").put(SOURCE.value(), CAPTURED);
        capture.setCaptureDecision(facts);
        pinnedPractices.saveAndFlush(capture);
        UUID runId = Objects.requireNonNull(
                jdbc.queryForObject("SELECT run_id FROM review_job WHERE id = ?", UUID.class, pending.jobId()));
        int epoch = Objects.requireNonNull(
                jdbc.queryForObject("SELECT lease_epoch FROM agent_run WHERE id = ?", Integer.class, runId));
        Instant work = Instant.now().plus(Duration.ofMinutes(10));
        return new Attempt(
                pending,
                issuer.issueForReview(
                        pending.jobId(), workspaceId, epoch, runId, work, work.plus(Duration.ofMinutes(5))));
    }

    /** What the attempt's start reads of its work's sources now. */
    private void sourcesNow(Attempt attempt, String fingerprint) {
        var item = productItems.require(
                attempt.workspaceId(), attempt.pending().scenario().item().getId());
        doReturn(Map.of(SOURCE, fingerprint)).when(productItems).fingerprints(eq(item), anySet(), any());
    }

    /** A leased {@code review_compose} run of the subject in the workspace. */
    private AgentRunLease composing(long workspaceId, String subject) {
        transactions.executeWithoutResult(
                status -> runs.enqueue(workspaceId, AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BATCH, subject, null));
        return Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspaceId, subject, Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
    }

    private String practiceStatus(Attempt attempt) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM review_practice WHERE workspace_id = ? AND review_id = ? AND practice_id = ?",
                String.class,
                attempt.workspaceId(),
                attempt.pending().review().getId(),
                attempt.pending().scenario().practice().getId()));
    }

    /** Every row of the workspace in the tables these routes touch, as text. */
    private List<String> rows(long workspaceId) {
        var rows = new ArrayList<String>();
        for (String table : TOUCHED)
            rows.addAll(jdbc.queryForList(
                    "SELECT '" + table + " ' || to_jsonb(t)::text FROM " + table
                            + " t WHERE workspace_id = ? ORDER BY 1",
                    String.class,
                    workspaceId));
        return rows;
    }

    private ObjectNode start(ReviewIntegrationData.Scenario scenario) {
        return json.createObjectNode().put("practiceSlug", scenario.practice().getSlug());
    }

    private ObjectNode envelope(Attempt attempt) {
        return json.createObjectNode()
                .put("contractVersion", 3)
                .put("reviewId", attempt.pending().review().getId().toString())
                .put("jobId", attempt.pending().jobId().toString())
                .put("attempt", 0);
    }

    private ObjectNode stage(Attempt attempt, ReviewIntegrationData.Scenario scenario) {
        var request = envelope(attempt);
        request.putArray("results");
        request.putArray("nonResults")
                .addObject()
                .put("practiceSlug", scenario.practice().getSlug())
                .put("reason", "EXECUTION_INCOMPLETE");
        return request;
    }

    private HttpResponse<String> post(String token, String route, JsonNode body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/llm/" + route))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> model(String token, AgentRunLease run, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/llm" + COMPLETIONS.upstreamPath()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header(SandboxRunArgumentResolver.RUN_HEADER, run.runId().toString())
                .header(SandboxRunArgumentResolver.EPOCH_HEADER, Integer.toString(run.epoch()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] bytes(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The composer's run as the gateway resolves it: the sandbox token names the workspace and subject, and the run in
     * the headers resolves only within them. The attempt is the run's own claim.
     */
    private static final class ComposeRuns implements SandboxRunKind {
        @Override
        public AgentRunKind kind() {
            return AgentRunKind.REVIEW_COMPOSE;
        }

        @Override
        public AgentSandboxKind sandbox() {
            return AgentSandboxKind.REVIEW;
        }

        @Override
        public boolean claimed(SandboxRun run) {
            return true;
        }

        @Override
        public Optional<ProxyAttempt> attempt(SandboxRun run) {
            return Optional.of(new ProxyAttempt(
                    ProxyAttempt.Caller.REVIEW,
                    run.fence().runId(),
                    run.fence().workspaceId(),
                    run.fence().epoch(),
                    run.fence().runId(),
                    Instant.now().plus(Duration.ofMinutes(10))));
        }

        @Override
        public JsonNode context(SandboxRun run) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public boolean end(SandboxRun run, Outcome outcome) {
            return false;
        }
    }

    /** Not a configuration class, so the test context does not load it as its own. */
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        ValidationAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
    })
    @Import(SandboxGatewayConfiguration.class)
    static class GatewayApplication {}
}
