package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JavaJwtWorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobCredentialScope;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerKeyRing;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerTokenDenylistService;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerTokenProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each launch of a review attempt holds one job scope. With real signed credentials, the real gateway chain, and the
 * real admission service over an admitted review in PostgreSQL, the review launch's credential prepares private
 * feedback only, and the public review launch's credential prepares only the review on the work and reads its history.
 * Every case is judged by the HTTP status and the feedback, job and review rows it leaves.
 */
@Import({ReviewIntegrationData.class, ReviewAdmissionData.class})
class ReviewCredentialScopeIntegrationTest extends BaseIntegrationTest {

    private static final Path FABRIC_ROOT = temporaryDirectory();
    private static final int GATEWAY_PORT = 9081;
    // The worker id that WorkerPropertiesFixtures gives the gateway chain.
    private static final String WORKER = "test-worker";
    private static final String ITEM_VERSION = "credential-scope-v1";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ReviewAdmissionData admissionData;

    @Autowired
    private ResultAdmissionService admission;

    @Autowired
    private ResultStore resultStore;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private JobEvidenceFiles files;

    @Autowired
    private PersonDataCopyRecorder recorder;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    // The native provider is outside this proof; the scopes, admission and feedback rows stay real.
    @MockitoBean
    private ScmItemSourceAccess sourceAccess;

    @MockitoBean(name = "scmItemService", enforceOverride = true)
    private ScmItemService source;

    private final WorkerTokenProperties tokens = new WorkerTokenProperties(
            "hephaestus-hub", "hephaestus-worker", Duration.ofHours(1), null, List.of(), null);
    private final WorkerKeyRing keyRing = WorkerKeyRing.fromConfig(tokens);
    private final WorkerJwtIssuer issuer = new WorkerJwtIssuer(keyRing, tokens);
    private MockMvc gateway;
    private ReviewJob job;
    private UUID resultId;
    private PreparedJobInputs inputs;

    @DynamicPropertySource
    static void evidenceStore(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.fabric.root", FABRIC_ROOT::toString);
    }

    @AfterAll
    static void removeEvidenceStore() throws IOException {
        FileUtils.deleteDirectory(FABRIC_ROOT.toFile());
    }

    @BeforeEach
    void admitAReviewAndStartTheGateway() throws Exception {
        databaseTestUtils.cleanDatabase();
        when(sourceAccess.kinds()).thenReturn(Set.of(new ItemKind("scm.pull_request")));
        when(sourceAccess.permits(any(), any())).thenReturn(true);
        when(source.supports(any()))
                .thenAnswer(call -> call.<ItemRef>getArgument(0).kind().equals(new ItemKind("scm.pull_request")));
        when(source.current(any()))
                .thenAnswer(call -> Optional.of(ItemFixtures.snapshot(call.getArgument(0), Map.of())
                        .version(new ItemVersion(ITEM_VERSION, null))
                        .build()));
        var scenario = data.scenario();
        AdmittedReviewJobFixtures.snapshot(scenario.workspace(), connections, models, bindings, resolver, mapper);
        IdentityProvider provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://credential-scope.example"));
        // Feedback is written only for an account with a live identity.
        jdbc.update(
                "INSERT INTO identity(account_id,provider_id,provider_user_key,team_key,linked_at,linked_via)"
                        + " VALUES (?,?,'1001','',now(),'SIGN_IN')",
                scenario.account().getId(),
                provider.getId());

        var pending = admissionData.pending(
                scenario, Instant.now(), scenario.account().getId(), ITEM_VERSION);
        jdbc.update("UPDATE review_job SET worker_id=? WHERE id=?", WORKER, pending.jobId());
        var running = jobs.findById(pending.jobId()).orElseThrow();
        files.beginPersonCapture(running);
        recorder.recordIdentity(new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "1001", null));
        inputs = files.prepare(
                running,
                new PreparedEvidence(
                        Map.of("context/scope.txt", "Retained evidence.".getBytes(StandardCharsets.UTF_8)), null),
                null);
        var receipt = resultStore.admit(
                pending.review().getWorkspaceId(),
                pending.review().getId(),
                pending.jobId(),
                0,
                List.of(admissionData.candidate(pending, ResultOutcome.NOT_MET)));
        resultId = receipt.resultIds().getFirst();
        jdbc.update(
                "UPDATE review_job SET admission_attempt=0,admission_digest=? WHERE id=?",
                "a".repeat(64),
                pending.jobId());
        job = jobs.findById(pending.jobId()).orElseThrow();

