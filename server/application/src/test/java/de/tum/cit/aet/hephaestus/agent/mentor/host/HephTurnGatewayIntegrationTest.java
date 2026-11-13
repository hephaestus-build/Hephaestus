package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrorFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Every endpoint that the conversation loop calls, over real HTTP through the agent runtime's real gateway connector and
 * its real security chain, with a signed turn credential. A request without a body carries no {@code Content-Length},
 * as the host's {@code fetch} sends it. Agent jobs are off in the test context, so the gateway runs as a small
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

    /** This test's signing key: the agent runtime, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));

    private final AttemptTokenVerifier verifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry());

    private AgentSandboxes sandboxes;
    private AgentSandboxes.LiveSandbox sandbox;
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

        var usage = beans.getBean(LlmUsageRecorder.class);
        sandboxes = SandboxOwnerFixtures.owner(beans, issuer);
        HephTurns served = HephTurnFixtures.turns(beans, sandboxes);
        HephTurnContexts contexts = HephTurnFixtures.contexts(beans);
        var controller = new HephTurnController(contexts, completion, served, usage);
        var sandboxController =
                new TurnContextController(contexts, served, HephTurnFixtures.folder(beans), beans.getBean(Clock.class));
        var transactions = beans.getBean(TransactionTemplate.class);
        sandbox = sandboxes.ensure(
                workspace.getId(), TurnRunsAdapter.subject(conversation.getId()), AgentSandboxKind.CONVERSATION);
        var kind = new TurnSandboxRunKind(
                MentorTurnStarts.admitting(beans, metrics),
                beans.getBean(TurnRunsAdapter.class),
                agentRuns,
                served,
                contexts,
                beans.getBean(MentorTurnRepository.class),
                beans.getBean(MentorTurnService.class),
                completion,
                beans.getBean(Clock.class),
                beans.getBean(ObjectMapper.class),
                metrics);
        var runController = new AgentRunController(agentRuns, sandboxes, transactions, List.of(kind));
        var application = new SpringApplication(
                GatewayApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"));
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton("hephTurnController", controller);
            factory.registerSingleton("hephTurnSandboxController", sandboxController);
            factory.registerSingleton("agentRunController", runController);
            factory.registerSingleton("turnSandboxRunKind", kind);
            factory.registerSingleton("agentSandboxes", sandboxes);
            factory.registerSingleton("tracer", beans.getBean(Tracer.class));
            factory.registerSingleton("agentGatewayErrors", AgentGatewayErrorFixtures.errors());
            factory.registerSingleton("reviewJobRepository", jobRepository);
            factory.registerSingleton("agentRuns", agentRuns);
            // The turn endpoints read no turn row on the gateway; a model call would.
            factory.registerSingleton("attemptTokenVerifier", verifier);

            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            // The server's shared buckets, which the gateway's rate limit draws from.
            factory.registerSingleton("bucketResolver", beans.getBean(BucketResolver.class));
            factory.registerSingleton("agentControllerAdvice", new AgentControllerAdvice());
            factory.registerSingleton("globalControllerAdvice", new GlobalControllerAdvice());
        });
        gateway = application.run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                // The test profile turns agent jobs off; the gateway belongs to the agent runtime.
                "--" + RuntimeRole.AGENT_ENABLED_PROPERTY + "=true",
                "--hephaestus.sandbox.gateway.port=0");
        gatewayPort = gateway.getBean(SandboxGatewayConnector.class).port();
    }

    @AfterEach
    void stopTheGateway() {
        gateway.close();
    }

    /** Each host endpoint, the body the host sends ({@code null} for none), and the status the gateway answers. */
    static Stream<Arguments> hostEndpoints() {
        return Stream.of(
                Arguments.of("GET", "context", null, 200),
                Arguments.of("POST", "partial", "partial.request.json", 204),
                Arguments.of("POST", "search", "search.request.json", 200),
                // The controller answers: the result, problem or feedback is unknown, so Hephaestus may not show it.
                Arguments.of("POST", "link-result", "{\"resultId\":\"" + UUID.randomUUID() + "\"}", 422),
                Arguments.of("POST", "link-problem", "link-problem.request.json", 422),
                Arguments.of("POST", "show-feedback", "show-feedback.request.json", 422),
                // A refusal of the context folder is an answer the model reads, not an HTTP error.
                Arguments.of("POST", "sandbox/load", "sandbox-load.request.json", 200),
                Arguments.of("POST", "lease", "{}", 200),
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

    static Stream<Arguments> turnWriteEndpoints() {
        return hostEndpoints().filter(arguments -> arguments.get()[2] != null).map(arguments -> {
            var endpoint = arguments.get();
            return Arguments.of(endpoint[0], endpoint[1], endpoint[2]);
        });
    }

    @ParameterizedTest(name = "refuses stale epoch: {0} {1}")
    @MethodSource("turnWriteEndpoints")
    void shouldRefuseEveryTurnWriteOfAnEarlierEpoch(String method, String endpoint, String body) throws Exception {
        var request = turnRequest(endpoint)
                .setHeader(SandboxRunArgumentResolver.EPOCH_HEADER, Integer.toString(turn.getAttempt() + 1))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.startsWith("{") ? body : contract(body)))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(409);
        assertThat(beans.getBean(MentorTurnRepository.class)
                        .findById(turn.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldRefuseAnotherSubjectsTokenAndLeaveTheTurnRunning() throws Exception {
        var other =
                sandboxes.ensure(workspace.getId(), "conversation:" + UUID.randomUUID(), AgentSandboxKind.CONVERSATION);
        String token = issuer.issueForSandbox(
                other.id(),
                other.workspaceId(),
                other.subjectKey(),
                Instant.now().plusSeconds(300));
        var request = turnRequest("partial")
                .setHeader("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(contract("partial.request.json")))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(404);
        assertThat(beans.getBean(MentorTurnRepository.class)
                        .findById(turn.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldRefuseTurnWritesAfterTheirSandboxWasRevoked() throws Exception {
        String token = credential();
        sandboxes.release(workspace.getId(), sandbox.subjectKey());
        var request = turnRequest("partial")
                .setHeader("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(contract("partial.request.json")))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
        assertThat(sandboxes.hasLive(workspace.getId(), sandbox.subjectKey())).isFalse();
        assertThat(beans.getBean(MentorTurnRepository.class)
                        .findById(turn.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldRefusePartialWritesAfterTheTurnEnded() throws Exception {
        var end = turnRequest("end")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"outcome\":\"FAILED\",\"entries\":[],\"reason\":\"ERROR\",\"detail\":\"host_error\"}"))
                .build();
        assertThat(http.send(end, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(204);
        var request = turnRequest("partial")
                .POST(HttpRequest.BodyPublishers.ofString(contract("partial.request.json")))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(409);
        assertThat(beans.getBean(MentorTurnRepository.class)
                        .findById(turn.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.FAILED);
    }

    @Test
    void shouldBoundThePartialBodyBeforeReadingItsJson() throws Exception {
        var request = turnRequest("partial")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(MentorTurnProgress.MAX_UPDATE_BYTES + 1)))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(413);
        assertThat(beans.getBean(MentorTurnRepository.class)
                        .findById(turn.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.RUNNING);
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

    @Test
    void shouldReadAnItemThroughItsOwningSourceAndRefusePathsOutsideTheContext() throws Exception {
        String resource = "inputs/context/delivered_feedback/" + UUID.randomUUID() + ".json";
        var item = http.send(
                turnRequest("sandbox/document")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"" + resource + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(item.statusCode()).isEqualTo(200);
        assertThat(beans.getBean(ObjectMapper.class)
                        .readTree(item.body())
                        .path("status")
                        .asString())
                .isEqualTo("NOT_FOUND");

        var outside = http.send(
                turnRequest("sandbox/document")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"/etc/passwd\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(outside.statusCode()).isEqualTo(404);
    }

    private HttpRequest.Builder turnRequest(String endpoint) {
        String path = endpoint.equals("context") || endpoint.equals("lease") ? endpoint : "turn/" + endpoint;
        return HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gatewayPort + "/internal/agent/runs/" + turn.getRunId() + "/" + path))
                .header("Authorization", "Bearer " + credential())
                .header("Content-Type", "application/json")
                .header(SandboxRunArgumentResolver.RUN_HEADER, turn.getRunId().toString())
                .header(SandboxRunArgumentResolver.EPOCH_HEADER, Integer.toString(turn.getAttempt()));
    }

    private String credential() {
        return issuer.issueForSandbox(
                sandbox.id(),
                sandbox.workspaceId(),
                sandbox.subjectKey(),
                Instant.now().plusSeconds(300));
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
    void shouldHideTheRetiredAssignmentPullForEveryCredential() throws Exception {
        assertThat(pullAssignment("retired-loop-secret").statusCode()).isEqualTo(404);
        assertThat(pullAssignment(credential()).statusCode()).isEqualTo(404);
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
    static class GatewayApplication implements WebMvcConfigurer {
        private final AgentRuns runs;
        private final Tracer tracer;

        GatewayApplication(AgentRuns runs, Tracer tracer) {
            this.runs = runs;
            this.tracer = tracer;
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new SandboxRunArgumentResolver(runs, tracer));
        }
    }
}
