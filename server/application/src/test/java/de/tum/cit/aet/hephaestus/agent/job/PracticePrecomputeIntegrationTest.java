package de.tum.cit.aet.hephaestus.agent.job;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The precompute runs of a review, from the runner's report to the trace and the practice needs: one row per
 * staged script under the job's workspace, and needs that come from the newest run of today's script and from
 * today's routing.
 */
class PracticePrecomputeIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String NEEDS_URI = "/workspaces/{slug}/practices/precompute";
    private static final String REVIEW_URI = "/workspaces/{slug}/agents/jobs/{jobId}/precompute";
    private static final Instant MONDAY = Instant.parse("2026-10-05T09:00:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private AgentJobPrecomputeRunRepository runRepository;

    @Autowired
    private PiResultParser parser;

    @Autowired
    private ReviewOutcomeLookup reviewOutcomes;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository revisionRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private PrecomputeRunRecorder recorder;

    @BeforeEach
    void setUpWorkspaces() {
        // The recorder runs in the worker role, which the integration context leaves off.
        recorder = new PrecomputeRunRecorder(parser, runRepository, meterRegistry, objectMapper);
        workspace = createWorkspace(
                "precompute-needs",
                "Precompute needs",
                "precompute-needs-org",
                AccountType.ORG,
                persistUser("precompute-needs-owner"));
        ensureAdminMembership(workspace);
        ensureWorkspaceMembership(workspace, persistUser("testuser"), WorkspaceMembership.WorkspaceRole.MEMBER);
        otherWorkspace = createWorkspace(
                "other-precompute-needs",
                "Other precompute needs",
                "other-precompute-needs-org",
                AccountType.ORG,
                persistUser("other-precompute-needs-owner"));
    }

    @Test
    void shouldRecordEachStagedScriptOnceWhenTheReportNamesSomeOfThem() {
        Practice commentQuality = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        Practice errorHandling = persistPractice(workspace, "error-handling", "Error handling", "scan()");
        Practice naming = persistPractice(workspace, "naming", "Naming", null);
        AgentJob job = persistJob(workspace, commentQuality, errorHandling, naming);
        double skippedBefore = scriptsCounted("skipped");

        record(job, MONDAY, """
                {"slug":"comment-quality","status":"skipped","leads":0,
                 "models":[{"slot":"decision","need":"required","bound":false,"notRated":{}}]}""", """
                {"slug":"naming","status":"ok","leads":4,"models":[]}""");

        Map<String, AgentJobPrecomputeRun> runs = runsOf(workspace, job);
        assertThat(runs).containsOnlyKeys("comment-quality", "error-handling");
        AgentJobPrecomputeRun skipped = Objects.requireNonNull(runs.get("comment-quality"));
        assertThat(skipped.getStatus()).isEqualTo(PrecomputeRunStatus.SKIPPED);
        assertThat(skipped.getLeads()).isZero();
        assertThat(skipped.getPracticeRevisionId())
                .isEqualTo(Objects.requireNonNull(commentQuality.getCurrentRevision())
                        .getId());
        assertThat(skipped.getFinishedAt()).isEqualTo(MONDAY);
        assertThat(skipped.attempt()).isEqualTo(job.getRetryCount());
        assertThat(skipped.getModels()).isEqualTo(objectMapper.readTree("""
                        [{"purpose":"PRACTICE_DECISION","need":"REQUIRED","bound":false,"notRated":[]}]"""));
        AgentJobPrecomputeRun unfinished = Objects.requireNonNull(runs.get("error-handling"));
        assertThat(unfinished.getStatus()).isEqualTo(PrecomputeRunStatus.NOT_FINISHED);
        assertThat(unfinished.getModels()).isNull();
        assertThat(runRepository.findByWorkspaceIdAndJobIdIn(otherWorkspace.getId(), List.of(job.getId())))
                .isEmpty();
        assertThat(scriptsCounted("skipped")).isEqualTo(skippedBefore + 1);
    }

    @Test
    void shouldRecordNothingWhenTheRunnerWroteNoReport() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(workspace, practice);

        transactions.executeWithoutResult(
                status -> recorder.record(jobRepository.findById(job.getId()).orElseThrow(), null, Map.of(), MONDAY));

        assertThat(runsOf(workspace, job)).isEmpty();
    }

    @Test
    void shouldGiveTheTraceTheRunsOfTheReviewWhenAskedFromItsOwnWorkspaceOnly() {
        Practice commentQuality = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        Practice errorHandling = persistPractice(workspace, "error-handling", "Error handling", "scan()");
        AgentJob job = persistJob(workspace, commentQuality, errorHandling);
        record(job, MONDAY, """
                {"slug":"comment-quality","status":"ok","leads":4,
                 "models":[{"slot":"decision","need":"optional","bound":true,"notRated":{"deadline":5}}]}""", """
                {"slug":"error-handling","status":"timeout","leads":0}""");

        ReviewOutcomeLookup.ReviewOutcome outcome = Objects.requireNonNull(reviewOutcomes
                .findByIds(workspace.getId(), List.of(job.getId()))
                .get(job.getId()));
        PrecomputeRunDTO run =
                Objects.requireNonNull(outcome.precomputeByPracticeSlug().get("comment-quality"));

        assertThat(run.status()).isEqualTo(PrecomputeRunStatus.OK);
        assertThat(run.leads()).isEqualTo(4);
        assertThat(run.models().getFirst().notRated().getFirst().count()).isEqualTo(5);
        // The trace lists what became of model calls; a script whose models are not known made none it can list.
        assertThat(Objects.requireNonNull(outcome.precomputeByPracticeSlug().get("error-handling"))
                        .models())
                .isEmpty();
        assertThat(reviewOutcomes.findByIds(otherWorkspace.getId(), List.of(job.getId())))
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldListTheNeedsOfTheNewestRunWhenNoBindingServesATier() {
        Practice commentQuality = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        persistPractice(workspace, "fresh-script", "Fresh script", "scan()");
        persistPractice(workspace, "naming", "Naming", null);
        AgentJob older = persistJob(workspace, commentQuality);
        record(older, MONDAY, skippedNeedingAReranker("comment-quality"));
        AgentJob newer = persistJob(workspace, commentQuality);
        record(newer, MONDAY.plus(1, ChronoUnit.DAYS), needingADecisionModel("comment-quality"));
        // A run that did not finish, or that failed before the script declared its models, reported no
        // models, so it never stands for the script's needs.
        AgentJob unfinished = persistJob(workspace, commentQuality);
        record(unfinished, MONDAY.plus(2, ChronoUnit.DAYS));
        AgentJob failedEarly = persistJob(workspace, commentQuality);
        record(failedEarly, MONDAY.plus(3, ChronoUnit.DAYS), """
                {"slug":"comment-quality","status":"timeout","leads":0}""");

        needs().jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].practiceSlug")
                .isEqualTo("comment-quality")
                .jsonPath("$[0].practiceName")
                .isEqualTo("Comment quality")
                .jsonPath("$[0].scriptChanged")
                .isEqualTo(false)
                .jsonPath("$[0].asOf.jobId")
                .isEqualTo(newer.getId().toString())
                .jsonPath("$[0].needs.length()")
                .isEqualTo(1)
                .jsonPath("$[0].needs[0].purpose")
                .isEqualTo(AgentPurpose.PRACTICE_DECISION.name())
                .jsonPath("$[0].needs[0].need")
                .isEqualTo("REQUIRED")
                .jsonPath("$[0].needs[0].unmetTiers")
                .isEqualTo(List.of("IN_HOUSE", "CLOUD", "UNDECLARED"))
                .jsonPath("$[1].practiceSlug")
                .isEqualTo("fresh-script")
                .jsonPath("$[1].asOf")
                .doesNotExist()
                .jsonPath("$[1].scriptChanged")
                .isEqualTo(false)
                .jsonPath("$[1].needs.length()")
                .isEqualTo(0);
    }

    /** "Uses no model" is a claim, so only a run that reported the models can make it. */
    @Test
    @WithAdminUser
    void shouldNotClaimTheScriptUsesNoModelWhenNoRunReportedItsModels() {
        Practice failing = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        Practice scanning = persistPractice(workspace, "naming", "Naming", "scan()");
        AgentJob job = persistJob(workspace, failing, scanning);
        record(job, MONDAY, """
                {"slug":"comment-quality","status":"error","leads":0}""", """
                {"slug":"naming","status":"ok","leads":1,"models":[]}""");

        needs().jsonPath("$[0].practiceSlug")
                .isEqualTo("comment-quality")
                .jsonPath("$[0].asOf")
                .doesNotExist()
                .jsonPath("$[0].needs.length()")
                .isEqualTo(0)
                .jsonPath("$[1].practiceSlug")
                .isEqualTo("naming")
                .jsonPath("$[1].asOf.jobId")
                .isEqualTo(job.getId().toString())
                .jsonPath("$[1].needs.length()")
                .isEqualTo(0);
    }

    @Test
    @WithAdminUser
    void shouldShowNoNeedsWhenTheScriptChangedSinceTheNewestRun() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        record(persistJob(workspace, practice), MONDAY, needingADecisionModel("comment-quality"));

        practice.setPrecomputeScript("decide({ reranking: true })");
        appendRevision(practice);

        needs().jsonPath("$[0].practiceSlug")
                .isEqualTo("comment-quality")
                .jsonPath("$[0].scriptChanged")
                .isEqualTo(true)
                .jsonPath("$[0].asOf")
                .doesNotExist()
                .jsonPath("$[0].needs.length()")
                .isEqualTo(0);
    }

    @Test
    @WithAdminUser
    void shouldNeverAnswerFromAnotherWorkspacesRunWhenAPracticeSharesItsSlug() {
        persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        Practice theirs = persistPractice(otherWorkspace, "comment-quality", "Comment quality", "decide()");
        record(persistJob(otherWorkspace, theirs), MONDAY, needingADecisionModel("comment-quality"));

        needs().jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].scriptChanged")
                .isEqualTo(false)
                .jsonPath("$[0].asOf")
                .doesNotExist()
                .jsonPath("$[0].needs.length()")
                .isEqualTo(0);
    }

    @Test
    @WithUser
    void shouldRefuseTheNeedsWhenAMemberAsks() {
        webTestClient
                .get()
                .uri(NEEDS_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    /** A workspace admin, not an instance admin: an instance admin may read every workspace. */
    @Test
    @WithMentorUser
    void shouldRefuseTheNeedsWhenTheAdminOfAnotherWorkspaceAsks() {
        ensureWorkspaceMembership(workspace, persistUser("mentor"), WorkspaceMembership.WorkspaceRole.ADMIN);
        webTestClient
                .get()
                .uri(NEEDS_URI, otherWorkspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldNotFindTheNeedsWhenTheWorkspaceDoesNotExist() {
        webTestClient
                .get()
                .uri(NEEDS_URI, "no-such-workspace")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldKeepOnlyTheFirstLineWhenAFailedScriptsErrorHasSeveral() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(workspace, practice);

        record(job, MONDAY, """
                {"slug":"comment-quality","status":"error","leads":0,"models":[],
                 "error":"TypeError: answer is undefined\\n    at decide (comment-quality.ts:4:9)"}""");

        assertThat(Objects.requireNonNull(runsOf(workspace, job).get("comment-quality"))
                        .getError())
                .isEqualTo("TypeError: answer is undefined");
    }

    @Test
    @WithAdminUser
    void shouldListTheCurrentAttemptsScriptsAndCallsWhenAnEarlierAttemptRecordedRuns() {
        Practice commentQuality = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        Practice errorHandling = persistPractice(workspace, "error-handling", "Error handling", "scan()");
        AgentJob job = persistJob(workspace, commentQuality, errorHandling);
        job.setConfigSnapshot(snapshotWithDecisionModel(DataHandlingTier.CLOUD));
        job = jobRepository.save(job);
        record(job, MONDAY, """
                {"slug":"comment-quality","status":"timeout","leads":0}""", """
                {"slug":"error-handling","status":"ok","leads":1,"models":[]}""");
        countCalls(job, 0, "comment-quality", ModelKind.DECISION, DataHandlingTier.CLOUD, 9, 900, 90);
        // A model that only the earlier attempt called is not a model of the attempt shown.
        countCalls(job, 0, "comment-quality", ModelKind.RERANKING, DataHandlingTier.CLOUD, 3, 30, 0);

        job.setRetryCount(1);
        job = jobRepository.save(job);
        record(job, MONDAY.plus(1, ChronoUnit.HOURS), """
                {"slug":"error-handling","status":"error","leads":0,"models":[],
                 "error":"TypeError: answer is undefined"}""", """
                {"slug":"comment-quality","status":"ok","leads":3,"durationMs":4200,
                 "models":[{"slot":"decision","need":"required","bound":true,"notRated":{"deadline":2}},
                           {"slot":"chat","need":"optional","bound":true,"notRated":{}}]}""");
        countCalls(job, 1, "comment-quality", ModelKind.DECISION, DataHandlingTier.CLOUD, 4, 120, 8);
        // Chat calls of a script are the review model's calls, which the review counts.
        countCalls(job, 1, "comment-quality", ModelKind.CHAT, DataHandlingTier.CLOUD, 2, 50, 5);

        reviewPrecompute(workspace, job.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].practiceSlug")
                .isEqualTo("comment-quality")
                .jsonPath("$[0].practiceName")
                .isEqualTo("Comment quality")
                .jsonPath("$[0].run.status")
                .isEqualTo("OK")
                .jsonPath("$[0].run.leads")
                .isEqualTo(3)
                .jsonPath("$[0].run.models[0].notRated[0].count")
                .isEqualTo(2)
                .jsonPath("$[0].models.length()")
                .isEqualTo(1)
                .jsonPath("$[0].models[0].purpose")
                .isEqualTo("PRACTICE_DECISION")
                .jsonPath("$[0].models[0].tier")
                .isEqualTo("CLOUD")
                .jsonPath("$[0].models[0].calls")
                .isEqualTo(4)
                .jsonPath("$[0].models[0].inputTokens")
                .isEqualTo(120)
                .jsonPath("$[0].models[0].outputTokens")
                .isEqualTo(8)
                .jsonPath("$[0].error")
                .doesNotExist()
                .jsonPath("$[0].durationMs")
                .isEqualTo(4200)
                .jsonPath("$[1].durationMs")
                .doesNotExist()
                .jsonPath("$[1].practiceSlug")
                .isEqualTo("error-handling")
                .jsonPath("$[1].run.status")
                .isEqualTo("FAILED")
                .jsonPath("$[1].models.length()")
                .isEqualTo(0)
                .jsonPath("$[1].error")
                .isEqualTo("TypeError: answer is undefined");
    }

    /**
     * A running attempt has recorded no runs yet. The runs of the attempt before it did not produce the review's
     * result, so the review shows none until the running attempt ends.
     */
    @Test
    @WithAdminUser
    void shouldListNoScriptsWhenTheCurrentAttemptHasNotEnded() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(workspace, practice);
        job.setConfigSnapshot(snapshotWithDecisionModel(DataHandlingTier.CLOUD));
        job = jobRepository.save(job);
        record(job, MONDAY, needingADecisionModel("comment-quality"));
        countCalls(job, 0, "comment-quality", ModelKind.DECISION, DataHandlingTier.CLOUD, 9, 900, 90);

        job.setRetryCount(1);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setConfigSnapshot(snapshotWithDecisionModel(DataHandlingTier.IN_HOUSE));
        job = jobRepository.save(job);

        reviewPrecompute(workspace, job.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);

        record(job, MONDAY.plus(1, ChronoUnit.HOURS), needingADecisionModel("comment-quality"));

        reviewPrecompute(workspace, job.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].models[0].purpose")
                .isEqualTo("PRACTICE_DECISION")
                .jsonPath("$[0].models[0].tier")
                .isEqualTo("IN_HOUSE")
                .jsonPath("$[0].models[0].calls")
                .isEqualTo(0);
    }

    /** A script that did not finish declared no models, so only the proxy's rows know which model served it. */
    @Test
    @WithAdminUser
    void shouldShowTheTierOfTheModelThatServedTheCallsWhenTheScriptDidNotFinish() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(workspace, practice);
        job.setConfigSnapshot(snapshotWithDecisionModel(DataHandlingTier.CLOUD));
        job = jobRepository.save(job);
        countCalls(job, 0, "comment-quality", ModelKind.DECISION, DataHandlingTier.CLOUD, 9, 900, 90);
        record(job, MONDAY);

        reviewPrecompute(workspace, job.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].run.status")
                .isEqualTo("NOT_FINISHED")
                .jsonPath("$[0].models.length()")
                .isEqualTo(1)
                .jsonPath("$[0].models[0].tier")
                .isEqualTo("CLOUD")
                .jsonPath("$[0].models[0].calls")
                .isEqualTo(9);
    }

    /** The runs are written in the attempt's terminal transaction, which a value PostgreSQL refuses would fail. */
    @Test
    void shouldStoreTheErrorOfAFailedScriptWhenItHoldsANul() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(workspace, practice);

        record(job, MONDAY, """
                {"slug":"comment-quality","status":"error","leads":0,"models":[],"error":"a\\u0000b"}""");

        assertThat(Objects.requireNonNull(runsOf(workspace, job).get("comment-quality"))
                        .getError())
                .isEqualTo("a b");
    }

    @Test
    @WithAdminUser
    void shouldListNoScriptsWhenNoneRanInTheReview() {
        AgentJob job = persistJob(workspace);

        reviewPrecompute(workspace, job.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    @WithUser
    void shouldRefuseTheScriptsOfAReviewWhenAMemberAsks() {
        Practice practice = persistPractice(workspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(workspace, practice);
        record(job, MONDAY, needingADecisionModel("comment-quality"));

        reviewPrecompute(workspace, job.getId()).expectStatus().isForbidden().expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldNotFindTheScriptsWhenTheReviewBelongsToAnotherWorkspace() {
        Practice theirs = persistPractice(otherWorkspace, "comment-quality", "Comment quality", "decide()");
        AgentJob job = persistJob(otherWorkspace, theirs);
        record(job, MONDAY, needingADecisionModel("comment-quality"));

        reviewPrecompute(workspace, job.getId()).expectStatus().isNotFound().expectBody(Void.class);
    }

    private WebTestClient.ResponseSpec reviewPrecompute(Workspace scope, UUID jobId) {
        return webTestClient
                .get()
                .uri(REVIEW_URI, scope.getWorkspaceSlug(), jobId)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    private JsonNode snapshotWithDecisionModel(DataHandlingTier tier) {
        FrozenModel decision = new FrozenModel(
                "openai-decisions", "https://models.example/v1", "decider", null, null, null, null, tier, null, null);
        return snapshot(tier, Map.of(ModelKind.DECISION, decision));
    }

    private JsonNode snapshot(DataHandlingTier tier, Map<ModelKind, FrozenModel> precompute) {
        return new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        "openai-completions",
                        "https://models.example/v1",
                        "reviewer",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        600,
                        false,
                        null,
                        tier,
                        null)
                .withPrecompute(precompute)
                .toJson(objectMapper);
    }

    /** The calls that the proxy counted, with the tier of the model that served them. */
    private void countCalls(
            AgentJob job,
            int attempt,
            String practice,
            ModelKind kind,
            DataHandlingTier tier,
            int calls,
            long input,
            long output) {
        jdbc.update(
                "INSERT INTO agent_job_precompute_usage (job_id, attempt, model_kind, practice_slug, workspace_id, "
                        + "data_handling_tier, calls, input_tokens, output_tokens) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                job.getId(),
                attempt,
                kind.name(),
                practice,
                job.getWorkspace().getId(),
                tier.name(),
                calls,
                input,
                output);
    }

    private WebTestClient.BodyContentSpec needs() {
        return webTestClient
                .get()
                .uri(NEEDS_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private static String needingADecisionModel(String slug) {
        return """
                {"slug":"%s","status":"ok","leads":2,
                 "models":[{"slot":"decision","need":"required","bound":true,"notRated":{}}]}""".formatted(slug);
    }

    private static String skippedNeedingAReranker(String slug) {
        return """
                {"slug":"%s","status":"skipped","leads":0,
                 "models":[{"slot":"reranking","need":"required","bound":false,"notRated":{}}]}""".formatted(slug);
    }

    private void record(AgentJob job, Instant finishedAt, String... entries) {
        byte[] report = ("{\"practices\":[" + String.join(",", entries) + "],\"truncated\":false}").getBytes(UTF_8);
        transactions.executeWithoutResult(status -> {
            AgentJob current = jobRepository.findById(job.getId()).orElseThrow();
            recorder.record(
                    current,
                    report,
                    ConfigSnapshot.fromJson(current.getConfigSnapshot(), objectMapper)
                            .precomputeModels(),
                    finishedAt);
        });
    }

    private Map<String, AgentJobPrecomputeRun> runsOf(Workspace scope, AgentJob job) {
        return runRepository.findByWorkspaceIdAndJobIdIn(scope.getId(), List.of(job.getId())).stream()
                .sorted(Comparator.comparing(AgentJobPrecomputeRun::practiceSlug))
                .collect(Collectors.toMap(AgentJobPrecomputeRun::practiceSlug, run -> run));
    }

    private double scriptsCounted(String status) {
        return meterRegistry
                .counter("agent.review.precompute.scripts", "status", status)
                .count();
    }

    private Practice persistPractice(Workspace scope, String slug, String name, @Nullable String script) {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(scope);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setCriteria("Review the change");
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice.setPrecomputeScript(script);
        return appendRevision(practiceRepository.save(practice));
    }

    private Practice appendRevision(Practice practice) {
        int number = revisionRepository
                .findFirstByPracticeIdOrderByRevisionNumberDesc(practice.getId())
                .map(latest -> latest.getRevisionNumber() + 1)
                .orElse(1);
        PracticeRevision revision = revisionRepository.save(new PracticeRevision(practice, number));
        practice.setCurrentRevision(revision);
        return practiceRepository.save(practice);
    }

    /** A review that admitted these practices at their current revisions, as its evidence snapshot records it. */
    private AgentJob persistJob(Workspace scope, Practice... admitted) {
        AgentJob job = new AgentJob();
        job.setWorkspace(scope);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(snapshot(DataHandlingTier.CLOUD, Map.of()));
        var snapshot = objectMapper.createObjectNode();
        var practices = snapshot.putArray("practices");
        for (Practice practice : admitted) {
            practices
                    .addObject()
                    .put("slug", practice.getSlug())
                    .put(
                            "revisionId",
                            Objects.requireNonNull(practice.getCurrentRevision())
                                    .getId());
        }
        job.setEvidenceSnapshot(snapshot);
        return jobRepository.save(job);
    }
}
