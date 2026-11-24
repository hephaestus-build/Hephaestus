package de.tum.cit.aet.hephaestus.productfeedback;

import static de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics.STEPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentNextController;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentRunController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.gateway.testing.AgentNextWaitsFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.testing.AgentReviewDrainFixtures;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ProductFeedbackDraftIntegrationTest extends AbstractProductFeedbackFilingIntegrationTest {
    private static final String PRIVATE_REPOSITORY = "acme/feedback-private";

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private ProductFeedbackDraftCalls draftCalls;

    @Autowired
    private ProductFeedbackDestinationRepository destinationRows;

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private List<SandboxRunKind> kinds;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private LlmUsageRecorder usage;

    @Autowired
    private MyReportsService reports;

    @Autowired
    private ProductFeedbackRepository feedbackRows;

    private LlmModel model;
    private @Nullable String previousPublic;
    private @Nullable String previousPrivate;
    private @Nullable Long previousModel;
    private boolean previousPaused;
    private int previousDailyLimit;
    private final List<UUID> reportsCreated = new ArrayList<>();
    private SandboxClaims claims;
    private AgentSandboxes sandboxes;
    private AttemptTokenIssuer issuer;
    private MockMvc gateway;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        var connection = connections.save(LlmCatalogTestFixtures.connection("draft-" + System.nanoTime()));
        model = models.save(LlmCatalogTestFixtures.model(connection, "draft-" + System.nanoTime(), "draft-upstream"));
        ProductFeedbackDestination destination = destination();
        previousPublic = destination.getPublicRepository();
        previousPrivate = destination.getPrivateRepository();
        previousModel = destination.getLlmModelId();
        previousPaused = destination.isPaused();
        previousDailyLimit = destination.getDailyDraftLimit();
        destination.setPublicRepository(null);
        destination.setPrivateRepository(PRIVATE_REPOSITORY);
        destination.setLlmModelId(model.getId());
        destination.setPaused(false);
        destination.setDailyDraftLimit(50);
        destinationRows.save(destination);

        var keys = AttemptKeyFixtures.keyring(AttemptSigningKey.generate());
        issuer = new AttemptTokenIssuer(keys);
        claims = mock(SandboxClaims.class);
        sandboxes = SandboxOwnerFixtures.owner(context, issuer, runs, claims);
        var verifier = new AttemptTokenVerifier(keys, context.getBean(MeterRegistry.class));
        var next = new AgentNextController(
                sandboxes, kinds, AgentReviewDrainFixtures.started(context), AgentNextWaitsFixtures.immediate(context));
        gateway = MockMvcBuilders.standaloneSetup(next, new AgentRunController(runs, sandboxes, transactions, kinds))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new SandboxRunArgumentResolver(runs, Tracer.NOOP))
                .addFilters(SandboxGatewayFixtures.sandboxCredentialFilter(verifier, sandboxes))
                .build();
    }

    @AfterEach
    void tearDown() {
        reportsCreated.forEach(id -> {
            jdbc.update("DELETE FROM product_feedback WHERE id=?", id);
            jdbc.update(
                    "DELETE FROM agent_sandbox WHERE workspace_id IS NULL AND subject_key=?",
                    new ProductFeedbackDrafts.Subject(id, 1).key());
        });
        ProductFeedbackDestination destination = destination();
        destination.setPublicRepository(previousPublic);
        destination.setPrivateRepository(previousPrivate);
        destination.setLlmModelId(previousModel);
        destination.setPaused(previousPaused);
        destination.setDailyDraftLimit(previousDailyLimit);
        destinationRows.save(destination);
    }

    @Test
    void shouldSettleADraftOfAReporterWhoBelongsToNoWorkspace() throws Exception {
        UUID report = report();
        AgentRun queued = run(report);
        assertThat(queued.getKind()).isEqualTo(AgentRunKind.DRAFT_REPORT);
        assertThat(queued.getState()).isEqualTo(AgentRunState.QUEUED);
        assertThat(mine(report).draftDeadline())
                .isBetween(
                        Instant.now().plus(DraftingService.EXPIRY).minusSeconds(5),
                        Instant.now().plus(DraftingService.EXPIRY));

        String token = sandbox(report);
        claim(token, queued);
        String url = url(queued);
        gateway.perform(get(url + "/context").headers(claimHeaders(token, queued)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.modelId").value(model.getUpstreamModelId()))
                .andExpect(jsonPath("$.prompt").isNotEmpty());
        String answer = "{\"disposition\":\"ISSUE\"}";
        transactions.executeWithoutResult(status -> {
            UUID call = usage.open(new LlmUsageRecorder.OpenCall(
                    null,
                    queued.getId(),
                    1,
                    "REPORT_DRAFT",
                    UsageSourceType.PRODUCT_FEEDBACK_DRAFT,
                    report,
                    1,
                    null,
                    ModelKind.CHAT,
                    null,
                    model.getUpstreamModelId(),
                    LlmPriceSnapshot.unpricedInstance(),
                    null));
            usage.complete(call, null, new LlmUsageRecorder.CallUsage(120, 30, 0, 0, 0, 0));
        });
        var result = new AgentRunController.End("completed", null, answer);
        end(token, queued, result);
        assertThat(sandboxState(queued)).isEmpty();
        gateway.perform(post(url(queued) + "/end")
                        .headers(claimHeaders(token, queued))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(result)))
                .andExpect(status().isUnauthorized());

        assertThat(run(report).getStopReason()).isEqualTo(AgentRunStopReason.COMPLETED);
        var filing = filings.findById(report).orElseThrow();
        assertThat(filing.getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(filing.getAnswer()).isEqualTo(answer);
        assertThat(mine(report).draftDeadline()).isNull();
    }

    @Test
    void shouldRefuseAnotherReportSandboxAndAnUnauthenticatedCaller() throws Exception {
        UUID report = report();
        AgentRun queued = run(report);
        claim(sandbox(report), queued);
        String url = url(queued);
        gateway.perform(get(url + "/context")).andExpect(status().isUnauthorized());
        String foreignToken = sandbox(report());
        gateway.perform(get(url + "/context").headers(claimHeaders(foreignToken, queued)))
                .andExpect(status().isNotFound());
        gateway.perform(post(url + "/end")
                        .headers(claimHeaders(foreignToken, queued))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"completed\"}"))
                .andExpect(status().isNotFound());
        var workspace = workspaces
                .save(TestEntities.activeWorkspace("draft-auth-" + UUID.randomUUID()))
                .getId();
        var review = sandboxes.ensure(workspace, "review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        String reviewToken = issuer.issueForSandbox(
                review.id(), workspace, review.subjectKey(), Instant.now().plusSeconds(60));
        gateway.perform(post("/internal/agent/next").header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewToken))
                .andExpect(status().isNoContent());
        gateway.perform(post(url + "/end")
                        .headers(claimHeaders(reviewToken, queued))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"completed\"}"))
                .andExpect(status().isNotFound());
        assertThat(run(report).getState()).isEqualTo(AgentRunState.LEASED);
    }

    @Test
    void shouldWithholdThePromptAndBudgetForAReportWithoutDisclosure() throws Exception {
        UUID report = report();
        AgentRun queued = run(report);
        String token = sandbox(report);
        claim(token, queued);
        jdbc.update("UPDATE product_feedback_filing SET file_disclosed=false WHERE feedback_id=?", report);

        gateway.perform(get(url(queued) + "/context").headers(claimHeaders(token, queued)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").doesNotExist());
        Boolean admitted = transactions.execute(status -> draftCalls.admitCall(report, 1, model.getId()));
        assertThat(admitted).isFalse();
        assertThat(filings.findById(report).orElseThrow().getDraftAdmittedAt()).isNull();
    }

    @Test
    void shouldDeferWhenThePoolHasNoRoom() throws Exception {
        UUID report = report();
        AgentRun queued = run(report);
        String token = sandbox(report);
        claim(token, queued);
        end(token, queued, new AgentRunController.End("handed_on", "model_capacity"));

        AgentRun deferred = run(report);
        assertThat(deferred.getState()).isEqualTo(AgentRunState.QUEUED);
        assertThat(deferred.getAttempt()).isZero();
        assertThat(deferred.getDeadline()).isEqualTo(queued.getDeadline());
        assertThat(mine(report).waitingForModel()).isTrue();
        assertThat(sandboxState(queued)).containsExactly("READY");
    }

    @Test
    void shouldEndAfterTheThirdTransientFailureEvenIfSandboxDeletionFails() throws Exception {
        UUID report = report();
        AgentRun first = run(report);
        String token = sandbox(report);
        doThrow(new IllegalStateException("Claim deletion failed")).when(claims).delete(anyString());
        var unavailable =
                context.getBean(MeterRegistry.class).counter(STEPS, "step", "draft", "outcome", "model_unavailable");
        double before = unavailable.count();
        var failure = new AgentRunController.End("handed_on", "model_unavailable");
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentRun queued = run(report);
            assertThat(queued.getId()).isEqualTo(first.getId());
            assertThat(queued.getState()).isEqualTo(AgentRunState.QUEUED);
            assertThat(queued.getDeadline()).isEqualTo(first.getDeadline());
            assertThat(queued.getAttempt()).isEqualTo(attempt);
            if (attempt > 0) {
                assertThat(sandboxState(first)).containsExactly("READY");
                jdbc.update("UPDATE agent_run SET available_at=clock_timestamp() WHERE id=?", first.getId());
            }

            claim(token, queued);
            end(token, queued, failure);
        }

        AgentRun ended = run(report);
        assertThat(ended.getState()).isEqualTo(AgentRunState.ENDED);
        assertThat(ended.getStopReason()).isEqualTo(AgentRunStopReason.FAILED);
        assertThat(ended.getLeaseEpoch()).isEqualTo(3);
        assertThat(unavailable.count()).isEqualTo(before + 1);
        var filing = filings.findById(report).orElseThrow();
        assertThat(filing.getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(filing.getAnswer()).isNull();
        assertThat(sandboxState(first)).containsExactly("DELETING");
        gateway.perform(get(url(first) + "/context").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldEndWithoutADraftWhenTheModelTimesOutOrRefuses() throws Exception {
        for (String cause : List.of("model_timeout", "model_failed")) {
            UUID report = report();
            AgentRun queued = run(report);
            String token = sandbox(report);
            claim(token, queued);
            end(token, queued, new AgentRunController.End("completed", cause));

            assertThat(run(report).getState()).isEqualTo(AgentRunState.ENDED);
            assertThat(run(report).getStopReason())
                    .isEqualTo(cause.equals("model_timeout") ? AgentRunStopReason.DEADLINE : AgentRunStopReason.FAILED);
            var filing = filings.findById(report).orElseThrow();
            assertThat(filing.getState()).isEqualTo(FilingState.ANSWERED);
            assertThat(filing.getAnswer()).isNull();
        }
    }

    @Test
    void shouldCancelAWithdrawnDraftAndKeepNoLateAnswer() throws Exception {
        UUID report = report();
        AgentRun queued = run(report);
        String token = sandbox(report);
        claim(token, queued);
        jdbc.update("UPDATE product_feedback_filing SET state='STOPPED' WHERE feedback_id=?", report);

        end(token, queued, new AgentRunController.End("completed", null, "A late draft"));
        assertThat(run(report).getStopReason()).isEqualTo(AgentRunStopReason.CANCELLED);
        var filing = filings.findById(report).orElseThrow();
        assertThat(filing.getState()).isEqualTo(FilingState.STOPPED);
        assertThat(filing.getAnswer()).isNull();
    }

    @Test
    void shouldSettleAnExpiredDraftEvenWhenNoSandboxStarted() {
        UUID report = report();
        AgentRun queued = run(report);
        jdbc.update("UPDATE agent_run SET deadline=clock_timestamp()-interval '1 second' WHERE id=?", queued.getId());
        reaper.reap();

        assertThat(run(report).getStopReason()).isEqualTo(AgentRunStopReason.DEADLINE);
        assertThat(filings.findById(report).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
    }

    @Test
    void shouldReserveTheDailyAllowanceBeforeTheModelCall() {
        UUID first = report();
        UUID second = report();
        var destination = destination();
        destination.setDailyDraftLimit(1);
        destinationRows.save(destination);
        Boolean admittedFirst = transactions.execute(status -> draftCalls.admitCall(first, 1, model.getId()));
        Boolean admittedSecond = transactions.execute(status -> draftCalls.admitCall(second, 1, model.getId()));
        assertThat(admittedFirst).isTrue();
        assertThat(admittedSecond).isFalse();
        assertThat(filings.findById(first).orElseThrow().getDraftAdmittedAt()).isNotNull();
        assertThat(filings.findById(second).orElseThrow().getDraftAdmittedAt()).isNull();
    }

    @Test
    void shouldRemoveALeasedDraftAndRevokeItsSandboxWhenTheReportIsErased() throws Exception {
        UUID report = report();
        var run = run(report);
        String token = sandbox(report);
        claim(token, run);

        jdbc.update("DELETE FROM product_feedback WHERE id=?", report);

        assertThat(runs.find(run.ref())).isEmpty();
        assertThat(sandboxState(run)).containsExactly("DELETING");
        gateway.perform(get(url(run) + "/context").headers(claimHeaders(token, run)))
                .andExpect(status().isUnauthorized());
    }

    private AgentRun run(UUID report) {
        var subject = new ProductFeedbackDrafts.Subject(report, 1);
        var id = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM agent_run WHERE workspace_id IS NULL AND kind='DRAFT_REPORT' AND concurrency_key=?",
                UUID.class,
                subject.key()));
        return runs.find(new AgentRunRef(id, AgentRunRef.INSTANCE_SCOPE)).orElseThrow();
    }

    private List<@Nullable String> sandboxState(AgentRun run) {
        return jdbc.queryForList(
                "SELECT state FROM agent_sandbox WHERE workspace_id IS NULL AND subject_key=?",
                String.class,
                run.getConcurrencyKey());
    }

    private String sandbox(UUID report) {
        var sandbox = sandboxes.ensure(
                AgentRunRef.INSTANCE_SCOPE,
                new ProductFeedbackDrafts.Subject(report, 1).key(),
                AgentSandboxKind.DRAFT_REPORT);
        return issuer.issueForSandbox(
                sandbox.id(),
                sandbox.workspaceId(),
                sandbox.subjectKey(),
                Instant.now().plus(Duration.ofMinutes(5)));
    }

    private void claim(String token, AgentRun run) throws Exception {
        gateway.perform(post("/internal/agent/next").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(run.getId().toString()))
                .andExpect(jsonPath("$.kind").value("DRAFT_REPORT"))
                .andExpect(jsonPath("$.epoch").value(run.getLeaseEpoch() + 1));
    }

    private void end(String token, AgentRun run, AgentRunController.End end) throws Exception {
        gateway.perform(post(url(run) + "/end")
                        .headers(claimHeaders(token, run))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(end)))
                .andExpect(status().isNoContent());
    }

    private static String url(AgentRun run) {
        return "/internal/agent/runs/" + run.getId();
    }

    private static HttpHeaders claimHeaders(String token, AgentRun run) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(SandboxRunArgumentResolver.RUN_HEADER, run.getId().toString());
        headers.set(SandboxRunArgumentResolver.EPOCH_HEADER, Integer.toString(run.getLeaseEpoch() + 1));
        return headers;
    }

    // The report as its reporter reads it in Your reports.
    private FilingDTOs.MyProductFeedbackDTO mine(UUID id) {
        long account =
                Objects.requireNonNull(feedbackRows.findById(id).orElseThrow().getAccountId());
        return reports.mine(account, false).stream()
                .filter(report -> report.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private ProductFeedbackDestination destination() {
        return destinationRows
                .findById(ProductFeedbackDestination.SINGLETON)
                .orElseGet(ProductFeedbackDestination::new);
    }

    private UUID report() {
        Account account = new Account("Draft reporter");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        Account reporter = accounts.save(account);
        UUID id = feedbackService
                .add(
                        new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "The list jumps", null, null, false),
                        Objects.requireNonNull(reporter.getId()),
                        null,
                        false)
                .id();
        reportsCreated.add(id);
        return id;
    }
}
