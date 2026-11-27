package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.AgentWorkspacePurgeAdapter;
import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.agent.context.AttemptCopyReceipts;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermitsTestFixtures;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPools;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.ModelCallFailure;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.ReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ReviewJobPolicyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LlmUsageEventRepository usageEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private AgentRuns agentRuns;

    @Autowired
    private ReviewRunKind reviewRuns;

    @Autowired
    private AgentWorkspacePurgeAdapter workspacePurge;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceModelAssignmentRepository bindings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelPriceRepository prices;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private LlmAdmissionService admission;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ReviewMemberAiPolicy memberAiPolicy;

    @Autowired
    private ReviewJobAssignments frozenAssignments;

    private final ReviewMemberAiPolicy policy = mock(ReviewMemberAiPolicy.class);
    private Workspace workspace;
    private final ReviewMethodVerifier methods = mock(ReviewMethodVerifier.class);

    /** A pool with no one waiting. */
    private static LlmCallPools idleCalls() {
        return LlmCallPermitsTestFixtures.idle();
    }

    @BeforeEach
    void setUp() {
        // The claim offers the next run of every workspace, so no run of another test may wait.
        databaseTestUtils.cleanDatabase();
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("tier-" + UUID.randomUUID()));
        doReturn(true).when(policy).permitsReview(any());
        when(policy.binding(any())).thenAnswer(invocation -> {
            ReviewJob selected = invocation.getArgument(0);
            var snapshot = ConfigSnapshot.fromJson(selected.getConfigSnapshot(), mapper);
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                    selected.getWorkspaceId(),
                    ModelPurpose.PRACTICE_REVIEW,
                    Objects.requireNonNull(snapshot.model().dataHandlingTier()));
        });
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldWithholdWithoutAModelWhenACompositionRunIsClaimedPastTheRetryLimit() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var started = claimLeased(job).orElseThrow();
        jdbc.update(
                "UPDATE review_job SET admission_attempt=0, admission_digest=? WHERE id=?",
                "a".repeat(64),
                job.getId());
        var composing = Objects.requireNonNull(
                        transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), started.fence())))
                .orElseThrow();
        jdbc.update("UPDATE agent_run SET attempt=? WHERE id=?", runs().reviewRetries() + 1, composing.id());

        var claimed = claimLeased(jobs.findById(job.getId()).orElseThrow()).orElseThrow();

        assertThat(claimed.withholdOnly()).isEqualTo(CompositionStop.RETRIES_EXHAUSTED);
    }

    @Test
    void shouldStartTheParentInTheTransactionThatLeasesTheJob() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        assertThat(parentStatus(job)).isEqualTo("QUEUED");

        assertThat(claimLeased(job)).isPresent();

        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(RuntimeJobTestData.runState(jdbc, claimed)).isEqualTo("LEASED");
        assertThat(parentStatus(job)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                        "SELECT checks->>'startedAt' FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        job.getWorkspaceId(),
                        job.getReviewId()))
                .isNotNull();
    }

    @Test
    void shouldCancelTheJobWhenItsParentWasCancelledBeforeTheClaim() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // The review's own cancellation never reached this job, as after a restart between its commit and listener.
        jdbc.update(
                "UPDATE review SET status='CANCELLED',completed_at=clock_timestamp(),stop_reason='CANCELLED' WHERE workspace_id=? AND id=?",
                job.getWorkspaceId(),
                job.getReviewId());

        assertThat(claimLeased(job)).isEmpty();

        var cancelled = jobs.findById(job.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
        assertThat(cancelled.getStartedAt()).isNull();
        assertThat(RuntimeJobTestData.runState(jdbc, cancelled)).isEqualTo("ENDED");
        assertThat(parentStatus(job)).isEqualTo("CANCELLED");
        assertThat(claimLeased(job)).isEmpty();
    }

    @Test
    void shouldSkipAClaimWhileCancellationHoldsItsParentLock() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var claims = claimService(jobs);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            transactions.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM review WHERE workspace_id=? AND id=? FOR UPDATE",
                                String.class,
                                job.getWorkspaceId(),
                                job.getReviewId()))
                        .isEqualTo("QUEUED");
                assertThat(CompletableFuture.supplyAsync(() -> claimLeased(job, claims), requests)
                                .orTimeout(10, TimeUnit.SECONDS)
                                .join())
                        .isEmpty();
            });
        }
        assertUnclaimed(job);
        assertThat(parentStatus(job)).isEqualTo("QUEUED");
    }

    /** Leases the job's run as its sandbox's pull does, and starts the job in the same transaction. */
    private Optional<ReviewJobClaimService.ClaimResult> claimLeased(ReviewJob job) {
        return claimLeased(job, claimService(jobs));
    }

    private Optional<ReviewJobClaimService.ClaimResult> claimLeased(ReviewJob job, ReviewJobClaimService claims) {
        UUID run = jobs.findById(job.getId()).orElseThrow().getRunId();
        String subject = Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id=?", String.class, run));
        var kind = AgentRunKind.valueOf(Objects.requireNonNull(
                jdbc.queryForObject("SELECT kind FROM agent_run WHERE id=?", String.class, run)));
        return Objects.requireNonNull(transactions.execute(status -> agentRuns
                .claimSubject(job.getWorkspaceId(), subject, Set.of(kind))
                .flatMap(lease -> claims.claimLeased(lease.fence()))));
    }

    private int runAttempt(ReviewJob job) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT attempt FROM agent_run WHERE id=?", Integer.class, job.getRunId()));
    }

    private String runKind(ReviewJob job) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT kind FROM agent_run WHERE id=?",
                String.class,
                jobs.findById(job.getId()).orElseThrow().getRunId()));
    }

    @Test
    void shouldFailAnAttemptWhosePreparationCannotSucceedWithOnlyItsBoundedDiagnosis() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var failure = new JobPreparationException(
                JobPreparationException.Reason.REVIEW_RANGE_UNAVAILABLE, "token=private path=/private/repository");

        results(new SimpleMeterRegistry())
                .unprepared(
                        jobs.findById(job.getId()).orElseThrow(),
                        fence,
                        PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure),
                        failure);

        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isNotBlank().doesNotContain("private");
        var diagnosis = Objects.requireNonNull(failed.getCapture()).path("preparationFailure");
        assertThat(diagnosis.path("stage").asString()).isEqualTo("INPUTS");
        assertThat(diagnosis.path("reason").asString()).isEqualTo("REVIEW_RANGE_UNAVAILABLE");
        assertThat(diagnosis.toString()).doesNotContain("private");
        assertThat(RuntimeJobTestData.runState(jdbc, failed)).isEqualTo("ENDED");
    }

    @Test
    void shouldRequeueAnAttemptWhoseSourceIsNotReadyYetAndSpendARetry() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        var failure = new ReviewSourceNotReadyException("the pushed commit is not synced yet");

        results(metrics)
                .unprepared(
                        jobs.findById(job.getId()).orElseThrow(),
                        fence,
                        PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure),
                        failure);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(RuntimeJobTestData.runState(jdbc, waiting)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT attempt FROM agent_run WHERE id=?", Integer.class, job.getRunId()))
                .isEqualTo(1);
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "preparation")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldEndAnAttemptWhoseWorkspaceNoAttemptCanPrepareWithoutSpendingARetry() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        var receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(20);
        jdbc.update(
                "UPDATE review_job SET execution_started_at = ? WHERE id = ? AND workspace_id = ?",
                Timestamp.from(receivedAt.minusSeconds(10)),
                job.getId(),
                job.getWorkspaceId());

        results(metrics)
                .measured(
                        job.getId(),
                        jobs.findById(job.getId()).orElseThrow(),
                        fence,
                        0,
                        new SandboxResult(SandboxLayout.EXIT_WORKSPACE_UNPREPARED, Map.of(), "", false, Duration.ZERO),
                        receivedAt);

        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus())
                .as("another attempt would meet the same cause, so none runs")
                .isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getExitCode()).isEqualTo(SandboxLayout.EXIT_WORKSPACE_UNPREPARED);
        assertThat(failed.spentAttempts()).as("it spends no retry").isZero();
        assertThat(RuntimeJobTestData.runState(jdbc, failed)).isEqualTo("ENDED");
        assertThat(metrics.find(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        var execution = metrics.get(AgentMetrics.AGENT_JOB_DURATION)
                .tag("phase", "execution")
                .timer();
        assertThat(execution.count()).isEqualTo(1);
        assertThat(execution.totalTime(TimeUnit.SECONDS)).isEqualTo(10);
    }

    @Test
    void shouldRequeueAnAttemptThatNoModelCallAnsweredWithoutSpendingARetry() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        var unanswered = results(metrics, new ModelCallFailure("upstream_stalled", null));

        assertThat(measure(unanswered, job, fence, SandboxLayout.EXIT_PROVIDER_UNREACHABLE))
                .isNull();

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts()).as("an outage spends no retry").isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT available_at > clock_timestamp() + interval '10 seconds' FROM agent_run WHERE id=?",
                        Boolean.class,
                        job.getRunId()))
                .as("the next attempt waits the backoff of its number")
                .isTrue();
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldCountARateLimitedAttemptApartAndSpendNoRetry() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        var limited = results(metrics, new ModelCallFailure("upstream_status", 429));

        measure(limited, job, fence, SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts()).isZero();
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "rate_limited")
                        .count())
                .isEqualTo(1);
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isZero();
    }

    @Test
    void shouldEndAReviewThatNoModelCallAnsweredPastItsOutageWindowAsProviderUnavailable() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        jdbc.update(
                "UPDATE review_job SET (submitted_at, admitted_at) = (SELECT t, t FROM (SELECT clock_timestamp()"
                        + " - make_interval(secs => ?) AS t) since) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                job.getId());
        var metrics = new SimpleMeterRegistry();
        var unanswered = results(metrics, new ModelCallFailure("upstream_stalled", null));

        measure(unanswered, job, fence, SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        var ended = jobs.findById(job.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(Objects.requireNonNull(ended.getOutput()).path("outcome").asString())
                .isEqualTo(RefusalOutcome.PROVIDER_UNAVAILABLE.name());
        assertThat(ended.getAdmissionDigest())
                .as("it admitted nothing about the work")
                .isNull();
        assertThat(RuntimeJobTestData.runState(jdbc, ended)).isEqualTo("ENDED");
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isZero();
    }

    @Test
    void shouldCountTheOutageWindowFromTheReviewsAdmissionNotFromItsSubmission() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        // The review waited for its pool longer than the window, but its pool admitted it just now.
        jdbc.update(
                "UPDATE review_job SET submitted_at=clock_timestamp() - make_interval(secs => ?) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                job.getId());
        var unanswered = results(new SimpleMeterRegistry(), new ModelCallFailure("upstream_stalled", null));

        measure(unanswered, job, fence, SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts()).as("an outage spends no retry").isEqualTo(job.spentAttempts());
    }

    @Test
    void shouldKeepStagedPracticesThroughAnOutageAndWithholdTheRestPastItsWindow() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var admission = mock(ResultAdmissionService.class);
        when(admission.hasStaged(any())).thenReturn(true);
        when(admission.sealIfComplete(any())).thenReturn(false);
        var stalled = new ModelCallFailure("upstream_stalled", null);

        measure(
                results(new SimpleMeterRegistry(), stalled, admission),
                job,
                fence,
                SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus())
                .as("inside the window it waits, keeping what it staged")
                .isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts()).isZero();
        verify(admission, never()).finishInterrupted(any(), any());

        jdbc.update("UPDATE agent_run SET available_at=clock_timestamp() WHERE id=?", waiting.getRunId());
        var again = claimLeased(waiting).orElseThrow().fence();
        jdbc.update(
                "UPDATE review_job SET (submitted_at, admitted_at) = (SELECT t, t FROM (SELECT clock_timestamp()"
                        + " - make_interval(secs => ?) AS t) since) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                job.getId());
        measure(
                results(new SimpleMeterRegistry(), stalled, admission),
                waiting,
                again,
                SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        verify(admission).finishInterrupted(any(), eq(ReviewStopReason.PROVIDER_UNAVAILABLE));
    }

    @Test
    void shouldSpendARetryWhenTheProviderAnsweredEveryCallWithAnError() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        // A revoked key: the sandbox reports no reply, but the provider answered.
        var refused = results(metrics, new ModelCallFailure("upstream_status", 401));

        measure(refused, job, fence, SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts())
                .as("an answered error spends a retry")
                .isEqualTo(1);
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldSpendARetryWhenTheSandboxCouldNotReachTheServer() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        var unreached = results(metrics, new ModelCallFailure("upstream_stalled", null));

        measure(unreached, job, fence, SandboxLayout.EXIT_SERVER_UNREACHABLE);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts()).isEqualTo(1);
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "server_unreachable")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldContinueACompositionThatTheProviderLeftUnansweredOnAFreshRunWithoutSpendingARetry() {
        var claim = composing();
        var deadline = runDeadline(claim.fence().runId());
        var metrics = new SimpleMeterRegistry();

        assertThat(handOn(claim, new ModelCallFailure("WebClientRequestException", null), metrics))
                .isTrue();

        var waiting = jobs.findById(claim.job().getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.getRunId())
                .as("a fresh run continues it")
                .isNotEqualTo(claim.fence().runId());
        assertThat(waiting.spentAttempts()).isEqualTo(claim.job().spentAttempts());
        assertThat(runDeadline(waiting.getRunId()))
                .as("the fresh run has its own work deadline")
                .isAfter(deadline);
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldContinueARateLimitedCompositionWithoutSpendingARetryAndCountItApart() {
        var claim = composing();
        var metrics = new SimpleMeterRegistry();

        assertThat(handOn(claim, new ModelCallFailure("upstream_status", 429), metrics))
                .isTrue();

        var waiting = jobs.findById(claim.job().getId()).orElseThrow();
        assertThat(waiting.getRunId()).isNotEqualTo(claim.fence().runId());
        assertThat(waiting.spentAttempts()).isEqualTo(claim.job().spentAttempts());
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "rate_limited")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldSpendARetryWhenTheProviderAnsweredTheCompositionsFailedCall() {
        var claim = composing();
        var metrics = new SimpleMeterRegistry();

        assertThat(handOn(claim, new ModelCallFailure("upstream_status", 400), metrics))
                .isTrue();

        var retry = jobs.findById(claim.job().getId()).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(retry.getRunId()).isNotEqualTo(claim.fence().runId());
        assertThat(retry.spentAttempts()).isEqualTo(claim.job().spentAttempts() + 1);
        assertThat(metrics.find(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED).counters().stream()
                        .mapToDouble(Counter::count)
                        .sum())
                .isZero();
    }

    @Test
    void shouldSpendARetryWhenACompositionHandsOnForAnotherCauseAfterATransientOutage() {
        var claim = composing();
        var metrics = new SimpleMeterRegistry();
        // A 503 that the loop retried, then a hand-on for its deadline: the outage did not stop the composition.
        assertThat(handOn(claim, new ModelCallFailure("upstream_status", 503), metrics, false))
                .isTrue();

        var waiting = jobs.findById(claim.job().getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.getRunId())
                .as("a spent retry continues on a fresh run")
                .isNotEqualTo(claim.fence().runId());
        assertThat(waiting.spentAttempts()).isEqualTo(claim.job().spentAttempts() + 1);
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isZero();
    }

    @Test
    void shouldEndACompositionThatTheProviderLeftUnansweredPastItsOutageWindowAsProviderUnavailable() {
        var claim = composing();
        jdbc.update(
                "UPDATE review_job SET (submitted_at, admitted_at) = (SELECT t, t FROM (SELECT clock_timestamp()"
                        + " - make_interval(secs => ?) AS t) since) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                claim.job().getId());
        var metrics = new SimpleMeterRegistry();

        assertThat(handOn(claim, new ModelCallFailure("upstream_stalled", null), metrics))
                .isTrue();

        var ended = jobs.findById(claim.job().getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(Objects.requireNonNull(ended.getOutput()).path("outcome").asString())
                .isEqualTo(RefusalOutcome.PROVIDER_UNAVAILABLE.name());
        assertThat(ended.getAdmissionDigest()).as("its admitted results stay").isNotNull();
        assertThat(RuntimeJobTestData.runState(jdbc, ended)).isEqualTo("ENDED");
        assertThat(metrics.counter(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED, "cause", "provider_unreachable")
                        .count())
                .isZero();
    }

    /** The claim of the run that composes an admitted review. */
    private ReviewJobClaimService.ClaimResult composing() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var started = claimLeased(job).orElseThrow();
        jdbc.update(
                "UPDATE review_job SET admission_attempt=0, admission_digest=? WHERE id=?",
                "a".repeat(64),
                job.getId());
        Optional<AgentRunRef> composition = Objects.requireNonNull(
                transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), started.fence())));
        assertThat(composition)
                .as("the measured review hands on to its composition")
                .isPresent();
        return claimLeased(jobs.findById(job.getId()).orElseThrow()).orElseThrow();
    }

    /** Ends the composing claim handed on for a provider failure, after its newest failed call was {@code latestFailure}. */
    private boolean handOn(
            ReviewJobClaimService.ClaimResult claim, ModelCallFailure latestFailure, SimpleMeterRegistry metrics) {
        return handOn(claim, latestFailure, metrics, true);
    }

    /** @param providerFailure whether the server closed the attempt for a provider failure, not another passing cause */
    private boolean handOn(
            ReviewJobClaimService.ClaimResult claim,
            ModelCallFailure latestFailure,
            SimpleMeterRegistry metrics,
            boolean providerFailure) {
        var outages = outages(metrics, latestFailure);
        var job = jobs.findById(claim.job().getId()).orElseThrow();
        return Boolean.TRUE.equals(
                transactions.execute(status -> outages.handOnComposition(job, claim.fence(), providerFailure)));
    }

    private Instant runDeadline(UUID runId) {
        return Objects.requireNonNull(
                        jdbc.queryForObject("SELECT deadline FROM agent_run WHERE id=?", Timestamp.class, runId))
                .toInstant();
    }

    private ProviderOutages outages(SimpleMeterRegistry metrics, @Nullable ModelCallFailure latestFailure) {
        var usage = mock(LlmUsageRecorder.class);
        when(usage.latestFailure(anyLong(), any(), anyInt())).thenReturn(latestFailure);
        return new ProviderOutages(reviewRuns, usage, runs(), metrics);
    }

    /** Hands the attempt's result to {@code results} as its sandbox reported it: an exit code and nothing else. */
    private @Nullable ReviewJobStatus measure(
            ReviewAttemptResults results, ReviewJob job, AgentRunFence fence, int exitCode) {
        return results.measured(
                job.getId(),
                jobs.findById(job.getId()).orElseThrow(),
                fence,
                0,
                new SandboxResult(exitCode, Map.of(), "", false, Duration.ZERO),
                Instant.now());
    }

    @Test
    void shouldRefuseAnAttemptWhoseWorkspaceExceedsItsBudgetAndEndItsRun() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var failure = new WorkspaceBudgetExceededException(2048, 1024);

        results(new SimpleMeterRegistry())
                .unprepared(
                        jobs.findById(job.getId()).orElseThrow(),
                        fence,
                        PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure),
                        failure);

        var refused = jobs.findById(job.getId()).orElseThrow();
        assertThat(refused.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        var output = Objects.requireNonNull(refused.getOutput());
        assertThat(output.path("outcome").asString()).isEqualTo(RefusalOutcome.WORKSPACE_REFUSED.name());
        assertThat(output.path("reasonCode").asString()).isEqualTo("WORKSPACE_BUDGET_EXCEEDED");
        assertThat(refused.getCapture() == null
                        || refused.getCapture().path("preparationFailure").isMissingNode())
                .isTrue();
        assertThat(RuntimeJobTestData.runState(jdbc, refused)).isEqualTo("ENDED");
    }

    @Test
    void shouldStartTheJobOfARunThatItsSandboxLeasedInTheSameTransaction() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));

        assertThat(claimLeased(job)).isPresent();

        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(RuntimeJobTestData.runState(jdbc, claimed)).isEqualTo("LEASED");
        assertThat(parentStatus(job)).isEqualTo("RUNNING");
    }

    @Test
    void shouldEndTheRunWithItsJobWhenASandboxLeasesTheRunOfACancelledReview() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        jdbc.update(
                "UPDATE review SET status='CANCELLED',completed_at=clock_timestamp(),stop_reason='CANCELLED' WHERE workspace_id=? AND id=?",
                job.getWorkspaceId(),
                job.getReviewId());

        assertThat(claimLeased(job)).isEmpty();

        var cancelled = jobs.findById(job.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(RuntimeJobTestData.runState(jdbc, cancelled)).isEqualTo("ENDED");
    }

    @Test
    void shouldPutTheRunBackWithoutSpendingAnAttemptWhileALifecycleWriteHoldsItsReview() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject(
                        "SELECT status FROM review WHERE workspace_id=? AND id=? FOR UPDATE",
                        String.class,
                        job.getWorkspaceId(),
                        job.getReviewId());
                assertThat(CompletableFuture.supplyAsync(() -> claimLeased(job), requests)
                                .orTimeout(10, TimeUnit.SECONDS)
                                .join())
                        .isEmpty();
            });
        }
        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(RuntimeJobTestData.runState(jdbc, waiting)).isEqualTo("QUEUED");
        assertThat(waiting.spentAttempts()).isZero();
    }

    @Test
    void shouldHandAMeasuredReviewOnToTheRunThatComposesIt() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var started = claimLeased(job).orElseThrow();
        UUID measuring = started.fence().runId();

        var composing = Objects.requireNonNull(
                        transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), started.fence())))
                .orElseThrow();

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.getRunId()).isEqualTo(composing.id());
        assertThat(jdbc.queryForObject("SELECT kind FROM agent_run WHERE id=?", String.class, composing.id()))
                .isEqualTo("REVIEW_COMPOSE");
        assertThat(jdbc.queryForMap("SELECT state, stop_reason FROM agent_run WHERE id=?", measuring))
                .as("the job keeps its measuring run as history")
                .containsEntry("state", "ENDED")
                .containsEntry("stop_reason", "HANDED_OFF");
        assertThat(waiting.getHandedOffRunId()).isEqualTo(measuring);
        Optional<AgentRunRef> again = Objects.requireNonNull(
                transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), started.fence())));
        assertThat(again).as("a stale claim hands nothing on").isEmpty();
    }

    @Test
    void shouldHandOffOnceAndKeepTheHandedOffRunUntilItsJobIsDeleted() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var started = claimLeased(job).orElseThrow();
        UUID measuring = started.fence().runId();
        var composing = Objects.requireNonNull(
                        transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), started.fence())))
                .orElseThrow();

        assertThatThrownBy(() -> jdbc.update("UPDATE review_job SET handed_off_run_id=NULL WHERE id=?", job.getId()))
                .hasMessageContaining("a review job keeps the run that it handed off");
        var composition = claimLeased(jobs.findById(job.getId()).orElseThrow()).orElseThrow();
        int attempt = jobs.findById(job.getId()).orElseThrow().getAttempt();
        Integer handedOffAgain = transactions.execute(
                status -> jobs.handOffToRun(job.getId(), composition.fence().runId(), attempt, UUID.randomUUID()));
        assertThat(handedOffAgain).as("a composition cannot hand off again").isZero();
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> jdbc.update("DELETE FROM review_job WHERE id=?", job.getId())))
                .rootCause()
                .hasMessageContaining("a handed-off run is deleted with its review job");
        assertThat(jobs.findById(job.getId())).isPresent();

        // Retention returns both runs of the job it deletes, in the same transaction.
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE review_job SET status='CANCELLED', processing_status='DONE',
                      completed_at=now() - interval '400 days'
                    WHERE id=?""", job.getId());
            List<UUID> ended = jobs.deleteUnreferencedTerminalRowsOlderThan(Instant.now(), 500).stream()
                    .flatMap(deleted -> deleted.runs().stream())
                    .toList();
            assertThat(ended).containsExactlyInAnyOrder(composing.id(), measuring);
            agentRuns.retireAll(ended);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE id=?", Integer.class, measuring))
                .isZero();
    }

    @Test
    void shouldDeleteTheHandedOffRunWithTheWorkspace() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var started = claimLeased(job).orElseThrow();
        UUID measuring = started.fence().runId();
        transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), started.fence()));

        transactions.executeWithoutResult(status -> workspacePurge.deleteWorkspaceData(workspace.getId()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE id=?", Integer.class, measuring))
                .isZero();
        assertThat(jobs.findById(job.getId())).isEmpty();
    }

    private AgentCapacityProperties runs() {
        return agentRuns.capacity();
    }

    private Instant leasedUntil(ReviewJob job) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT leased_until FROM agent_run WHERE id=?", Instant.class, job.getRunId()));
    }

    private String parentStatus(ReviewJob job) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM review WHERE workspace_id=? AND id=?",
                String.class,
                job.getWorkspaceId(),
                job.getReviewId()));
    }

    /** The steps after a sandbox's attempt, with the real job and run writes and nothing of its measurement. */
    private ReviewAttemptResults results(SimpleMeterRegistry metrics) {
        return results(metrics, null);
    }

    /** @param latestFailure the newest failed model call of the attempt's claim, as the proxy recorded it */
    private ReviewAttemptResults results(SimpleMeterRegistry metrics, @Nullable ModelCallFailure latestFailure) {
        return results(metrics, latestFailure, mock(ResultAdmissionService.class));
    }

    private ReviewAttemptResults results(
            SimpleMeterRegistry metrics, @Nullable ModelCallFailure latestFailure, ResultAdmissionService admission) {
        var preparation = mock(ReviewAttemptPreparation.class);
        when(preparation.owned(any(), any()))
                .thenAnswer(call -> jobs.findById(call.getArgument(0)).orElseThrow());
        // As the runner's parser reads a result with no files: a failure that names the exit code.
        var practiceAgent = mock(PracticePiAdapter.class);
        lenient()
                .when(practiceAgent.parseResult(any()))
                .thenAnswer(call -> new AgentResult(
                        false,
                        Map.of("exitCode", call.<SandboxResult>getArgument(0).exitCode())));
        var terminal =
                new ReviewJobTerminal(jobs, agentRuns, context.getBeanProvider(ReviewCompositionContinuation.class));
        return new ReviewAttemptResults(
                jobs,
                admission,
                practiceAgent,
                preparation,
                terminal,
                reviewRuns,
                outages(metrics, latestFailure),
                new ReviewAttemptOutputs(
                        jobs,
                        terminal,
                        reviewRuns,
                        mock(PracticeScriptRunRecorder.class),
                        transactions,
                        mapper,
                        metrics,
                        usageEvents),
                transactions,
                mapper,
                metrics,
                new ReviewJobTelemetry(metrics, Tracer.NOOP));
    }

    private ReviewJobClaimService claimService(ReviewJobRepository repository) {
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        return new ReviewJobClaimService(
                repository,
                policy,
                methods,
                assignments,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, idleCalls()),
                agentRuns,
                reviewRuns);
    }

    private void assertUnclaimed(ReviewJob job) {
        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.spentAttempts()).as("a wait spends no retry").isEqualTo(job.spentAttempts());
        assertThat(RuntimeJobTestData.runState(jdbc, queued)).isEqualTo("QUEUED");
        assertThat(queued.getStartedAt()).isNull();
        assertThat(queued.getExecutionStartedAt()).isNull();
    }

    /** A model pool, the binding's model in it, and a claim service that admits to it with a real permit worker. */
    private record PoolScenario(
            long poolId,
            String slug,
            WorkspaceModelAssignment selected,
            ReviewJobClaimService claims,
            LlmCallPools pools) {}

    private PoolScenario poolScenario() {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        var pool = new LlmCallPool();
        pool.setSlug("queued-claim-" + UUID.randomUUID());
        pool.setDisplayName("Queued claims");
        // Eight calls, two of them the interactive reserve: measurements may hold six, one per practice session.
        pool.setMaxConcurrentCalls(8);
        pool = context.getBean(LlmCallPoolRepository.class).saveAndFlush(pool);
        var selectedModel = Objects.requireNonNull(selected.getInstanceModel());
        jdbc.update("UPDATE llm_model SET call_pool_id=? WHERE id=?", pool.getId(), selectedModel.getId());
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var pools = LlmCallPermitsTestFixtures.pools(context);
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignments,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(new SimpleMeterRegistry(), Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, pools),
                agentRuns,
                reviewRuns);
        return new PoolScenario(pool.getId(), pool.getSlug(), selected, claims, pools);
    }

    /** A measurement of another workspace that the pool admitted and that runs since {@code started}. */
    private ReviewJob runningMeasurement(PoolScenario pool, ReviewJob like, Instant started) {
        var busyWorkspace = workspaces.saveAndFlush(TestEntities.activeWorkspace("busy-pool-" + UUID.randomUUID()));
        var running = new ReviewJob();
        RuntimeJobTestData.initialize(running, busyWorkspace.getId());
        ReviewJobFixtures.withStatus(running, ReviewJobStatus.RUNNING);
        running.setConfigSnapshot(like.getConfigSnapshot());
        running.setStartedAt(started);
        running.setCallPoolId(pool.poolId());
        return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, running));
    }

    /** A running measurement of {@code practices} practices, which the pool admitted {@code started}. */
    private ReviewJob runningMeasurement(PoolScenario pool, ReviewJob like, Instant started, int practices) {
        var running = runningMeasurement(pool, like, started);
        withPractices(running, practices);
        return running;
    }

    private void withPractices(ReviewJob job, int practices) {
        var input = new ReviewJobInput(
                1,
                LongStream.rangeClosed(1, practices)
                        .mapToObj(practice -> new ReviewAssignmentId(job.getReviewId(), practice, 1))
                        .toList());
        transactions.executeWithoutResult(status -> {
            var stored = jobs.findById(job.getId()).orElseThrow();
            stored.setInput(input);
            jobs.saveAndFlush(stored);
        });
    }

    /** A batch call of {@code job} that waits for the pool since {@code waited} ago. */
    private void waitingCall(PoolScenario pool, ReviewJob job, Duration waited) {
        waitingCall(pool, job, waited, AgentRunBand.BATCH);
    }

    private void waitingCall(PoolScenario pool, ReviewJob job, Duration waited, AgentRunBand band) {
        var rows = context.getBean(LlmCallPermitRepository.class);
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> rows.enqueue(new LlmCallPermitRepository.Request(
                id, pool.poolId(), "remote-" + id, band, job.getWorkspaceId(), job.getId(), 0, null)));
        jdbc.update(
                "UPDATE llm_call_permit SET acquired_at=clock_timestamp()-make_interval(secs => ?) WHERE id=?",
                waited.toSeconds(),
                id);
    }

    @Test
    void shouldStartAReviewOnlyWhenItsPoolHasRoomForItWhateverWorkspaceFillsIt() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        // Another workspace's review has just started on the pool; no call waits yet.
        var runningReview = runningMeasurement(pool, job, Instant.now());

        assertThat(claimLeased(job, pool.claims())).isEmpty();
        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.getStartedAt())
                .as("a waiting review has started no budget")
                .isNull();
        assertThat(waiting.spentAttempts()).as("a wait spends no retry").isEqualTo(job.spentAttempts());
        assertThat(RuntimeJobTestData.runState(jdbc, waiting)).isEqualTo("QUEUED");
        Instant waited = Instant.now();

        // The other review ends: its run and its job end together, as the holder ends them.
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "UPDATE agent_run SET state='ENDED', ended_at=now(), leased_until=NULL, stop_reason='COMPLETED'"
                            + " WHERE id=?",
                    runningReview.getRunId());
            jdbc.update(
                    "UPDATE review_job SET status='COMPLETED', completed_at=now() WHERE id=?", runningReview.getId());
        });
        // The pool held the run for a later dispatch; that dispatch is now.
        jdbc.update("UPDATE agent_run SET available_at=clock_timestamp() WHERE id=?", job.getRunId());

        assertThat(claimLeased(job, pool.claims())).isPresent();
        var admitted = jobs.findById(job.getId()).orElseThrow();
        assertThat(admitted.getCallPoolId()).isEqualTo(pool.poolId());
        assertThat(admitted.getAdmittedAt()).isNotNull();
        assertThat(admitted.getStartedAt())
                .as("its budget counts from its admission, not from its wait")
                .isAfterOrEqualTo(waited);
    }

    @Test
    void shouldAdmitAnotherReviewOnceTheNewestHasRunAWhileWithoutCongestion() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)));

        assertThat(claimLeased(job, pool.claims())).isPresent();
    }

    @Test
    void shouldHoldAReviewWhosePracticeSessionsDoNotFitThePoolBesideTheRunningMeasurement() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        // Six practice sessions fill the six calls that measurements may hold, though no call waits yet.
        runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)), 6);

        assertThat(claimLeased(job, pool.claims())).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
    }

    @Test
    void shouldReserveTheFreeSlotsForTheOldestReviewEvenWhenItIsWiderThanThePool() {
        var pool = poolScenario();
        var wide = queued(pool.selected());
        var narrow = queued(pool.selected());
        var admission = SandboxReviewClaims.poolAdmission(context, pool.pools());
        jdbc.update("UPDATE llm_call_pool SET learned_limit=6 WHERE id=?", pool.poolId());
        withPractices(wide, 8);
        var running = runningMeasurement(pool, narrow, Instant.now().minus(Duration.ofMinutes(2)), 1);
        var reviews = EnumSet.of(AgentRunKind.REVIEW);
        var pass = admission.pass(reviews);

        assertThat(pass.admits(due(wide))).isTrue();
        assertThat(pass.admits(due(narrow)))
                .as("the oldest gets the pool's place in this pass")
                .isFalse();
        assertThat(claimLeased(wide, pool.claims())).isPresent();
        var admitted = jobs.findById(wide.getId()).orElseThrow();
        assertThat(admitted.getConfigSnapshot()
                        .path(ReviewPoolAdmission.SESSION_LIMIT)
                        .asInt())
                .isEqualTo(3);
        assertThat(admission.pass(reviews).admits(due(narrow))).isFalse();

        // This changes the room after the wide review's claim, before it prepares.
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "UPDATE agent_run SET state='ENDED', ended_at=now(), leased_until=NULL, stop_reason='COMPLETED'"
                            + " WHERE id=?",
                    running.getRunId());
            jdbc.update("UPDATE review_job SET status='COMPLETED', completed_at=now() WHERE id=?", running.getId());
        });
        jdbc.update("UPDATE review_job SET started_at=clock_timestamp()-interval '2 minutes' WHERE id=?", wide.getId());
        assertThat(admission.sessionLimit(pool.poolId(), admitted))
                .as("preparation keeps its reservation")
                .isEqualTo(3);
        assertThat(admission.pass(reviews).admits(due(narrow))).isTrue();
        assertThat(claimLeased(narrow, pool.claims())).isPresent();
    }

    @Test
    void shouldCountTheRoomOfThePoolsLearnedLimitWhenItFellBelowTheConfiguredOne() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)), 2);
        // The provider made the pool learn 3 calls: one is the interactive reserve, so measurements hold two.
        jdbc.update("UPDATE llm_call_pool SET learned_limit=3 WHERE id=?", pool.poolId());

        assertThat(claimLeased(job, pool.claims()))
                .as("two running sessions fill the learned room, though the configured eight calls had room")
                .isEmpty();

        jdbc.update("UPDATE llm_call_pool SET learned_limit=8 WHERE id=?", pool.poolId());
        jdbc.update("UPDATE agent_run SET available_at=clock_timestamp() WHERE id=?", job.getRunId());
        assertThat(claimLeased(job, pool.claims())).isPresent();
    }

    @Test
    void shouldReserveOnlyTheOpenPracticeOfARetryAndAdmitAnotherReviewBesideIt() {
        var pool = poolScenario();
        var retry = queued(pool.selected());
        var another = queued(pool.selected());
        var poolAdmission = SandboxReviewClaims.poolAdmission(context, pool.pools());
        var reviews = EnumSet.of(AgentRunKind.REVIEW);
        jdbc.update("UPDATE llm_call_pool SET learned_limit=4 WHERE id=?", pool.poolId());
        long open = withThirtyTwoPractices(retry);
        var first = claimLeased(retry, pool.claims()).orElseThrow();
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.update(
                            "UPDATE review_item SET state='WITHHELD', reason='EXECUTION_FAILED'"
                                    + " WHERE workspace_id=? AND job_id=? AND practice_id<>?",
                            retry.getWorkspaceId(),
                            retry.getId(),
                            open))
                    .isEqualTo(31);
            assertThat(reviewRuns.requeue(retry.getId(), first.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null))
                    .isTrue();
        });
        runningMeasurement(pool, another, Instant.now().minus(Duration.ofMinutes(2)));

        assertThat(poolAdmission.pass(reviews).admits(due(retry))).isTrue();
        assertThat(claimLeased(retry, pool.claims())).isPresent();
        var runningRetry = jobs.findById(retry.getId()).orElseThrow();
        assertThat(poolAdmission.sessionLimit(pool.poolId(), runningRetry)).isEqualTo(1);

        jdbc.update(
                "UPDATE review_job SET started_at=clock_timestamp()-interval '2 minutes' WHERE id=?", retry.getId());
        assertThat(poolAdmission.pass(reviews).admits(due(another))).isTrue();
        assertThat(claimLeased(another, pool.claims())).isPresent();
    }

    private long withThirtyTwoPractices(ReviewJob job) {
        var versions = context.getBean(PracticeVersionService.class);
        var kind = new ItemKind("scm.pull_request");
        var definition = DefinitionTestFixtures.definition(kind, "Pool fixture", "Pool fixture", null);
        long item = job.getInput().itemId();
        var frozen = LongStream.rangeClosed(1, 32)
                .mapToObj(index -> {
                    var practice = versions.create(new PracticeVersionService.NewPractice(
                            job.getWorkspaceId(), "pool-fixture-" + index, kind, definition, null, 0, null));
                    jdbc.update(
                            "INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)"
                                    + " VALUES (?,?,?,?,'READY')",
                            job.getReviewId(),
                            job.getWorkspaceId(),
                            practice.getId(),
                            practice.getCurrentVersion().getId());
                    jdbc.update(
                            """
                            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version,job_id)
                            SELECT ?,?,?,?,item_version,? FROM review WHERE workspace_id=? AND id=?
                            """,
                            job.getReviewId(),
                            job.getWorkspaceId(),
                            practice.getId(),
                            item,
                            job.getId(),
                            job.getWorkspaceId(),
                            job.getReviewId());
                    return new ReviewAssignmentId(job.getReviewId(), practice.getId(), item);
                })
                .toList();
        transactions.executeWithoutResult(status -> {
            var stored = jobs.findById(job.getId()).orElseThrow();
            stored.setInput(new ReviewJobInput(item, frozen));
            jobs.saveAndFlush(stored);
        });
        return frozen.getLast().practiceId();
    }

    @Test
    void shouldHoldTheRunSoItsSandboxGoesBackWhenTheLimitFallsBetweenDispatchAndClaim() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        withPractices(job, 3);
        runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)), 3);
        var reviews = EnumSet.of(AgentRunKind.REVIEW);
        // At the learned limit of eight, three running sessions and the review's three fit the six calls of
        // measurements, so a dispatch admitted the review and started its sandbox.
        int attempt = runAttempt(job);

        // The provider lowers the pool's limit before the review's sandbox claims its run: five calls, less their
        // reserve of two, hold three, and the three running sessions fill them.
        jdbc.update("UPDATE llm_call_pool SET learned_limit=5 WHERE id=?", pool.poolId());

        assertThat(claimLeased(job, pool.claims())).isEmpty();
        var run = jdbc.queryForMap(
                "SELECT state, hold_reason, available_at > clock_timestamp() AS waits FROM agent_run WHERE id=?",
                job.getRunId());
        assertThat(run)
                .as("held, not deferred: a deferred run stays due, and its ready sandbox would poll and keep its slot")
                .containsEntry("state", "QUEUED")
                .containsEntry("hold_reason", AgentRunHold.MODEL_POOL.name())
                .containsEntry("waits", true);
        assertThat(runAttempt(job)).as("a hold spends no attempt").isEqualTo(attempt);
        assertThat(agentRuns.due(reviews, 0, 100))
                .as("a held run is not due, so no dispatch starts a sandbox for it")
                .noneMatch(due -> due.run().id().equals(job.getRunId()));
    }

    @Test
    void shouldLetNoNarrowerReviewPassAWideReviewThatThePoolHolds() {
        var pool = poolScenario();
        jdbc.update("UPDATE llm_call_pool SET learned_limit=8 WHERE id=?", pool.poolId());
        var wide = queued(pool.selected());
        withPractices(wide, 8);
        var narrow = queued(pool.selected());
        var reviews = EnumSet.of(AgentRunKind.REVIEW);
        var admission = SandboxReviewClaims.poolAdmission(context, pool.pools());
        var running = runningMeasurement(pool, narrow, Instant.now().minus(Duration.ofMinutes(2)), 6);

        var pass = admission.pass(reviews);
        assertThat(pass.admits(due(wide)))
                .as("the running measurement fills the pool")
                .isFalse();
        assertThat(pass.admits(due(narrow)))
                .as("a narrower review behind it waits too, so the wide one keeps its place")
                .isFalse();

        // The running review ends: an idle pool takes the wide review, however wide it is.
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "UPDATE agent_run SET state='ENDED', ended_at=now(), leased_until=NULL, stop_reason='COMPLETED'"
                            + " WHERE id=?",
                    running.getRunId());
            jdbc.update("UPDATE review_job SET status='COMPLETED', completed_at=now() WHERE id=?", running.getId());
        });
        assertThat(admission.pass(reviews).admits(due(wide))).isTrue();
        var claimed = claimLeased(wide, pool.claims()).orElseThrow().job();
        assertThat(claimed.getConfigSnapshot()
                        .path(ReviewPoolAdmission.SESSION_LIMIT)
                        .asInt())
                .isEqualTo(6);
    }

    @Test
    void shouldLeaveRoomForOtherMeasurementsWhenPreparingALegacyClaim() {
        var pool = poolScenario();
        jdbc.update("UPDATE llm_call_pool SET learned_limit=8 WHERE id=?", pool.poolId());
        var queued = queued(pool.selected());
        var started = Instant.now().minus(Duration.ofMinutes(2));
        var legacy = runningMeasurement(pool, queued, started, 8);
        runningMeasurement(pool, queued, started, 2);
        var admitted = jobs.findById(legacy.getId()).orElseThrow();
        var admission = SandboxReviewClaims.poolAdmission(context, pool.pools());

        assertThat(admitted.getConfigSnapshot().has(ReviewPoolAdmission.SESSION_LIMIT))
                .isFalse();
        assertThat(admission.sessionLimit(pool.poolId(), admitted)).isEqualTo(4);
    }

    @Test
    void shouldHoldAReviewWhileAMeasuringCallWaitsLongerThanThePermitAging() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        var running = runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)));
        waitingCall(pool, running, Duration.ofMinutes(2));

        assertThat(claimLeased(job, pool.claims())).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
    }

    @Test
    void shouldNotHoldAReviewForAWaitingCallOfAComposition() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)));
        var composed = queued(pool.selected());
        var measured = claimLeased(composed, pool.claims()).orElseThrow();
        var composition = Objects.requireNonNull(transactions.execute(
                        status -> reviewRuns.continueToCompose(composed.getId(), measured.fence())))
                .orElseThrow();
        assertThat(jobs.findById(composed.getId()).orElseThrow().getRunId())
                .as("the composition took over the job")
                .isEqualTo(composition.id());
        waitingCall(pool, jobs.findById(composed.getId()).orElseThrow(), Duration.ofMinutes(2));

        assertThat(claimLeased(job, pool.claims())).isPresent();
    }

    @Test
    void shouldNotHoldAReviewForAWaitingInteractiveCall() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        var running = runningMeasurement(pool, job, Instant.now().minus(Duration.ofMinutes(2)));
        waitingCall(pool, running, Duration.ofMinutes(2), AgentRunBand.INTERACTIVE);

        assertThat(claimLeased(job, pool.claims())).isPresent();
    }

    @Test
    void shouldAdmitOneReviewForEachPoolInADispatchPassAndCountTheRestAsHeld() {
        var pool = poolScenario();
        var first = queued(pool.selected());
        var second = queued(pool.selected());
        var third = queued(pool.selected());
        var meters = new SimpleMeterRegistry();
        var admission =
                new ReviewPoolAdmission(jobs, pool.pools(), context.getBean(ReviewAssignmentProgress.class), meters);
        var reviews = EnumSet.of(AgentRunKind.REVIEW);

        var pass = admission.pass(reviews);
        assertThat(pass.admits(due(first))).as("an idle pool takes one").isTrue();
        assertThat(pass.admits(due(second)))
                .as("the next waits for the first to show its load")
                .isFalse();
        pass.ended();
        assertThat(held(meters, pool)).isEqualTo(1);

        // The first review's sandbox starts but has not claimed its run yet: it still holds the pool's place.
        startSandbox(first);
        var next = admission.pass(reviews);
        assertThat(next.admits(due(second))).isFalse();
        assertThat(next.admits(due(third))).isFalse();
        next.ended();
        assertThat(held(meters, pool)).isEqualTo(2);

        var empty = admission.pass(reviews);
        empty.ended();
        assertThat(held(meters, pool))
                .as("a pass that holds nothing resets the count")
                .isZero();
    }

    @Test
    void shouldGiveNoPlaceToAStartedReviewThatAHoldPutBackToALaterTime() {
        var pool = poolScenario();
        var budgetHeld = queued(pool.selected());
        var next = queued(pool.selected());
        // Its sandbox started, then its claim found the budget spent and put the run back for an hour.
        startSandbox(budgetHeld);
        jdbc.update(
                "UPDATE agent_run SET available_at=clock_timestamp() + interval '1 hour' WHERE id=?",
                budgetHeld.getRunId());
        var pass = SandboxReviewClaims.poolAdmission(context, pool.pools()).pass(EnumSet.of(AgentRunKind.REVIEW));

        assertThat(pass.admits(due(next)))
                .as("an idle sandbox of a run that is not due holds no place in the pool")
                .isTrue();
    }

    @Test
    void shouldAdmitAReviewWhoseSnapshotNamesNoPoolAndLeaveItToTheClaim() {
        var pool = poolScenario();
        var job = queued(pool.selected());
        // A snapshot of the current version that names no model: it fails past the version check.
        jdbc.update(
                "UPDATE review_job SET config_snapshot=jsonb_build_object('schemaVersion', ?) WHERE id=?",
                ConfigSnapshot.SCHEMA_VERSION,
                job.getId());
        var pass = SandboxReviewClaims.poolAdmission(context, pool.pools()).pass(EnumSet.of(AgentRunKind.REVIEW));

        assertThat(pass.admits(due(job))).isTrue();
    }

    /** Waits until PostgreSQL reports {@code waiters} backends blocked on a lock. */
    private void awaitLockWaiters(int waiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Long.class);
            if (waiting != null && waiting >= waiters) return;
            Thread.sleep(20); // NOPMD - poll interval of a bounded wait
        }
        throw new AssertionError("the claims never waited for the pool's lock");
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("release latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A sandbox that has started for the job's run and has not claimed it yet. */
    private void startSandbox(ReviewJob job) {
        jdbc.update(
                "INSERT INTO agent_sandbox(id,workspace_id,subject_key,kind,claim_name,state,shutdown_at)"
                        + " SELECT ?, r.workspace_id, r.concurrency_key, 'REVIEW', ?, 'REQUESTED', now()+interval '1 hour'"
                        + " FROM agent_run r WHERE r.id=?",
                UUID.randomUUID(),
                "review-" + UUID.randomUUID(),
                job.getRunId());
    }

    private static AgentRuns.DueRun due(ReviewJob job) {
        return new AgentRuns.DueRun(
                new AgentRunRef(Objects.requireNonNull(job.getRunId()), job.getWorkspaceId()),
                AgentRunKind.REVIEW,
                "review:" + job.getId());
    }

    private static double held(SimpleMeterRegistry meters, PoolScenario pool) {
        var gauge = meters.find(AgentMetrics.AGENT_REVIEW_POOL_HELD)
                .tag("pool", pool.slug())
                .gauge();
        return gauge == null ? 0 : gauge.value();
    }

    @Test
    void shouldAdmitOnlyOneOfTwoReviewsThatTwoServersClaimAtOnceOnAnIdlePool() throws Exception {
        var pool = poolScenario();
        var first = queued(pool.selected());
        var second = queued(pool.selected());

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var servers = Executors.newVirtualThreadPerTaskExecutor()) {
            // A third transaction holds the pool's lock, so both claims read the pool only after both reached it.
            var holder = servers.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM llm_call_pool WHERE id=? FOR UPDATE", pool.poolId());
                locked.countDown();
                awaitRelease(release);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            var a = servers.submit(() -> claimLeased(first, pool.claims()).isPresent());
            var b = servers.submit(() -> claimLeased(second, pool.claims()).isPresent());
            try {
                awaitLockWaiters(2);
            } finally {
                release.countDown();
            }
            holder.get(30, TimeUnit.SECONDS);
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void shouldHoldPartlyStagedWorkWhileItsBudgetCanBeRaised() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong()))
                .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.EXHAUSTED));
        var snapshots = mock(ReviewJobAssignments.class);
        when(snapshots.hasStaged(any())).thenReturn(true);
        var metrics = new SimpleMeterRegistry();
        var pools = mock(LlmCallPools.class);
        var modelAdmission = mock(LlmAdmissionService.class);
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                snapshots,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                modelAdmission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, pools),
                agentRuns,
                reviewRuns);
        assertThat(claimLeased(job, claims)).isEmpty();
        var held = jobs.findById(job.getId()).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(held.getHoldReason()).isEqualTo(AgentRunHold.BUDGET.name());
        assertThat(held.spentAttempts()).as("a hold spends no retry").isZero();
        verifyNoInteractions(modelAdmission);
    }

    @Test
    void shouldClaimTheLastStagedHandoffOnlyToWithholdWithoutAdmittingAnyModelCall() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // Every retry is spent: only the handoff that seals what is staged is left.
        jdbc.update("UPDATE agent_run SET attempt=? WHERE id=?", runs().reviewRetries() + 1, job.getRunId());
        var budgets = mock(LlmBudgetService.class);
        var snapshots = mock(ReviewJobAssignments.class);
        when(snapshots.hasStaged(any())).thenReturn(true);
        when(snapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var pools = mock(LlmCallPools.class);
        var modelAdmission = mock(LlmAdmissionService.class);
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                snapshots,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                modelAdmission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, pools),
                agentRuns,
                reviewRuns);
        assertThat(claimLeased(job, claims)).hasValueSatisfying(claimed -> {
            assertThat(claimed.withholdOnly()).isEqualTo(CompositionStop.RETRIES_EXHAUSTED);
            assertThat(claimed.job().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        });
        verifyNoInteractions(budgets, modelAdmission, pools);
    }

    @Test
    void shouldLeaseTheAttemptThatAClaimStartsOnTheDatabaseClock() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignmentSnapshots,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, idleCalls()),
                agentRuns,
                reviewRuns);

        String subject = Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id=?", String.class, job.getRunId()));
        var claim = Objects.requireNonNull(transactions.execute(status -> agentRuns
                        .claimSubject(job.getWorkspaceId(), subject, Set.of(AgentRunKind.REVIEW))
                        .flatMap(lease -> claims.claimLeased(lease.fence()))))
                .orElseThrow();

        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(leasedUntil(claimed))
                .as("the claim leases the attempt for one lease from now")
                .isAfter(Instant.now().plus(runs().leaseTtl()).minusSeconds(60));
        assertThat(claim.fence())
                .as("the claim hands its holder the fence of the lease that the run holds")
                .isEqualTo(RuntimeJobTestData.fence(jdbc, claimed));
    }

    @Test
    void shouldRefuseAQueuedJobWhoseSnapshotTierDiffersFromTheBindingResolvedNow() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(inHouse);
        var routedElsewhere = binding(DataHandlingTier.CLOUD);
        doReturn(Optional.of(routedElsewhere)).when(policy).binding(any(ReviewJob.class));

        assertThat(claimLeased(job)).isEmpty();
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldCancelAQueuedJobWhenItsFrozenModelBecomesUnavailable() {
        var slot = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(slot);
        var model = Objects.requireNonNull(slot.getInstanceModel());
        model.setEnabled(false);
        models.saveAndFlush(model);
        assertThat(claimLeased(job)).isEmpty();
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldKeepTheJobQueuedWhenModelAdmissionFailsUnexpectedly() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var failure = new IllegalStateException("Unexpected programming error");
        doThrow(failure).when(methods).require(any(), any(), any());
        // The lease's transaction rolls back with the failure, so the run and its job wait for the next pull.
        assertThatThrownBy(() -> claimLeased(job)).isSameAs(failure);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.QUEUED, null);
    }

    @Test
    void shouldHandAnAdmittedReviewOnToItsCompositionWhenItsSandboxClaimsItsReviewRun() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // Attempt 0 admitted the results and lost its run before it handed them on.
        RuntimeJobTestData.passAttempt(jdbc, job);
        jdbc.update(
                "UPDATE review_job SET admission_attempt=0, admission_digest=? WHERE id=?",
                "a".repeat(64),
                job.getId());

        int spent = jobs.findById(job.getId()).orElseThrow().spentAttempts();

        assertThat(claimedBySandbox(job, claimService(jobs))).isFalse();

        var handedOn = jobs.findById(job.getId()).orElseThrow();
        assertThat(handedOn.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(handedOn.getExecutionStartedAt()).isNull();
        assertThat(runKind(handedOn)).isEqualTo("REVIEW_COMPOSE");
        assertThat(RuntimeJobTestData.runState(jdbc, handedOn)).isEqualTo("QUEUED");
        assertThat(handedOn.spentAttempts()).as("a hand-on spends no attempt").isEqualTo(spent);

        assertThat(claimLeased(handedOn)).isPresent();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    void shouldNameTheCrashOfTheLastAttemptsReviewProgramsWhenTheNextClaimFindsTheRetriesSpent() {
        var job = crashedAtTheLastRetry();

        assertThat(claimLeased(job)).isEmpty();

        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorMessage())
                .isEqualTo(ReviewRunKind.RETRIES_EXHAUSTED + " " + PROGRAM_CRASH);
    }

    @Test
    void shouldNameTheCrashOfTheLastAttemptsReviewProgramsWhenTheRunEndsWhileQueued() {
        var job = crashedAtTheLastRetry();

        // The reaper ends a queued run past its start window: the handler first, then the run, in one transaction.
        transactions.executeWithoutResult(status -> {
            reviewRuns.ended(job.run(), Integer.MAX_VALUE, AgentRunStopReason.DEADLINE);
            jdbc.update(
                    "UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, deferred_until ="
                            + " NULL, stop_reason = 'DEADLINE', ended_at = clock_timestamp() WHERE id = ?",
                    job.getRunId());
        });

        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(ReviewRunKind.RETRIES_EXHAUSTED + " " + PROGRAM_CRASH);
    }

    private static final String PROGRAM_CRASH = "The last attempt's review sandbox stopped on an unexpected error"
            + " (TypeError) while it prepared the attempt's workspace.";

    /**
     * A job whose last allowed attempt ended as its review sandbox reports a crash of gateway-init: the cause that the
     * driver reads from the program's crash record. A claim that then waited raised the lease epoch without an attempt.
     */
    private ReviewJob crashedAtTheLastRetry() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        UUID runId = Objects.requireNonNull(job.getRunId());
        jdbc.update("UPDATE agent_run SET attempt=? WHERE id=?", runs().reviewRetries(), runId);
        var fence = claimLeased(job).orElseThrow().fence();
        String subject = Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id=?", String.class, runId));
        var run = new SandboxRun(
                new AgentSandboxes.LiveSandbox(
                        UUID.randomUUID(), job.getWorkspaceId(), subject, AgentSandboxKind.REVIEW),
                fence,
                AgentRunKind.REVIEW,
                runs().reviewRetries());

        Boolean held = transactions.execute(status ->
                reviewSandboxRuns(claimService(jobs)).end(run, SandboxRunKind.Outcome.HANDED_ON, "init:TypeError"));
        assertThat(held).isTrue();

        var requeued = jobs.findById(job.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.spentAttempts()).isEqualTo(runs().reviewRetries() + 1);
        // A claim that waits, as one that a lifecycle write blocks: a new lease epoch, no attempt, no spent retry.
        makeAvailable(runId);
        Boolean deferred = transactions.execute(status -> agentRuns
                .claimSubject(job.getWorkspaceId(), subject, Set.of(AgentRunKind.REVIEW))
                .map(lease -> agentRuns.defer(lease.fence(), AgentRuns.Deferral.LOCKED))
                .orElseThrow());
        assertThat(deferred).isTrue();
        makeAvailable(runId);
        assertThat(jobs.findById(job.getId()).orElseThrow().spentAttempts()).isEqualTo(runs().reviewRetries() + 1);
        return requeued;
    }

    private void makeAvailable(UUID runId) {
        jdbc.update(
                "UPDATE agent_run SET available_at = clock_timestamp() - interval '1 second', deferred_until = NULL"
                        + " WHERE id=?",
                runId);
    }

    private ReviewSandboxRuns reviewSandboxRuns(ReviewJobClaimService claims) {
        return new ReviewSandboxRuns(
                claims,
                mock(ReviewAttemptCustody.class),
                jobs,
                mock(SandboxGatewaySessions.class),
                mapper,
                mock(ReviewAttemptResults.class),
                reviewRuns,
                agentRuns,
                mock(AttemptCopyReceipts.class),
                mock(ReviewAttemptMeasurements.class),
                context.getBean(AgentRunAuthority.class));
    }

    /** Leases the job's review run as its review sandbox's pull does; false when the sandbox has nothing to measure. */
    private boolean claimedBySandbox(ReviewJob job, ReviewJobClaimService claims) {
        var sandboxRuns = reviewSandboxRuns(claims);
        String subject = Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id=?", String.class, job.getRunId()));

        return Boolean.TRUE.equals(transactions.execute(status -> agentRuns
                .claimSubject(job.getWorkspaceId(), subject, Set.of(AgentRunKind.REVIEW))
                .map(lease -> sandboxRuns.claimed(
                        new AgentSandboxes.LiveSandbox(
                                UUID.randomUUID(), job.getWorkspaceId(), subject, AgentSandboxKind.REVIEW),
                        lease))
                .orElseThrow()));
    }

    @Test
    void shouldClaimAContinuationOnlyToWithholdWhenTheBudgetIsSpent() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        // Attempt 0 admitted the results and was handed on to compose them.
        RuntimeJobTestData.passAttempt(jdbc, job);
        jdbc.update(
                "UPDATE review_job SET admission_attempt=0, admission_digest=? WHERE id=?",
                "a".repeat(64),
                job.getId());
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong()))
                .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.EXHAUSTED));
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignmentSnapshots,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, idleCalls()),
                agentRuns,
                reviewRuns);

        // The review sandbox's claim hands the review on; the composition run's own claim decides to withhold only.
        assertThat(claimedBySandbox(job, claims)).isFalse();
        assertThat(claimLeased(job, claims))
                .hasValueSatisfying(claim -> assertThat(claim.withholdOnly()).isEqualTo(CompositionStop.BUDGET));

        var claimed = jobs.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(runKind(claimed)).isEqualTo("REVIEW_COMPOSE");
        assertThat(claimed.getExecutionStartedAt()).isNull();
        assertThat(leasedUntil(claimed))
                .as("the claim leases the attempt for one lease from now")
                .isAfter(Instant.now().plus(runs().leaseTtl()).minusSeconds(60));
    }

    @Test
    void shouldFailAJobWhoseFrozenAssignmentIsMissingOnItsFirstClaimAndNotClaimItAgain() {
        // The fixture pins an assignment that no row holds; the real policy reads it inside the claim transaction.
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                memberAiPolicy,
                methods,
                frozenAssignments,
                mapper,
                context.getBean(ReviewStart.class),
                mock(LlmBudgetService.class),
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                SandboxReviewClaims.poolAdmission(context, idleCalls()),
                agentRuns,
                reviewRuns);

        assertThat(claimLeased(job, claims)).isEmpty();

        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isNotBlank();
        assertThat(Objects.requireNonNull(failed.getCapture())
                        .path("preparationFailure")
                        .path("stage")
                        .asString())
                .isEqualTo("INPUTS");
        assertThat(claimLeased(job, claims)).isEmpty();
    }

    @Test
    void shouldServeAMemberWhoAcceptsTheCloudFromAnInHouseBindingWhenItIsTheOnlyOne() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var preferences = mock(MemberAiPreferences.class);
        when(preferences.forDeveloper(workspace.getId(), 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, AiChoice.CLOUD));
        var routing = new MemberAiRoutingAdapter(
                bindings,
                preferences,
                resolver,
                WorkspacePortFixtures.queries(workspaces),
                mock(PersonProcessingSuppression.class));

        assertThat(routing.binding(workspace.getId(), ModelPurpose.PRACTICE_REVIEW, 20L))
                .get()
                .extracting(WorkspaceModelAssignment::getId)
                .isEqualTo(inHouse.getId());
    }

    private WorkspaceModelAssignment binding(DataHandlingTier tier) {
        var binding = new WorkspaceModelAssignment();
        binding.setWorkspace(workspace);
        binding.setPurpose(ModelPurpose.PRACTICE_REVIEW);
        binding.setDataHandlingTier(tier);
        binding.setInstanceModel(model(tier));
        binding.setEnabled(true);
        return bindings.saveAndFlush(binding);
    }

    private LlmModel model(DataHandlingTier tier) {
        String slug = "tier-" + UUID.randomUUID();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = LlmCatalogTestFixtures.model(connection, slug, slug);
        model.setDataHandling(
                switch (tier) {
                    case IN_HOUSE -> DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null);
                    case CLOUD -> DataHandlingFacts.of(LlmDataOperator.PROVIDER, null);
                    case UNDECLARED -> new DataHandlingFacts();
                });
        model = models.save(model);
        var price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setNote("No charge for the declared test model.");
        price.setEffectiveFrom(Instant.now());
        prices.saveAndFlush(price);
        return model;
    }

    private ReviewJob queued(WorkspaceModelAssignment binding) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var currentBinding = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspace.getId(), ModelPurpose.PRACTICE_REVIEW, binding.getDataHandlingTier())
                    .orElseThrow();
            var job = new ReviewJob();
            RuntimeJobTestData.initialize(job, workspace.getId());

            job.setConfigSnapshot(ConfigSnapshot.from(currentBinding, resolver)
                    .withPriceSnapshot(admission.admit(currentBinding).price())
                    .toJson(mapper));
            return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        }));
    }
}
