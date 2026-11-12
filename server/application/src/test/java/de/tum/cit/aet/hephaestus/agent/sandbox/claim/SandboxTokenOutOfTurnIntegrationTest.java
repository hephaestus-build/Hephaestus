package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.ConversationLoopProperties;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Any process in a sandbox can hold its token (ADR 0079 § Update — a tool may hold the sandbox token), so the gateway,
 * not the loop, refuses every write out of turn. Each call here is what a tool could send with the sandbox's own token,
 * over real HTTP through the gateway connector and its security chain, against the run rows in PostgreSQL. The worker
 * role is off in the test context, so the gateway runs as a small application of its own.
 */
@DisplayName("A sandbox token out of turn Integration")
class SandboxTokenOutOfTurnIntegrationTest extends BaseIntegrationTest {

    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    @Autowired
    private AgentSandboxRepository repository;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ReviewJobRepository jobs;

    private final FakeSandboxClaims claims = new FakeSandboxClaims();
    private final HttpClient http = HttpClient.newHttpClient();
    private AgentSandboxes sandboxes;
    private ConfigurableApplicationContext gateway;
    private int gatewayPort;
    private long workspace;

    @BeforeEach
    void startTheGateway() throws Exception {
        workspace = workspaces
                .save(TestEntities.activeWorkspace("out-of-turn-" + UUID.randomUUID()))
                .getId();
        sandboxes = new AgentSandboxes(
                repository,
                runs,
                claims,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY)),
                transactions,
                "http://hephaestus-sandbox-gateway.hephaestus.svc:8081",
                host -> "10.43.0.12",
                new SimpleMeterRegistry());
        var review = new FencedReview(runs);
        var controller = new AgentRunController(runs, sandboxes, transactions, List.of(review));
        var application = new SpringApplication(
                GatewayApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"),
                Class.forName("de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrors"));
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton("agentRunController", controller);
            factory.registerSingleton("modelRoute", new ModelRoute());
            factory.registerSingleton("fencedReview", review);
            factory.registerSingleton("sandboxRunArguments", new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new SandboxRunArgumentResolver(runs, Tracer.NOOP));
                }
            });
            factory.registerSingleton("agentSandboxes", sandboxes);
            factory.registerSingleton("reviewJobRepository", jobs);
            factory.registerSingleton("agentRuns", runs);
            factory.registerSingleton("mentorTurnRepository", mock(MentorTurnRepository.class));
            factory.registerSingleton(
                    "attemptTokenVerifier",
                    new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry()));
            factory.registerSingleton("sandboxGatewaySessions", new SandboxGatewaySessions());
            factory.registerSingleton(
                    "conversationLoopProperties", new ConversationLoopProperties("0123456789abcdef".repeat(4)));
            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            factory.registerSingleton("workerProperties", WorkerPropertiesFixtures.withDrain(Duration.ofMinutes(1)));
            factory.registerSingleton("globalControllerAdvice", new GlobalControllerAdvice());
        });
        gateway = application.run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                "--" + RuntimeRole.WORKER_PROPERTY + "=true",
                "--" + RuntimeRole.SERVER_PROPERTY + "=false",
                "--hephaestus.sandbox.gateway.port=0");
        gatewayPort = gateway.getBean(SandboxGatewayConnector.class).port();
    }

    @AfterEach
    void stopTheGateway() {
        gateway.close();
    }

    /** A sandbox of a new subject, and its token as its claim carries it. */
    private record Sandbox(AgentSandboxes.LiveSandbox live, String token) {}

    private Sandbox sandbox() {
        var live = sandboxes.ensure(
                workspace,
                "review:" + UUID.randomUUID(),
                AgentSandboxKind.REVIEW,
                Instant.now().plus(Duration.ofHours(1)));
        String token = Objects.requireNonNull(claims.created.get(AgentSandboxes.claimName(live.id())))
                .env()
                .get(AgentSandboxes.TOKEN_ENV);
        return new Sandbox(live, Objects.requireNonNull(token));
    }

    /** Queues a run of the sandbox's subject and leases it, as {@code next} does for a kind that the sandbox drives. */
    private AgentRunLease leased(Sandbox sandbox) {
        transactions.executeWithoutResult(status -> runs.enqueue(
                workspace,
                AgentRunKind.REVIEW,
                AgentRunBand.BATCH,
                sandbox.live().subjectKey(),
                null));
        return Objects.requireNonNull(transactions.execute(status ->
                        runs.claimSubject(workspace, sandbox.live().subjectKey(), Set.of(AgentRunKind.REVIEW))))
                .orElseThrow();
    }

    private int call(String method, Sandbox caller, UUID run, int epoch, String route, String body) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + gatewayPort + "/internal/agent/runs/" + run + "/" + route))
                .header("Authorization", "Bearer " + caller.token())
                .header(SandboxRunArgumentResolver.RUN_HEADER, run.toString())
                .header(SandboxRunArgumentResolver.EPOCH_HEADER, Integer.toString(epoch))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int model(Sandbox caller, UUID run, int epoch) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + gatewayPort + "/internal/llm/chat/completions"))
                .header("Authorization", "Bearer " + caller.token())
                .header(SandboxRunArgumentResolver.RUN_HEADER, run.toString())
                .header(SandboxRunArgumentResolver.EPOCH_HEADER, Integer.toString(epoch))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int renew(Sandbox caller, UUID run, int epoch) throws Exception {
        return call("POST", caller, run, epoch, "lease", "{}");
    }

    private int end(Sandbox caller, UUID run, int epoch, String outcome) throws Exception {
        return call("POST", caller, run, epoch, "end", "{\"outcome\":\"" + outcome + "\"}");
    }

    private AgentRunState state(AgentRunLease lease) {
        return runs.find(new AgentRunRef(lease.runId(), workspace))
                .orElseThrow()
                .getState();
    }

    @Test
    void shouldRefuseEveryWriteWithAStaleEpochAndLeaveTheCurrentClaimAlone() throws Exception {
        var sandbox = sandbox();
        var first = leased(sandbox);
        // The first claim hands the run on, and the next claim of the same sandbox holds it with a later epoch.
        assertThat(end(sandbox, first.runId(), first.epoch(), "handed_on")).isEqualTo(204);
        var current = Objects.requireNonNull(transactions.execute(status ->
                        runs.claimSubject(workspace, sandbox.live().subjectKey(), Set.of(AgentRunKind.REVIEW))))
                .orElseThrow();
        assertThat(current.runId()).isEqualTo(first.runId());

        assertThat(model(sandbox, current.runId(), current.epoch())).isEqualTo(200);
        for (int epoch : new int[] {first.epoch(), current.epoch() + 1}) {
            assertThat(model(sandbox, current.runId(), epoch)).isEqualTo(409);
            assertThat(renew(sandbox, current.runId(), epoch)).isEqualTo(409);
            assertThat(end(sandbox, current.runId(), epoch, "completed")).isEqualTo(409);
            assertThat(end(sandbox, current.runId(), epoch, "handed_on")).isEqualTo(409);
            assertThat(call("GET", sandbox, current.runId(), epoch, "context", ""))
                    .isEqualTo(409);
        }
        assertThat(state(current)).isEqualTo(AgentRunState.LEASED);
        assertThat(renew(sandbox, current.runId(), current.epoch())).isEqualTo(200);
    }

    @Test
    void shouldAnswerARunOfAnotherSubjectAsNoRouteAndLeaveItAlone() throws Exception {
        var tool = sandbox();
        var other = sandbox();
        var lease = leased(other);

        assertThat(renew(tool, lease.runId(), lease.epoch())).isEqualTo(404);
        assertThat(model(tool, lease.runId(), lease.epoch())).isEqualTo(401);
        assertThat(end(tool, lease.runId(), lease.epoch(), "completed")).isEqualTo(404);
        assertThat(end(tool, lease.runId(), lease.epoch(), "unisolated")).isEqualTo(404);
        assertThat(call("GET", tool, lease.runId(), lease.epoch(), "context", ""))
                .isEqualTo(404);

        assertThat(state(lease)).isEqualTo(AgentRunState.LEASED);
        assertThat(sandboxes.authenticate(
                        new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry())
                                .verifySandbox(other.token())))
                .isPresent();
    }

    @Test
    void shouldRefuseEveryWriteAfterTheRunEndedAndNeverReviveIt() throws Exception {
        var sandbox = sandbox();
        var lease = leased(sandbox);

        assertThat(end(sandbox, lease.runId(), lease.epoch(), "completed")).isEqualTo(204);

        assertThat(renew(sandbox, lease.runId(), lease.epoch())).isEqualTo(409);
        assertThat(model(sandbox, lease.runId(), lease.epoch())).isEqualTo(409);
        assertThat(end(sandbox, lease.runId(), lease.epoch(), "handed_on")).isEqualTo(409);
        assertThat(end(sandbox, lease.runId(), lease.epoch(), "completed")).isEqualTo(409);
        assertThat(call("GET", sandbox, lease.runId(), lease.epoch(), "context", ""))
                .isEqualTo(409);
        assertThat(state(lease)).isEqualTo(AgentRunState.ENDED);
    }

    @Test
    void shouldRefuseTheTokenOfARevokedSandboxOnEveryRoute() throws Exception {
        var sandbox = sandbox();
        var lease = leased(sandbox);

        assertThat(end(sandbox, lease.runId(), lease.epoch(), "unisolated")).isEqualTo(204);

        assertThat(renew(sandbox, lease.runId(), lease.epoch())).isEqualTo(401);
        assertThat(model(sandbox, lease.runId(), lease.epoch())).isEqualTo(401);
        assertThat(end(sandbox, lease.runId(), lease.epoch(), "completed")).isEqualTo(401);
        var next = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + "/internal/agent/next"))
                .header("Authorization", "Bearer " + sandbox.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertThat(http.send(next, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
        assertThat(state(lease)).isEqualTo(AgentRunState.QUEUED);
    }

    /** The server side of a review run as the kinds write it: every end goes through the run's fence. */
    private record FencedReview(AgentRuns runs) implements SandboxRunKind {
        @Override
        public AgentRunKind kind() {
            return AgentRunKind.REVIEW;
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
            return switch (outcome) {
                case COMPLETED -> runs.end(run.fence()) == AgentRuns.EndOutcome.ENDED;
                case HANDED_ON -> runs.requeue(run.fence(), AgentRuns.Charge.SPEND, Duration.ZERO, null);
            };
        }
    }

    /** The model route as far as authentication decides it: a call that gets here was admitted. */
    @RestController
    static class ModelRoute {
        @PostMapping("/internal/llm/chat/completions")
        String complete() {
            return "routed";
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
