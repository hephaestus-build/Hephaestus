package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionSessions;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.composer.ReviewComposeSandboxKind;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentGatewayErrorFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Two HTTP gateways share PostgreSQL and have separate custody caches. Claim selection and composition are fixtures;
 * the run fence, persisted claim, gateway authentication and terminal writes are real.
 */
class ReviewCompositionGatewayReplicasIntegrationTest extends BaseIntegrationTest {
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();
    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private ObjectMapper mapper;

    private ReviewJob job;
    private AgentRunFence fence;
    private AgentSandboxes owner;
    private AgentSandboxes.LiveSandbox sandbox;
    private String token;

    @BeforeEach
    void leaseTheReview() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("gateway-" + UUID.randomUUID()));
        job = Objects.requireNonNull(transactions.execute(status -> {
            var created = new ReviewJob();
            RuntimeJobTestData.initialize(created, workspace.getId());
            created.setStatus(ReviewJobStatus.QUEUED);
            created.setConfigSnapshot(configuration());
            created.prePersist();
            var saved = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, created));
            jdbc.update(
                    "UPDATE agent_run SET concurrency_key=? WHERE id=?",
                    ReviewRunKind.subject(saved.getReviewId()),
                    saved.getRunId());
            assertThat(runs.claimSubject(
                            saved.getWorkspaceId(),
                            ReviewRunKind.subject(saved.getReviewId()),
                            Set.of(AgentRunKind.REVIEW)))
                    .isPresent();
            RuntimeJobTestData.leaseUntil(jdbc, saved, Instant.now().plus(RuntimeJobTestData.LEASE));
            saved.setStatus(ReviewJobStatus.RUNNING);
            saved.setStartedAt(Instant.now());
            return jobs.saveAndFlush(saved);
        }));
        fence = RuntimeJobTestData.fence(jdbc, job);
        owner = SandboxOwnerFixtures.owner(beans, issuer);
        sandbox = owner.ensure(job.getWorkspaceId(), ReviewRunKind.subject(job.getReviewId()), AgentSandboxKind.REVIEW);
        token = issuer.issueForSandbox(
                sandbox.id(),
                job.getWorkspaceId(),
                sandbox.subjectKey(),
                Instant.now().plusSeconds(600));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepThePersistedWithholdingDecisionWhenCompositionMovesToAnotherServer(boolean stopFirst)
            throws Exception {
        var run = leaseTheComposition();
        var firstCustody = new ReviewAttemptCustody(runs);
        var secondCustody = new ReviewAttemptCustody(runs);
        var closing = mock(CompositionSessions.class);
        var firstClosed = new AtomicInteger();
        var secondClosed = new AtomicInteger();
        var firstKind = composition(firstCustody, closing, firstClosed);
        var secondKind = composition(secondCustody, closing, secondClosed);
        Boolean claimed = transactions.execute(status -> firstKind.claimed(run));
        assertThat(claimed).isTrue();
        try (var second = gateway(secondKind, new SandboxGatewaySessions());
                var first = gateway(firstKind, new SandboxGatewaySessions())) {
            assertThat(call(first, "/context", null, fence.epoch()).statusCode())
                    .isEqualTo(200);
            if (stopFirst) {
                stopReplica(first, firstCustody, job.getId());
                assertThat(firstClosed).hasValue(1);
            }

            assertThat(secondKind.attempt(run)).isEmpty();
            assertThat(call(second, "/context", null, fence.epoch()).statusCode())
                    .isEqualTo(200);
            var unchanged = jobs.findById(job.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(unchanged.getRunId()).isEqualTo(fence.runId());
            assertThat(unchanged.spentAttempts()).isEqualTo(job.spentAttempts());
            assertThat(Objects.requireNonNull(unchanged.getCapture())
                            .path("compositionClaim")
                            .path("snapshot")
                            .path("withholdOnly")
                            .asString())
                    .isEqualTo("BUDGET");
            verify(closing, times(1)).withholdOpen(any(), any(), any());
            assertThat(secondClosed).hasValue(0);
            assertThat(call(
                                    stopFirst ? second : first,
                                    "/end",
                                    "{\"outcome\":\"completed\"}".getBytes(StandardCharsets.UTF_8),
                                    fence.epoch())
                            .statusCode())
                    .isEqualTo(204);
            if (!stopFirst) assertThat(secondClosed).hasValue(0);
            secondCustody.releaseEnded();
            assertThat(secondClosed).hasValue(1);
        } finally {
            firstCustody.releaseJob(job.getId());
            secondCustody.releaseJob(job.getId());
        }
    }

    @Test
    void shouldAnswerBusyWithoutWithholdingWhileAnotherServerHoldsTheWithholdingLock() throws Exception {
        var run = leaseTheComposition();
        var custody = new ReviewAttemptCustody(runs);
        var closing = mock(CompositionSessions.class);
        var kind = composition(custody, closing, new AtomicInteger());
        Boolean claimed = transactions.execute(status -> kind.claimed(run));
        assertThat(claimed).isTrue();
        var recorded =
                Objects.requireNonNull(jobs.findById(job.getId()).orElseThrow().getCapture());
        var locks = beans.getBean(ClusterLock.class);
        var key = "review-composition/" + fence.runId() + "/" + fence.epoch();
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Executors.newSingleThreadExecutor();
        try (var web = gateway(kind, new SandboxGatewaySessions())) {
            var held = holder.submit(() -> locks.call(key, Duration.ZERO, () -> {
                holding.countDown();
                try {
                    return release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            var busy = call(web, "/context", null, fence.epoch());

            assertThat(busy.statusCode()).isEqualTo(503);
            assertThat(busy.headers().firstValue("Retry-After")).contains("1");
            verify(closing, never()).withholdOpen(any(), any(), any());
            var unchanged = jobs.findById(job.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(unchanged.getRunId()).isEqualTo(fence.runId());
            assertThat(unchanged.spentAttempts()).isEqualTo(job.spentAttempts());
            assertThat(unchanged.getCapture()).isEqualTo(recorded);
            assertThat(RuntimeJobTestData.fence(jdbc, unchanged)).isEqualTo(fence);

            release.countDown();
            Optional<Boolean> released = held.get(30, TimeUnit.SECONDS);
            assertThat(released).hasValue(true);
            assertThat(call(web, "/context", null, fence.epoch()).statusCode()).isEqualTo(200);
            verify(closing, times(1)).withholdOpen(any(), any(), any());
            assertThat(Objects.requireNonNull(
                                    jobs.findById(job.getId()).orElseThrow().getCapture())
                            .path("compositionClaim")
                            .path("snapshot")
                            .path("withheld")
                            .asBoolean())
                    .isTrue();
        } finally {
            release.countDown();
            holder.shutdownNow();
            custody.releaseJob(job.getId());
        }
    }

    private SandboxRun leaseTheComposition() {
        var reviewRuns = beans.getBean(ReviewRunKind.class);
        transactions.executeWithoutResult(status -> {
            job.setAdmissionAttempt(job.getAttempt());
            job.setAdmissionDigest("a".repeat(64));
            jobs.saveAndFlush(job);
            assertThat(reviewRuns.continueToCompose(job.getId(), fence)).isPresent();
            job = jobs.findById(job.getId()).orElseThrow();
            assertThat(runs.claimSubject(
                            job.getWorkspaceId(),
                            ReviewRunKind.subject(job.getReviewId()),
                            Set.of(AgentRunKind.REVIEW_COMPOSE)))
                    .isPresent();
            RuntimeJobTestData.leaseUntil(jdbc, job, Instant.now().plus(RuntimeJobTestData.LEASE));
            job.setStatus(ReviewJobStatus.RUNNING);
            jobs.saveAndFlush(job);
            fence = RuntimeJobTestData.fence(jdbc, job);
        });
        return new SandboxRun(sandbox, fence, AgentRunKind.REVIEW_COMPOSE, 0);
    }

    private static void stopReplica(ConfigurableApplicationContext replica, ReviewAttemptCustody custody, UUID jobId) {
        replica.close();
        custody.releaseJob(jobId);
    }

    @AfterEach
    void endOnlyThisTestsUnfinishedWork() {
        transactions.executeWithoutResult(status -> {
            var current = jobs.findById(job.getId()).orElseThrow();
            if (current.getStatus() == ReviewJobStatus.RUNNING || current.getStatus() == ReviewJobStatus.QUEUED) {
                current.setStatus(ReviewJobStatus.CANCELLED);
                current.setCompletedAt(Instant.now());
                jobs.saveAndFlush(current);
                RuntimeJobTestData.agree(jdbc, current.getId());
            }
        });
        owner.release(job.getWorkspaceId(), sandbox.id());
    }

    private ReviewComposeSandboxKind composition(
            ReviewAttemptCustody custody, CompositionSessions closing, AtomicInteger closed) {
        var claims = mock(ReviewJobClaimService.class);
        when(claims.claimLeased(fence))
                .thenReturn(Optional.of(new ReviewJobClaimService.ClaimResult(
                        job,
                        fence,
                        ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper),
                        "test.item",
                        CompositionStop.BUDGET)));
        var admission = mock(ResultAdmissionService.class);
        when(admission.composing(fence))
                .thenAnswer(call -> new ResultAdmissionService.Composing(
                        jobs.findById(job.getId()).orElseThrow(),
                        new ResultAdmissionService.AdmissionIdentity(
                                job.getId(), job.getWorkspaceId(), job.getAttempt(), job.getRunId()),
                        null));
        var continuation = mock(ReviewCompositionContinuation.class);
        when(continuation.stageWithoutInputs(any()))
                .thenAnswer(call -> new ReviewCompositionContinuation.Restored(
                        new PreparedJobInputs(Map.of(), List.of(), List.of(closed::incrementAndGet), null, null),
                        Map.of()));
        return new ReviewComposeSandboxKind(
                claims,
                jobs,
                admission,
                custody,
                continuation,
                closing,
                SandboxReviewClaims.terminal(beans),
                beans.getBean(ReviewRunKind.class),
                runs,
                new ReviewCompositionClaim(jobs, transactions, mapper, beans.getBean(ClusterLock.class)),
                new SimpleMeterRegistry(),
                Tracer.NOOP);
    }

    private JsonNode configuration() {
        return new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.invalid/v1",
                                "model",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600)
                .toJson(mapper);
    }

    private ConfigurableApplicationContext gateway(SandboxRunKind kind, SandboxGatewaySessions sessions)
            throws Exception {
        var application = new SpringApplication(
                GatewayApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"));
        application.addInitializers(context -> {
            var factory = context.getBeanFactory();
            factory.registerSingleton(
                    "agentRunController", new AgentRunController(runs, owner, transactions, List.of(kind)));
            factory.registerSingleton("reviewSandboxRuns", kind);
            factory.registerSingleton("sandboxGatewaySessions", sessions);
            factory.registerSingleton("agentSandboxes", owner);
            factory.registerSingleton("agentRuns", runs);
            factory.registerSingleton("reviewJobRepository", jobs);
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
        return application.run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.main.banner-mode=off",
                "--spring.mvc.problemdetails.enabled=true",
                "--" + RuntimeRole.AGENT_ENABLED_PROPERTY + "=true",
                "--hephaestus.sandbox.gateway.port=0");
    }

    private HttpResponse<byte[]> call(
            ConfigurableApplicationContext web, String route, byte @Nullable [] body, int epoch) throws Exception {
        int port = web.getBean(SandboxGatewayConnector.class).port();
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/agent/runs/" + fence.runId() + route))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("X-Agent-Run", fence.runId().toString())
                .header("X-Agent-Epoch", Integer.toString(epoch));
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, ValidationAutoConfiguration.class,
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
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
