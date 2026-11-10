package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
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
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.SandboxReviewClaims;
import de.tum.cit.aet.hephaestus.agent.proxy.ConversationLoopProperties;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
import org.springframework.http.MediaType;
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

    private final ConfigurableApplicationContext gateway;
    private final AgentSandboxes sandboxes;
    private final FakeSandboxClaims claims = new FakeSandboxClaims();
    private final int port;

    /**
     * @param model the provider's answer to each chat completion that the proxy admitted
     */
    public ComposeRunGateway(
            ApplicationContext context,
            ReviewMemberAiPolicy policy,
            ReviewAttemptCustody custody,
            UnaryOperator<JsonNode> model) {
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
                transactions);
        var application = new SpringApplication(
                GatewayApplication.class,
                classNamed("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"),
                classNamed("de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrors"));
        application.addInitializers(gatewayContext -> {
            var factory = gatewayContext.getBeanFactory();
            factory.registerSingleton("reviewComposeSandboxKind", kind);
            factory.registerSingleton("agentNextController", new AgentNextController(sandboxes, List.of(kind)));
            factory.registerSingleton(
                    "agentRunController", new AgentRunController(runs, sandboxes, transactions, List.of(kind)));
            factory.registerSingleton("compositionController", new CompositionController(kind, sessions));
            factory.registerSingleton("modelRoute", modelRoute(model));
            factory.registerSingleton("sandboxRunArguments", new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new SandboxRunArgumentResolver(runs));
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
        port = gateway.getBean(SandboxGatewayConnector.class).port();
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
        var process = new ProcessBuilder("node", DRIVER.toString(), "http://localhost:" + port, token)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        byte[] printed = process.getInputStream().readAllBytes();
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0)
            throw new IllegalStateException("The sandbox driver did not finish: exit " + process.exitValue());
        return new String(printed, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        gateway.close();
    }

    /**
     * The provider behind the model proxy: a call that gets here was admitted by the proxy's security chain. A route
     * function, so that the test context's component scan does not take it for one of its own controllers.
     */
    private static RouterFunction<ServerResponse> modelRoute(UnaryOperator<JsonNode> model) {
        var mapper = new JsonMapper();
        return RouterFunctions.route()
                .POST(
                        "/internal/llm/chat/completions",
                        request -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(mapper.writeValueAsString(
                                        model.apply(mapper.readTree(request.body(String.class))))))
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