        var context = new GenericApplicationContext();
        context.refresh();
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
        var chains = new FilterChainProxy(List.of(new LlmProxySecurityConfig()
                .llmProxyFilterChain(
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
    }

    @AfterEach
    void closeInputs() {
        inputs.close();
    }

    @Test
    void shouldRefuseEveryPublicCompositionCallToTheReviewLaunchsCredential() throws Exception {
        String review = credential(JobCredentialScope.REVIEW);

        assertThat(call("/internal/llm/prepare-feedback", review, preparation(true, "Name the failure case.")))
                .isEqualTo(403);
        assertThat(call("/internal/llm/public-feedback-history", review, "{}")).isEqualTo(403);

        assertNoPublicReview();
    }

    @Test
    void shouldRefuseAReviewOnTheWorkThatIsNotPreparedAsThePublicReview() throws Exception {
        // The review launch may prepare private feedback, but a piece on the work outside the public review is refused.
        assertThat(call(
                        "/internal/llm/prepare-feedback",
                        credential(JobCredentialScope.REVIEW),
                        preparation(false, "Name the failure case.")))
                .isEqualTo(409);

        assertNoPublicReview();
    }

    @Test
    void shouldServeThePublicReviewLaunchsCredentialOnlyTheReviewOnTheWork() throws Exception {
        String publicReview = credential(JobCredentialScope.PUBLIC_REVIEW);

        assertThat(call("/internal/llm/admit-results", publicReview, "{}")).isEqualTo(403);
        assertThat(call("/internal/llm/prepare-feedback", publicReview, preparation(false, "Name the failure case.")))
                .isEqualTo(403);
        assertNoPublicReview();

        assertThat(call("/internal/llm/public-feedback-history", publicReview, "{}"))
                .isEqualTo(200);
        var accepted =
                perform("/internal/llm/prepare-feedback", publicReview, preparation(true, "Name the failure case."));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        String digest = mapper.readTree(accepted.getResponse().getContentAsString())
                .path("contentDigest")
                .asString();

        assertThat(onWorkBodies()).containsExactly("Name the failure case.");
        assertThat(publicReviewDigest()).isEqualTo(digest);
        assertThat(publicReviewAt()).isNotNull();
    }

    @Test
    void shouldRefusePrivateFeedbackSentAsThePublicReview() throws Exception {
        assertThat(call(
                        "/internal/llm/prepare-feedback",
                        credential(JobCredentialScope.PUBLIC_REVIEW),
                        preparation(true, conversation("Talk about the failure case."))))
                .isEqualTo(409);

        assertThat(feedbackRows()).isZero();
        assertNoPublicReview();
    }

    @Test
    void shouldReplayTheAcceptedPublicReviewAndRefuseADifferentOne() throws Exception {
        String publicReview = credential(JobCredentialScope.PUBLIC_REVIEW);
        var first =
                perform("/internal/llm/prepare-feedback", publicReview, preparation(true, "Name the failure case."));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String digest = publicReviewDigest();
        Instant acceptedAt = publicReviewAt();

        var replay =
                perform("/internal/llm/prepare-feedback", publicReview, preparation(true, "Name the failure case."));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(call("/internal/llm/prepare-feedback", publicReview, preparation(true, "Say something else.")))
                .isEqualTo(409);

        assertThat(onWorkBodies()).containsExactly("Name the failure case.");
        assertThat(publicReviewDigest()).isEqualTo(digest);
        assertThat(publicReviewAt()).isEqualTo(acceptedAt);
    }

    private void assertNoPublicReview() {
        assertThat(onWorkBodies()).isEmpty();
        assertThat(publicReviewDigest()).isNull();
        assertThat(publicReviewAt()).isNull();
    }

    private int feedbackRows() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM feedback WHERE workspace_id=? AND review_id=?",
                Integer.class,
                job.getWorkspaceId(),
                job.getReviewId()));
    }

    private List<@Nullable String> onWorkBodies() {
        return jdbc.queryForList(
                "SELECT body FROM feedback WHERE workspace_id=? AND review_id=? AND destination='ON_WORK'",
                String.class,
                job.getWorkspaceId(),
                job.getReviewId());
    }

    private @Nullable String publicReviewDigest() {
        return jdbc.queryForObject("SELECT public_review_digest FROM review_job WHERE id=?", String.class, job.getId());
    }

    private @Nullable Instant publicReviewAt() {
        var at = jdbc.queryForObject(
                "SELECT public_review_at FROM review WHERE id=?", Timestamp.class, job.getReviewId());
        return at == null ? null : at.toInstant();
    }

    private String credential(JobCredentialScope scope) {
        Instant work = Instant.now().plus(Duration.ofMinutes(5));
        return issuer.issueForJobUntil(
                job.getId(), job.getWorkspaceId(), job.getAttempt(), work, work.plus(Duration.ofMinutes(10)), scope);
    }

    /** One summary on the work, built only from this attempt's admitted result. */
    private String preparation(boolean publicComposition, String summary) {
        return preparation(publicComposition, onWork(summary));
    }

    private String preparation(boolean publicComposition, ComposedFeedback piece) {
        return mapper.writeValueAsString(new PrepareFeedbackRequestDTO(
                job.getReviewId(),
                job.getId(),
                job.getAttempt(),
                List.of(resultId),
                List.of(piece),
                publicComposition,
                null));
    }

    private ComposedFeedback conversation(String words) {
        return new ComposedFeedback(
                FeedbackDestination.CONVERSATION,
                List.of(resultId),
                words,
                List.of(),
                null,
                new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(
                                FeedbackCopyKind.CHAT_TURN,
                                0,
                                List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                        List.of(),
                        null,
                        resultId),
                null);
    }

    private ComposedFeedback onWork(String summary) {
        return new ComposedFeedback(
                FeedbackDestination.ON_WORK,
                List.of(resultId),
                summary,
                List.of(),
                null,
                new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(
                                FeedbackCopyKind.SUMMARY,
                                0,
                                List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                        List.of(),
                        null,
                        null),
                null);
    }

    private int call(String path, String token, String body) throws Exception {
        return perform(path, token, body).getResponse().getStatus();
    }

    private MvcResult perform(String path, String token, String body) throws Exception {
        return gateway.perform(post(path)
                        .with(request -> {
                            request.setLocalPort(GATEWAY_PORT);
                            return request;
                        })
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
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

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("review-credential-scope-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
