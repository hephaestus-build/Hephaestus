package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscript;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionSessions;
import de.tum.cit.aet.hephaestus.agent.composer.ReviewComposeSandboxKind;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentNextController;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.CompositionController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.testing.AgentReviewDrainFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.SandboxReviewClaims;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxModelProxy;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sandbox gateway of {@code review_compose} runs, as a small application of its own over the test's PostgreSQL:
 * the real pull, run and composition routes behind the gateway's security chain, with the real composition kind. Only
 * the provider behind the model proxy is scripted. {@link #drive} runs the sandbox's real Node driver against it for
 * one run, with the sandbox's own token.
 */
public final class ComposeRunGateway implements AutoCloseable {
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();
    private static final Path DRIVER = Path.of("src/test/resources/agent/review-compose-sandbox.ts");
    private static final Path FORGED_CALLS = Path.of("src/test/resources/agent/review-compose-forged-calls.ts");

    private final ConfigurableApplicationContext gateway;
    private final AgentSandboxes sandboxes;
    private final FakeSandboxClaims claims = new FakeSandboxClaims();
    private final ScheduledTaskRegistrar outbox = new ScheduledTaskRegistrar();
    private final int port;

    /**
     * @param model the provider's answer to each chat completion that the proxy admitted
     */
    public ComposeRunGateway(
            ApplicationContext context,
            ReviewMemberAiPolicy policy,
            ReviewAttemptCustody custody,
            UnaryOperator<JsonNode> model) {
        this(context, policy, custody, modelRoute(model, context));
    }

    /** The real model proxy, with delivery advanced by the test instead of a background worker. */
    public ComposeRunGateway(ApplicationContext context, ReviewMemberAiPolicy policy, ReviewAttemptCustody custody) {
        this(context, policy, custody, SandboxModelProxy.controller(context));
    }

    private ComposeRunGateway(
            ApplicationContext context,
            ReviewMemberAiPolicy policy,
            ReviewAttemptCustody custody,
            Object modelEndpoint) {
        var runs = context.getBean(AgentRuns.class);
        var transactions = context.getBean(TransactionTemplate.class);
        var jobs = context.getBean(ReviewJobRepository.class);
        var admission = context.getBean(ResultAdmissionService.class);
        var continuation = context.getBean(ReviewCompositionContinuation.class);
        var sessions = new CompositionSessions(
                admission,
                continuation,
                context.getBean(ReviewJobAssignments.class),
                context.getBean(ItemDescriptorRegistry.class),
                context.getBean(PracticeReviewProperties.class),
                runs.capacity(),
                context.getBean(MeterRegistry.class),
                context.getBean(JsonMapper.class),
                context.getBean(FeedbackRepair.class),
                context.getBean(CompositionToolTrace.class));
        sandboxes = new AgentSandboxes(
                context.getBean(AgentSandboxRepository.class),
                runs,
                claims,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY)),
                transactions,
                "http://hephaestus-sandbox-gateway.hephaestus.svc:8081",
                host -> "10.43.0.12",
                new SimpleMeterRegistry());
        var kind = new ReviewComposeSandboxKind(
                SandboxReviewClaims.claimService(context, policy),
                jobs,
                admission,
                custody,
                continuation,
                sessions,
                SandboxReviewClaims.terminal(context),
                context.getBean(ReviewRunKind.class),
                runs,
                transactions,
                new SimpleMeterRegistry(),
                Tracer.NOOP);
        var application = new SpringApplication(
                GatewayApplication.class,
                classNamed("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"),
                classNamed("de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrors"));
        var drain = AgentReviewDrainFixtures.started(context);
        application.addInitializers(gatewayContext -> {
            var factory = gatewayContext.getBeanFactory();
            factory.registerSingleton("reviewComposeSandboxKind", kind);
            factory.registerSingleton("agentNextController", new AgentNextController(sandboxes, List.of(kind), drain));
            factory.registerSingleton(
                    "agentRunController", new AgentRunController(runs, sandboxes, transactions, List.of(kind)));
            factory.registerSingleton(
                    "compositionController",
                    new CompositionController(kind, sessions, context.getBean(ComposerTranscript.class)));
            factory.registerSingleton("modelEndpoint", modelEndpoint);
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
            factory.registerSingleton("proxyAccounting", mock(ProxyAccounting.class));
            // The server's shared buckets, which the gateway's rate limit draws from.
            factory.registerSingleton("bucketResolver", context.getBean(BucketResolver.class));
            factory.registerSingleton("agentReviewDrain", drain);
            factory.registerSingleton("globalControllerAdvice", new GlobalControllerAdvice());
        });
        gateway = application.run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                "--" + RuntimeRole.AGENT_ENABLED_PROPERTY + "=true",
                "--hephaestus.sandbox.gateway.port=0");
        port = gateway.getBean(SandboxGatewayConnector.class).port();
        // The isolated test context has no scheduler; composition waits for the outbox's provider readback.
        outbox.addFixedDelayTask(context.getBean(ActionExecutor.class)::runDue, Duration.ofSeconds(1));
        outbox.afterPropertiesSet();
    }

    /** The token of the review sandbox of {@code subjectKey}, as its claim gives it to the sandbox. */
    public String sandbox(long workspaceId, String subjectKey) {
        var live = sandboxes.ensure(
                workspaceId, subjectKey, AgentSandboxKind.REVIEW, Instant.now().plus(Duration.ofHours(1)));
        return Objects.requireNonNull(Objects.requireNonNull(claims.created.get(AgentSandboxes.claimName(live.id())))
                .env()
                .get(AgentSandboxes.TOKEN_ENV));
    }

    /**
     * Runs the sandbox's Node driver until it ended one run, or found none to run.
     *
     * @return what the driver printed: {@code {"ended": <status>}}, or {@code {"idle": true}}
     */
    public String drive(String token) throws Exception {
        return node(DRIVER, token);
    }

    /**
     * Runs agent-side code with the sandbox's token: it leases the subject's next run, sends changed copies of the
     * composer's first model request and then the request itself, and posts the model's tool call, that call again, and
     * a call that no model wrote ({@code review-compose-forged-calls.ts}).
     *
     * @return what it printed, as one JSON object
     */
    public String forge(String token) throws Exception {
        return node(FORGED_CALLS, token);
    }

    private String node(Path script, String token) throws Exception {
        Path output = Files.createTempFile("review-compose-driver-", ".out");
        try {
            var process = new ProcessBuilder("node", script.toString(), "http://localhost:" + port, token)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile())
                    .start();
            try {
                if (!process.waitFor(2, TimeUnit.MINUTES))
                    throw new IllegalStateException("The sandbox driver did not finish within two minutes");
                if (process.exitValue() != 0)
                    throw new IllegalStateException("The sandbox driver failed: exit " + process.exitValue());
                return Files.readString(output, StandardCharsets.UTF_8);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Override
    public void close() {
        outbox.destroy();
        gateway.close();
    }

    /**
     * The provider behind the model proxy: a call that gets here was admitted by the proxy's security chain. It checks
     * each request against the claim's transcript and records the reply and tool calls of each answer, as the proxy
     * does. A route function, so that the test context's component scan does not take it for one of its own
     * controllers.
     */
    private static RouterFunction<ServerResponse> modelRoute(
            UnaryOperator<JsonNode> model, ApplicationContext context) {
        var mapper = new JsonMapper();
        var transcript = context.getBean(ComposerTranscript.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        return RouterFunctions.route()
                .POST("/internal/llm/chat/completions", request -> {
                    byte[] body = request.body(byte[].class);
                    var headers = request.headers();
                    UUID run = UUID.fromString(Objects.requireNonNull(headers.firstHeader("X-Agent-Run")));
                    int epoch = Integer.parseInt(Objects.requireNonNull(headers.firstHeader("X-Agent-Epoch")));
                    long workspace = Objects.requireNonNull(
                            jdbc.queryForObject("SELECT workspace_id FROM agent_run WHERE id = ?", Long.class, run));
                    if (!(transcript.check(workspace, run, epoch, LlmApiProtocol.OPENAI_COMPLETIONS, body)
                            instanceof ComposerTranscript.Forward forward))
                        return ServerResponse.status(HttpStatus.FORBIDDEN)
                                .header("Hephaestus-Proxy-Refusal", "NOT_COMPOSER_REQUEST")
                                .build();
                    byte[] answer = mapper.writeValueAsBytes(model.apply(mapper.readTree(body)));
                    transcript.served(workspace, run, epoch, LlmApiProtocol.OPENAI_COMPLETIONS, forward, answer);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(answer);
                })
                .build();
    }

    private static Class<?> classNamed(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(missing);
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
