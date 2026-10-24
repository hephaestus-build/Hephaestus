package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JavaJwtWorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobCredentialScope;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerKeyRing;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerTokenDenylistService;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerTokenProperties;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each launch of a review attempt holds one job scope. With real signed credentials, the real gateway chain and the
 * controller over a job row in PostgreSQL, the review launch's credential prepares private feedback only, and the
 * public review launch's credential prepares only the review on the work and reads its history. Only the admission
 * service, whose writes are not under test, is a stand-in.
 */
class ReviewCredentialScopeIntegrationTest extends BaseIntegrationTest {

    private static final int GATEWAY_PORT = 9081;
    private static final String WORKER = "test-worker";

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    private final ResultAdmissionService admission = mock(ResultAdmissionService.class);
    private final WorkerTokenProperties tokens = new WorkerTokenProperties(
            "hephaestus-hub", "hephaestus-worker", Duration.ofHours(1), null, List.of(), null);
    private final WorkerKeyRing keyRing = WorkerKeyRing.fromConfig(tokens);
    private final WorkerJwtIssuer issuer = new WorkerJwtIssuer(keyRing, tokens);
    private MockMvc gateway;
    private ReviewJob job;

    @BeforeEach
    void startTheGateway() throws Exception {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(
                "credential-scope-" + UUID.randomUUID().toString().substring(0, 8)));
        var running = new ReviewJob();
        RuntimeJobTestData.initialize(running, workspace.getId());
        running.setConfigSnapshot(new JsonMapper().createObjectNode());
        running.setStatus(ReviewJobStatus.RUNNING);
        running.setWorkerId(WORKER);
        running.setStartedAt(Instant.now());
        running.prePersist();
        job = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, running));

        var context = new GenericApplicationContext();
        context.refresh();
        var config = new LlmProxySecurityConfig();
        var verifier = new JavaJwtWorkerJwtVerifier(
                keyRing, tokens, mock(WorkerTokenDenylistService.class), new SimpleMeterRegistry());
        BucketResolver buckets = (key, configuration) ->
                Bucket.builder().addLimit(configuration.getBandwidths()[0]).build();
        var accounting = new ProxyAccounting(
                mock(ProxyBudgetGate.class),
                mock(ProxyUsageAccumulator.class),
                mock(MentorTurnUsageAccumulator.class),
                new SimpleMeterRegistry(),
                new JsonMapper());
        // The application's HttpSecurity prototype integrates the servlet API, which hands the controller its caller.
        var chains = new FilterChainProxy(List.of(config.llmProxyFilterChain(
                httpSecurity(context).servletApi(Customizer.withDefaults()),
                new SandboxGatewayProperties(GATEWAY_PORT, 1 << 20, 600),
                jobs,
                mock(MentorTurnRepository.class),
                mock(SandboxGatewaySessions.class),
                verifier,
                buckets,
                accounting,
                new JsonMapper(),
                WorkerPropertiesFixtures.minimal("1", "1"))));
        gateway = MockMvcBuilders.standaloneSetup(new ResultAdmissionController(admission))
                .addFilters(chains)
                .build();
        when(admission.prepareFeedback(any(), any()))
                .thenReturn(new PrepareFeedbackResponseDTO(List.of(), "a".repeat(64), Instant.now()));
    }

    @Test
    void shouldRefuseEveryPublicCompositionCallToTheReviewLaunchsCredential() throws Exception {
        String review = credential(JobCredentialScope.REVIEW);

        assertThat(call("/internal/llm/prepare-feedback", review, preparation(true)))
                .isEqualTo(403);
        assertThat(call("/internal/llm/public-feedback-history", review, "{}")).isEqualTo(403);
        verifyNoInteractions(admission);

        assertThat(call("/internal/llm/prepare-feedback", review, preparation(false)))
                .isEqualTo(200);
        verify(admission).prepareFeedback(any(), any());
    }

    @Test
    void shouldServeThePublicReviewLaunchsCredentialOnlyTheReviewOnTheWork() throws Exception {
        String publicReview = credential(JobCredentialScope.PUBLIC_REVIEW);

        assertThat(call("/internal/llm/admit-results", publicReview, "{}")).isEqualTo(403);
        assertThat(call("/internal/llm/prepare-feedback", publicReview, preparation(false)))
                .isEqualTo(403);
        verifyNoInteractions(admission);

        assertThat(call("/internal/llm/prepare-feedback", publicReview, preparation(true)))
                .isEqualTo(200);
        verify(admission).prepareFeedback(any(), any());
    }

    private String credential(JobCredentialScope scope) {
        Instant work = Instant.now().plus(Duration.ofMinutes(5));
        return issuer.issueForJobUntil(
                job.getId(), job.getWorkspaceId(), job.getAttempt(), work, work.plus(Duration.ofMinutes(10)), scope);
    }

    private String preparation(boolean publicComposition) {
        return new JsonMapper()
                .writeValueAsString(Map.of(
                        "reviewId", job.getReviewId(),
                        "jobId", job.getId(),
                        "attempt", job.getAttempt(),
                        "basisResultIds", List.of(),
                        "composition", List.of(),
                        "publicComposition", publicComposition));
    }

    private int call(String path, String token, String body) throws Exception {
        return gateway.perform(post(path)
                        .with(request -> {
                            request.setLocalPort(GATEWAY_PORT);
                            return request;
                        })
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static HttpSecurity httpSecurity(ApplicationContext context) {
        // Initializes what the configurers build, as the application's post-processor does.
        ObjectPostProcessor<Object> postProcessor = new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                if (object instanceof InitializingBean initializing) {
                    try {
                        initializing.afterPropertiesSet();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return object;
            }
        };
        return new HttpSecurity(
                postProcessor,
                new AuthenticationManagerBuilder(postProcessor),
                Map.of(ApplicationContext.class, context));
    }
}
