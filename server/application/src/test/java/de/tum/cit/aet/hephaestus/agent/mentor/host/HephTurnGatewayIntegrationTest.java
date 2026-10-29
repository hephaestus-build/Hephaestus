package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.FakeExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
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
import java.time.Duration;
import java.time.Instant;
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
 * Every endpoint that a Hephaestus host calls, over real HTTP through the worker's real gateway connector and its real
 * security chain, with a signed turn credential. A request without a body carries no {@code Content-Length}, as the
 * host's {@code fetch} sends it. The worker role is off in the test context, so the gateway runs as a small
 * application of its own, wired from the test context's beans.
 */
class HephTurnGatewayIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** The worker id of {@link WorkerPropertiesFixtures#minimal}. */
    private static final String WORKER = "test-worker";

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MeterRegistry metrics;

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(KEY);

    private final AttemptTokenVerifier verifier = new AttemptTokenVerifier(KEY, new SimpleMeterRegistry());

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
                turns, metrics, workspace.getId(), conversation.getId(), "How should I pin the image?", WORKER);

        HephTurnDispatcher dispatcher = runningHere();
        HephTurnContexts contexts = HephTurnFixtures.contexts(beans);
        var controller = new HephTurnController(
                contexts,
                completion,
                dispatcher,
                new ProxyCallsInFlight(3),
                WorkerPropertiesFixtures.minimal("1", "4"));
        var sandboxController = new HephTurnSandboxController(
                contexts, dispatcher, HephTurnFixtures.folder(beans, new TurnSandboxes(new FakeExecSandboxes(), 0)));
        var application = new SpringApplication(
                GatewayApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"));
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton("hephTurnController", controller);
            factory.registerSingleton("hephTurnSandboxController", sandboxController);
            factory.registerSingleton("reviewJobRepository", jobRepository);
            // The turn endpoints read no turn row on the gateway; a model call would.
            factory.registerSingleton("mentorTurnRepository", gatewayTurnReads);
            factory.registerSingleton("attemptTokenVerifier", verifier);
            factory.registerSingleton("sandboxGatewaySessions", new SandboxGatewaySessions());
            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            factory.registerSingleton("workerProperties", WorkerPropertiesFixtures.minimal("1", "4"));
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
                Arguments.of("POST", "sandbox/read", "sandbox-read.request.json", 200),
                Arguments.of("POST", "sandbox/exec", "sandbox-exec.request.json", 200),
                Arguments.of("POST", "sandbox/load", "sandbox-load.request.json", 200),
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
        for (String path : new String[] {"", "/workspace", "/commands"}) {
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

    /** A dispatcher that runs this test's attempt, as the worker's does once it started the turn on its host. */
    private HephTurnDispatcher runningHere() {
        HephTurnDispatcher dispatcher = mock(HephTurnDispatcher.class);
        when(dispatcher.running(turn.getId(), turn.getAttempt()))
                .thenReturn(Optional.of(new TurnAttempt(
                        turn.getId(), workspace.getId(), developer, conversation.getId(), turn.getAttempt())));
        when(dispatcher.deadline(turn.getId(), turn.getAttempt()))
                .thenReturn(Instant.now().plusSeconds(300));
        return dispatcher;
    }

    private HttpRequest.Builder turnRequest(String endpoint) {
        return HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gatewayPort + "/internal/llm/turn/" + turn.getId() + "/" + endpoint))
                .header("Authorization", "Bearer " + credential());
    }

    /** The signed credential the worker issues for this attempt of the turn. */
    private String credential() {
        return issuer.issueForAttempt(
                AttemptJwt.Kind.TURN,
                turn.getId(),
                workspace.getId(),
                turn.getAttempt(),
                null,
                Instant.now().plus(Duration.ofMinutes(5)));
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
