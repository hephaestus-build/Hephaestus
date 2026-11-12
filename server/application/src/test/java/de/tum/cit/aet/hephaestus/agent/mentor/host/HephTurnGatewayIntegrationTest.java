package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.FakeToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.proxy.ConversationLoopProperties;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.core.io.ClassPathResource;

/**
 * Every endpoint that the conversation loop calls, over real HTTP through the worker's real gateway connector and its real
 * security chain, with a signed turn credential. A request without a body carries no {@code Content-Length}, as the
 * host's {@code fetch} sends it. The worker role is off in the test context, so the gateway runs as a small
 * application of its own, wired from the test context's beans.
 */
class HephTurnGatewayIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private AgentRuns agentRuns;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MeterRegistry metrics;

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));

    private final AttemptTokenVerifier verifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry());

    /** The loop's credential, as the test profile sets it. */
    private static final String LOOP_SECRET = "0123456789abcdef".repeat(4);

    private final MentorTurnRepository gatewayTurnReads = mock(MentorTurnRepository.class);
    private final HttpClient http = HttpClient.newHttpClient();
    private ConfigurableApplicationContext gateway;
    private int gatewayPort;
    private long developer;
    private Workspace workspace;
    private MentorConversation conversation;
    private MentorTurn turn;

    @BeforeEach
    void startTheGateway() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User developerUser = persistUser("heph-gateway-" + suffix);
        workspace = createWorkspace(
                "heph-gateway-" + suffix, "Hephaestus Gateway", "heph-gateway-org", AccountType.ORG, developerUser);
        developer = accountId(developerUser);
        conversation = conversations.findOrCreateMain(workspace.getId(), developer);
        turn = MentorTurnStarts.askAndStart(
                beans, metrics, workspace.getId(), conversation.getId(), "How should I pin the image?");

        var callsInFlight = new ProxyCallsInFlight(12);
        var toolsPods = HephTurnFixtures.toolsPods(beans, new FakeToolsPods());
        HephTurns served = HephTurnFixtures.turns(
                beans,
                mock(MentorLedgerNotifications.class),
                callsInFlight,
                toolsPods,
                issuer,
                Clock.systemUTC(),
                new SimpleMeterRegistry(),
                Integer.MAX_VALUE,
                Duration.ofMillis(100));
        HephTurnContexts contexts = HephTurnFixtures.contexts(beans);
        var controller = new HephTurnController(contexts, completion, served, callsInFlight);
        var sandboxController =
                new HephTurnSandboxController(contexts, served, HephTurnFixtures.folder(beans, toolsPods));
        // No turn waits for this loop; which other tests left queued is not this test's to claim.
        HephTurns idle = mock(HephTurns.class);
        when(idle.assign()).thenReturn(Optional.empty());
        var assignments = new HephTurnAssignmentController(idle);
        var application = new SpringApplication(
                GatewayApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"));
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton("hephTurnController", controller);
            factory.registerSingleton("hephTurnSandboxController", sandboxController);
            factory.registerSingleton("hephTurnAssignmentController", assignments);
            factory.registerSingleton("reviewJobRepository", jobRepository);
            factory.registerSingleton("agentRuns", agentRuns);
            // The turn endpoints read no turn row on the gateway; a model call would.
            factory.registerSingleton("mentorTurnRepository", gatewayTurnReads);
            factory.registerSingleton("attemptTokenVerifier", verifier);
            factory.registerSingleton("sandboxGatewaySessions", new SandboxGatewaySessions());
            factory.registerSingleton("conversationLoopProperties", new ConversationLoopProperties(LOOP_SECRET));
            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            factory.registerSingleton("workerProperties", WorkerPropertiesFixtures.withDrain(Duration.ofMinutes(1)));
            factory.registerSingleton("agentControllerAdvice", new AgentControllerAdvice());
            factory.registerSingleton("globalControllerAdvice", new GlobalControllerAdvice());
        });
        gateway = application.run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                // The test JVM turns the worker role off; the gateway is a worker capability.
                "--" + RuntimeRole.WORKER_PROPERTY + "=true",
                "--" + RuntimeRole.SERVER_PROPERTY + "=false",
                "--hephaestus.sandbox.gateway.port=0");
        gatewayPort = gateway.getBean(SandboxGatewayConnector.class).port();
    }

    @AfterEach
    void stopTheGateway() {
        gateway.close();
    }

    /** Each host endpoint, the body the host sends ({@code null} for none), and the status the worker answers. */
    static Stream<Arguments> hostEndpoints() {
        return Stream.of(
                Arguments.of("GET", "context", null, 200),
                Arguments.of("PUT", "partial", "partial.request.json", 204),
                Arguments.of("POST", "search", "search.request.json", 200),
                // The controller answers: the result, problem or feedback is unknown, so Hephaestus may not show it.
                Arguments.of("POST", "link-result", "{\"resultId\":\"" + UUID.randomUUID() + "\"}", 422),
                Arguments.of("POST", "link-problem", "link-problem.request.json", 422),
                Arguments.of("POST", "show-feedback", "show-feedback.request.json", 422),
                // A refusal of the context folder is an answer the model reads, not an HTTP error.
                Arguments.of("POST", "sandbox/document", "sandbox-document.request.json", 200),
                Arguments.of("GET", "sandbox/documents", null, 200),
                Arguments.of("POST", "sandbox/bind", "{}", 200),
                Arguments.of("POST", "sandbox/discard", "sandbox-discard.request.json", 204),
                Arguments.of("POST", "sandbox/load", "sandbox-load.request.json", 200),
                Arguments.of("POST", "lease", "{}", 204),
                Arguments.of("POST", "end", "end.request.json", 204));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("hostEndpoints")
    void shouldReachTheTurnEndpointWhenTheHostCallsItThroughTheGateway(
            String method, String endpoint, String body, int status) throws Exception {
        HttpRequest.Builder request = turnRequest(endpoint);
        if (body == null) {
            // Like the host's fetch: no body and no Content-Length.
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.startsWith("{") ? body : contract(body)));
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    }

    @Test
    void shouldReadNoTurnRowOnTheGatewayWhenTheHostReportsItsPartialAnswer() throws Exception {
        HttpRequest request = turnRequest("partial")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(contract("partial.request.json")))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(204);
        verifyNoInteractions(gatewayTurnReads);
    }

    @Test
    void shouldRefuseABodyThatDeclaresNoLengthWhenTheHostStreamsIt() throws Exception {
        byte[] body = contract("end.request.json").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = turnRequest("end")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(411);
    }

    @Test
    void shouldRefuseTheTurnCredentialWhenItCallsAReviewRuntimeEndpoint() throws Exception {
        for (String path : new String[] {"", "/workspace"}) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + gatewayPort + "/internal/llm/runtime/" + turn.getId() + path))
                    .header("Authorization", "Bearer " + credential())
                    .GET()
                    .build();

            assertThat(http.send(request, HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .as(path)
                    .isEqualTo(401);
        }
    }

    private HttpRequest.Builder turnRequest(String endpoint) {
        return HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gatewayPort + "/internal/llm/turn/" + turn.getId() + "/" + endpoint))
                .header("Authorization", "Bearer " + credential());
    }

    /** The signed credential the worker issues for this attempt of the turn, with five minutes of work left. */
    private String credential() {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        return issuer.issueForTurn(
                turn.getId(),
                workspace.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                deadline,
                deadline.plus(Duration.ofMinutes(1)));
    }

    private HttpResponse<String> pullAssignment(String credential) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + "/internal/llm/turn-assignments"))
                        .header("Authorization", "Bearer " + credential)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shouldAnswerTheLoopsAssignmentPullOnlyWithTheLoopsCredential() throws Exception {
        assertThat(pullAssignment(LOOP_SECRET).statusCode()).isEqualTo(204);
        assertThat(pullAssignment(credential()).statusCode())
                .as("a turn credential pulls no turn")
                .isEqualTo(401);
        assertThat(pullAssignment("f".repeat(64)).statusCode()).isEqualTo(401);
    }

    private static String contract(String name) throws IOException {
        return new ClassPathResource("agent/heph-turn-contract/" + name).getContentAsString(StandardCharsets.UTF_8);
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
