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
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermitsTestFixtures;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
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
    private static LlmCallPermits idleCalls() {
        var capacity = mock(LlmCallPermits.class);
        when(capacity.poolFor(any())).thenReturn(1L);
        return capacity;
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

        measure(results(metrics), job, fence, SandboxLayout.EXIT_WORKSPACE_UNPREPARED);

        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus())
                .as("another attempt would meet the same cause, so none runs")
                .isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getExitCode()).isEqualTo(SandboxLayout.EXIT_WORKSPACE_UNPREPARED);
        assertThat(failed.spentAttempts()).as("it spends no retry").isZero();
        assertThat(RuntimeJobTestData.runState(jdbc, failed)).isEqualTo("ENDED");
        assertThat(metrics.find(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void shouldRequeueAnAttemptThatNoModelCallAnsweredWithoutSpendingARetry() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        var unanswered = results(metrics, new ConversationTurn.FailedCall("upstream_stalled", null));

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
        var limited = results(metrics, new ConversationTurn.FailedCall("upstream_status", 429));

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
                "UPDATE review_job SET submitted_at = clock_timestamp() - make_interval(secs => ?) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                job.getId());
        var metrics = new SimpleMeterRegistry();
        var unanswered = results(metrics, new ConversationTurn.FailedCall("upstream_stalled", null));

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
    void shouldKeepStagedPracticesThroughAnOutageAndWithholdTheRestPastItsWindow() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var handler = mock(DefaultReviewExecutionHandler.class);
        when(handler.hasStaged(any())).thenReturn(true);
        when(handler.completeStaged(any())).thenReturn(false);
        var stalled = new ConversationTurn.FailedCall("upstream_stalled", null);

        measure(
                results(new SimpleMeterRegistry(), stalled, handler),
                job,
                fence,
                SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        var waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getStatus())
                .as("inside the window it waits, keeping what it staged")
                .isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(waiting.spentAttempts()).isZero();
        verify(handler, never()).finishInterrupted(any(), any());

        jdbc.update("UPDATE agent_run SET available_at=clock_timestamp() WHERE id=?", waiting.getRunId());
        var again = claimLeased(waiting).orElseThrow().fence();
        jdbc.update(
                "UPDATE review_job SET submitted_at = clock_timestamp() - make_interval(secs => ?) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                job.getId());
        measure(
                results(new SimpleMeterRegistry(), stalled, handler),
                waiting,
                again,
                SandboxLayout.EXIT_PROVIDER_UNREACHABLE);

        verify(handler).finishInterrupted(any(), eq(ReviewStopReason.PROVIDER_UNAVAILABLE));
    }

    @Test
    void shouldSpendARetryWhenTheProviderAnsweredEveryCallWithAnError() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var metrics = new SimpleMeterRegistry();
        // A revoked key: the sandbox reports no reply, but the provider answered.
        var refused = results(metrics, new ConversationTurn.FailedCall("upstream_status", 401));

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
        var unreached = results(metrics, new ConversationTurn.FailedCall("upstream_stalled", null));

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

        assertThat(handOn(claim, new ConversationTurn.FailedCall("WebClientRequestException", null), metrics))
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

        assertThat(handOn(claim, new ConversationTurn.FailedCall("upstream_status", 429), metrics))
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

        assertThat(handOn(claim, new ConversationTurn.FailedCall("upstream_status", 400), metrics))
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
        assertThat(handOn(claim, new ConversationTurn.FailedCall("upstream_status", 503), metrics, false))
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
                "UPDATE review_job SET submitted_at = clock_timestamp() - make_interval(secs => ?) WHERE id=?",
                runs().modelOutageWindow().plusMinutes(1).toSeconds(),
                claim.job().getId());
        var metrics = new SimpleMeterRegistry();

        assertThat(handOn(claim, new ConversationTurn.FailedCall("upstream_stalled", null), metrics))
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
            ReviewJobClaimService.ClaimResult claim,
            ConversationTurn.FailedCall latestFailure,
            SimpleMeterRegistry metrics) {
        return handOn(claim, latestFailure, metrics, true);
    }

    /** @param providerFailure whether the server closed the attempt for a provider failure, not another passing cause */
    private boolean handOn(
            ReviewJobClaimService.ClaimResult claim,
            ConversationTurn.FailedCall latestFailure,
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

    private ProviderOutages outages(SimpleMeterRegistry metrics, ConversationTurn.@Nullable FailedCall latestFailure) {
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
                new SandboxResult(exitCode, Map.of(), "", false, Duration.ZERO));
    }

    @Test
    void shouldRefuseAnAttemptWhoseEvidenceIsTooLittleAndEndItsRun() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var fence = claimLeased(job).orElseThrow().fence();
        var failure = new InsufficientEvidenceException(
                "too little evidence", new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));

        results(new SimpleMeterRegistry())
                .unprepared(
                        jobs.findById(job.getId()).orElseThrow(),
                        fence,
                        PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure),
                        failure);

        var refused = jobs.findById(job.getId()).orElseThrow();
        assertThat(refused.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(Objects.requireNonNull(refused.getOutput()).path("outcome").asString())
                .isEqualTo(RefusalOutcome.INSUFFICIENT_EVIDENCE.name());
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
    private ReviewAttemptResults results(
            SimpleMeterRegistry metrics, ConversationTurn.@Nullable FailedCall latestFailure) {
        return results(metrics, latestFailure, mock(DefaultReviewExecutionHandler.class));
    }

    private ReviewAttemptResults results(
            SimpleMeterRegistry metrics,
            ConversationTurn.@Nullable FailedCall latestFailure,
            DefaultReviewExecutionHandler handler) {
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
        return new ReviewAttemptResults(
                jobs,
                handler,
                practiceAgent,
                preparation,
                new ReviewJobTerminal(jobs, agentRuns, context.getBeanProvider(ReviewCompositionContinuation.class)),
                reviewRuns,
                outages(metrics, latestFailure),
                mock(PracticeScriptRunRecorder.class),
                transactions,
                mapper,
                metrics,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                usageEvents);
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
                idleCalls(),
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

    @Test
    void shouldWaitForBackgroundDemandWithoutReservingAProviderSlot() throws Exception {
        var selected = binding(DataHandlingTier.IN_HOUSE);
        var pool = new LlmCallPool();
        pool.setSlug("queued-claim-" + UUID.randomUUID());
        pool.setDisplayName("Queued claims");
        pool.setMaxConcurrentCalls(1);
        pool = context.getBean(LlmCallPoolRepository.class).saveAndFlush(pool);
        var selectedModel = Objects.requireNonNull(selected.getInstanceModel());
        jdbc.update("UPDATE llm_model SET call_pool_id=? WHERE id=?", pool.getId(), selectedModel.getId());
        var job = queued(selected);
        var capacity = LlmCallPermitsTestFixtures.worker(context, "test-worker");
        var busyWorkspace = workspaces.saveAndFlush(TestEntities.activeWorkspace("busy-pool-" + UUID.randomUUID()));
        var runningReview = new ReviewJob();
        RuntimeJobTestData.initialize(runningReview, busyWorkspace.getId());
        runningReview.setStatus(ReviewJobStatus.RUNNING);
        runningReview.setConfigSnapshot(job.getConfigSnapshot());
        runningReview.setStartedAt(Instant.now());
        runningReview = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, runningReview));
        var busyCaller = new LlmCallPermits.Caller(busyWorkspace.getId(), runningReview.getId(), 0, null);
        long poolId = pool.getId();
        var busy = Objects.requireNonNull(capacity.acquire(
                poolId,
                AgentRunBand.BATCH,
                busyCaller,
                () -> false,
                Instant.now().plusSeconds(5)));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var claims = new ReviewJobClaimService(
                jobs,
                policy,
                methods,
                assignments,
                mapper,
                context.getBean(ReviewStart.class),
                budgets,
                admission,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                capacity,
                agentRuns,
                reviewRuns);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var repeated = requests.submit(() -> capacity.acquire(
                    poolId,
                    AgentRunBand.BATCH,
                    busyCaller,
                    () -> false,
                    Instant.now().plusSeconds(10)));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM llm_call_permit WHERE pool_id=? AND state='WAITING'",
                                    Long.class,
                                    poolId))
                            .isEqualTo(1L));
            try {
                assertThat(claimLeased(job, claims)).isEmpty();
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
                assertThat(RuntimeJobTestData.runState(jdbc, job)).isEqualTo("QUEUED");
                busy.close();
                var granted = Objects.requireNonNull(repeated.get(12, TimeUnit.SECONDS));
                assertThat(granted.isRevoked()).isFalse();
                // The wait deferred the run for a later pull; the next pull is now.
                jdbc.update("UPDATE agent_run SET deferred_until=clock_timestamp() WHERE id=?", job.getRunId());
                assertThat(claimLeased(job, claims)).isPresent();
                assertThat(context.getBean(LlmCallPermitRepository.class).countActive(poolId))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM llm_call_permit WHERE source_id=?", Long.class, job.getId()))
                        .isZero();
            } finally {
                busy.close();
                var repeat = repeated.get(12, TimeUnit.SECONDS);
                if (repeat != null) repeat.close();
            }
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
        var permits = mock(LlmCallPermits.class);
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
                permits,
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
        var permits = mock(LlmCallPermits.class);
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
                permits,
                agentRuns,
                reviewRuns);
        assertThat(claimLeased(job, claims)).hasValueSatisfying(claimed -> {
            assertThat(claimed.withholdOnly()).isEqualTo(CompositionStop.RETRIES_EXHAUSTED);
            assertThat(claimed.job().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        });
        verifyNoInteractions(budgets, modelAdmission, permits);
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
                idleCalls(),
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
    void shouldLeaveTheReviewQueuedWhileItsEndpointHasBackgroundWaiters() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var metrics = new SimpleMeterRegistry();
        var capacity = mock(LlmCallPermits.class);
        when(capacity.poolFor(any())).thenReturn(42L);
        when(capacity.hasReviewCallsWaiting(anyLong())).thenReturn(true);
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
                capacity,
                agentRuns,
                reviewRuns);

        assertThat(claimLeased(job, claims)).isEmpty();

        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(queued.spentAttempts()).as("a wait spends no retry").isEqualTo(job.spentAttempts());
        assertThat(RuntimeJobTestData.runState(jdbc, queued))
                .as("the wait requeued the run")
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "SELECT deferred_until > clock_timestamp() FROM agent_run WHERE id = ?",
                        Boolean.class,
                        queued.getRunId()))
                .as("the claim deferred the run, which keeps its place")
                .isTrue();
        assertThat(queued.getExecutionStartedAt()).isNull();
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

        transactions.executeWithoutResult(
                status -> reviewRuns.ended(job.run(), Integer.MAX_VALUE, AgentRunStopReason.ATTEMPTS_EXHAUSTED));

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
        jdbc.update(
                "UPDATE agent_run SET lease_epoch = lease_epoch + 1, available_at = clock_timestamp() - interval '1"
                        + " second' WHERE id=?",
                runId);
        return requeued;
    }

    private ReviewSandboxRuns reviewSandboxRuns(ReviewJobClaimService claims) {
        return new ReviewSandboxRuns(
                claims,
                mock(ReviewAttemptPreparation.class),
                mock(ReviewAttemptCustody.class),
                mock(ReviewJobTerminal.class),
                jobs,
                mock(SandboxWorkspaceManager.class),
                mock(SandboxGatewaySessions.class),
                transactions,
                mapper,
                mock(ReviewAttemptResults.class),
                reviewRuns,
                agentRuns,
                mock(JobEvidenceFiles.class),
                mock(ClusterLock.class));
    }

    /** Leases the job's review run as its review sandbox's pull does; false when the sandbox has nothing to measure. */
    private boolean claimedBySandbox(ReviewJob job, ReviewJobClaimService claims) {
        var sandboxRuns = reviewSandboxRuns(claims);
        String subject = Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id=?", String.class, job.getRunId()));

        return Boolean.TRUE.equals(transactions.execute(status -> agentRuns
                .claimSubject(job.getWorkspaceId(), subject, Set.of(AgentRunKind.REVIEW))
                .map(lease -> sandboxRuns.claimed(new SandboxRun(
                        new AgentSandboxes.LiveSandbox(
                                UUID.randomUUID(), job.getWorkspaceId(), subject, AgentSandboxKind.REVIEW),
                        lease.fence(),
                        lease.kind(),
                        lease.attempt())))
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
                idleCalls(),
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
                idleCalls(),
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
