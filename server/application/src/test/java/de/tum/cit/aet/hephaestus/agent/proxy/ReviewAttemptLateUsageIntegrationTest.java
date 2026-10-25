package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.job.FrozenCharge;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLlmUsageDelta;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRecovery;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UnpricedLedgerRow;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls that return after their attempt ended, settled through the production accumulator and service on real
 * PostgreSQL. Assertions read the rows: the attempt measurement, the ledger, the script rows and the job row.
 */
@Tag("integration")
class ReviewAttemptLateUsageIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRACTICE = "comment-quality";

    /** Instance-funded: $1 per 1M input, $2 per 1M output. */
    private static final FrozenCharge INSTANCE_CHAT = new FrozenCharge(
            "chat-a",
            new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE,
                    UsagePricingState.PRICED,
                    11L,
                    null,
                    new BigDecimal("1.00"),
                    new BigDecimal("2.00"),
                    new BigDecimal("0.50"),
                    new BigDecimal("1.00")));

    /** What the next attempt's claim froze instead: own-provider funded, ten times the rates. */
    private static final FrozenCharge WORKSPACE_CHAT = new FrozenCharge(
            "chat-b",
            new LlmPriceSnapshot(
                    UsageFundingSource.WORKSPACE,
                    UsagePricingState.PRICED,
                    null,
                    22L,
                    new BigDecimal("10.00"),
                    new BigDecimal("20.00"),
                    new BigDecimal("5.00"),
                    new BigDecimal("10.00")));

    private static final FrozenCharge DECISION = new FrozenCharge(
            "decision-c",
            new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE,
                    UsagePricingState.PRICED,
                    33L,
                    null,
                    new BigDecimal("3.00"),
                    new BigDecimal("3.00"),
                    null,
                    null));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewAttemptUsageService attemptUsage;

    @Autowired
    private ReviewJobRecovery recovery;

    @Autowired
    private LlmUsageRecorder recorder;

    @Autowired
    private LlmUsageEventRepository ledger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PracticeVersionService versions;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ProxyUsageAccumulator accumulator() {
        return new ProxyUsageAccumulator(attemptUsage, transactionManager, meters);
    }

    private ReviewJob runningJob(String slug) {
        return runningJob(slug, false);
    }

    /** @param withPractice whether the workspace gets the practice that the practice script calls name */
    private ReviewJob runningJob(String slug, boolean withPractice) {
        return runningJob(slug, withPractice, true);
    }

    private ReviewJob runningJob(String slug, boolean withPractice, boolean executionStarted) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Late " + slug, slug + "-org", AccountType.ORG, owner);
        if (withPractice) {
            var kind = new ItemKind("scm.pull_request");
            versions.create(new PracticeVersionService.NewPractice(
                    workspace,
                    PRACTICE,
                    kind,
                    DefinitionTestFixtures.definition(kind, PRACTICE, "Review the work.", null),
                    null,
                    0,
                    null));
        }
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setExecutionStartedAt(executionStarted ? Instant.now() : null);
        job.setConfigSnapshot(MAPPER.createObjectNode());
        job.prePersist();
        return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private void register(ReviewJob job, int attempt, ModelKind kind, FrozenCharge charge) {
        assertThat(attemptUsage.registerCall(job.getId(), job.getWorkspaceId(), attempt, "worker-1", kind, charge))
                .isTrue();
    }

    private static ProxyRouting.BilledAttempt billed(ReviewJob job, int attempt, UsageSourceType type) {
        return billed(job, attempt, type, job.getWorkspaceId());
    }

    private static ProxyRouting.BilledAttempt billed(ReviewJob job, int attempt, UsageSourceType type, long workspace) {
        return new ProxyRouting.BilledAttempt(
                type, job.getId(), attempt, BigDecimal.ZERO, "worker-1", 0L, 0L, workspace);
    }

    private void call(ReviewJob job, int attempt, int input, int output) {
        accumulator()
                .accumulate(
                        billed(job, attempt, UsageSourceType.REVIEW_JOB),
                        null,
                        new ProxyTokenUsage(input, output, 0, 0, 0));
    }

    /** Ends the attempt the way a terminal path does: the transition, then the settlement, in one transaction. */
    private void end(ReviewJob job, ReviewJobStatus status, AgentResult.@Nullable LlmUsage runner, FrozenCharge chat) {
        transactions.executeWithoutResult(tx -> {
            jdbc.update(
                    "UPDATE review_job SET status = ?, completed_at = now() WHERE id = ?", status.name(), job.getId());
            attemptUsage.settleAttempt(
                    job,
                    Map.of(ModelKind.CHAT, chat),
                    runner,
                    jobs.findLlmUsageById(job.getId()).orElse(null));
        });
    }

    private static AgentResult.LlmUsage runner(int input, int output, int calls) {
        return new AgentResult.LlmUsage(null, input, output, null, null, null, null, calls);
    }

    private record LedgerRow(
            long input,
            long output,
            int calls,
            int unreported,
            String provenance,
            String verification,
            String pricing,
            @Nullable BigDecimal cost,
            String funding,
            @Nullable Long appliedPriceId,
            Timestamp occurredAt) {}

    private @Nullable LedgerRow ledgerRow(ReviewJob job, int attempt, UsageSourceType type) {
        return jdbc
                .query(
                        "SELECT input_tokens, output_tokens, total_calls, unreported_calls, usage_provenance,"
                                + " measurement_verification, pricing_state, cost_usd, funding_source,"
                                + " applied_price_id, occurred_at FROM llm_usage_event WHERE source_type = ? AND"
                                + " source_id = ? AND source_attempt = ?",
                        (row, n) -> new LedgerRow(
                                row.getLong("input_tokens"),
                                row.getLong("output_tokens"),
                                row.getInt("total_calls"),
                                row.getInt("unreported_calls"),
                                row.getString("usage_provenance"),
                                row.getString("measurement_verification"),
                                row.getString("pricing_state"),
                                row.getBigDecimal("cost_usd"),
                                row.getString("funding_source"),
                                (Long) row.getObject("applied_price_id"),
                                row.getTimestamp("occurred_at")),
                        type.name(),
                        job.getId(),
                        attempt)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private LedgerRow reviewRow(ReviewJob job, int attempt) {
        return Objects.requireNonNull(ledgerRow(job, attempt, UsageSourceType.REVIEW_JOB));
    }

    private int measurements(ReviewJob job) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM review_job_attempt_usage WHERE job_id = ?", Integer.class, job.getId()));
    }

    @Test
    void shouldSettleALateCallOnTheCancelledAttemptWhenItReturnsAfterTheCancel() {
        ReviewJob job = runningJob("late-after-cancel");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 10);
        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        var ended = jdbc.queryForMap(
                "SELECT status, completed_at, llm_calls, llm_input_tokens FROM review_job WHERE id = ?", job.getId());
        Timestamp dated = reviewRow(job, 0).occurredAt();

        call(job, 0, 50, 5);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.input()).isEqualTo(150);
        assertThat(row.output()).isEqualTo(15);
        assertThat(row.calls()).isEqualTo(2);
        assertThat(row.provenance()).isEqualTo(UsageProvenance.PROXY.name());
        assertThat(row.verification()).isEqualTo(MeasurementVerification.VERIFIED.name());
        assertThat(row.pricing()).isEqualTo(UsagePricingState.PRICED.name());
        assertThat(row.funding()).isEqualTo(UsageFundingSource.INSTANCE.name());
        assertThat(row.appliedPriceId()).isEqualTo(11L);
        assertThat(row.cost()).isEqualByComparingTo("0.000180");
        assertThat(row.occurredAt()).isEqualTo(dated);
        // The job ended as it ended: status, end time and the counters the cancel left.
        assertThat(jdbc.queryForMap(
                        "SELECT status, completed_at, llm_calls, llm_input_tokens FROM review_job WHERE id = ?",
                        job.getId()))
                .isEqualTo(ended);
        assertThat(meters.counter("llm.proxy.usage.late_settled").count()).isEqualTo(1.0);
    }

    @Test
    void shouldPriceALateCallAtItsOwnAttemptsChargeWhenTheJobWasHandedOn() {
        ReviewJob job = runningJob("late-after-handoff");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 10);
        transactions.executeWithoutResult(tx -> {
            jobs.lockById(job.getId());
            attemptUsage.holdAttempt(
                    job,
                    Map.of(ModelKind.CHAT, INSTANCE_CHAT),
                    jobs.findLlmUsageById(job.getId()).orElse(null));
            assertThat(jobs.requeueOrphan(job.getId(), "worker-1", 5, Instant.now()))
                    .isEqualTo(1);
            attemptUsage.settleAttempt(job, Map.of(ModelKind.CHAT, INSTANCE_CHAT), null, null);
        });
        // The next claim: attempt 1 runs with another configuration.
        jdbc.update("UPDATE review_job SET status = 'RUNNING', worker_id = 'worker-1' WHERE id = ?", job.getId());
        register(job, 1, ModelKind.CHAT, WORKSPACE_CHAT);

        call(job, 0, 40, 4);
        call(job, 1, 7, 1);

        LedgerRow old = reviewRow(job, 0);
        assertThat(old.input()).isEqualTo(140);
        assertThat(old.output()).isEqualTo(14);
        assertThat(old.funding()).isEqualTo(UsageFundingSource.INSTANCE.name());
        assertThat(old.appliedPriceId()).isEqualTo(11L);
        assertThat(old.cost()).isEqualByComparingTo("0.000168");
        // The running attempt has only its own call, in flight, and no ledger row yet.
        assertThat(ledgerRow(job, 1, UsageSourceType.REVIEW_JOB)).isNull();
        assertThat(jdbc.queryForMap(
                        "SELECT attempt, llm_calls, llm_input_tokens FROM review_job WHERE id = ?", job.getId()))
                .containsEntry("attempt", 1)
                .containsEntry("llm_calls", 1)
                .containsEntry("llm_input_tokens", 7L);
    }

    @Test
    void shouldBillTheLockedAttemptWhenAnExecutorRetriesWithItsDetachedPreExecutionClaim() {
        ReviewJob claim = runningJob("late-detached-retry", false, false);
        assertThat(claim.getExecutionStartedAt()).isNull();
        transactions.executeWithoutResult(tx -> assertThat(
                        jobs.markExecutionStarted(claim.getId(), "worker-1", claim.getAttempt(), Instant.now()))
                .isEqualTo(1));
        assertThat(claim.getExecutionStartedAt()).isNull();
        assertThat(jobs.findById(claim.getId()).orElseThrow().getExecutionStartedAt())
                .isNotNull();
        register(claim, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(claim, 0, 100, 10);
        assertThat(ledgerRow(claim, 0, UsageSourceType.REVIEW_JOB)).isNull();

        var requeued = transactions.execute(tx -> recovery.retryUnadmitted(claim, "worker-1", true));
        assertThat(requeued).isTrue();

        ReviewJob next = jobs.findById(claim.getId()).orElseThrow();
        assertThat(next.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(next.getAttempt()).isEqualTo(1);
        assertThat(next.getLlmCalls()).isZero();
        assertThat(next.getLlmInputTokens()).isZero();
        assertThat(next.getLlmOutputTokens()).isZero();
        assertThat(next.getExecutionStartedAt()).isNull();
        // No later call or terminal write may be needed to project the completed attempt's measured spend.
        var recorded = ledgerRow(claim, 0, UsageSourceType.REVIEW_JOB);
        assertThat(recorded).isNotNull();
        LedgerRow settled = Objects.requireNonNull(recorded);
        assertThat(settled.input()).isEqualTo(100);
        assertThat(settled.output()).isEqualTo(10);
        assertThat(settled.calls()).isEqualTo(1);
        assertThat(settled.provenance()).isEqualTo(UsageProvenance.PROXY.name());
        assertThat(settled.appliedPriceId()).isEqualTo(11L);
        assertThat(settled.cost()).isEqualByComparingTo("0.000120");
        assertThat(ledgerRow(claim, 1, UsageSourceType.REVIEW_JOB)).isNull();
    }

    @Test
    void shouldKeepTheRunnerReportWhenItAlreadyCoversALateCall() {
        ReviewJob job = runningJob("late-runner-covers");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 20);
        end(job, ReviewJobStatus.COMPLETED, runner(300, 60, 2), INSTANCE_CHAT);

        call(job, 0, 50, 10);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.provenance()).isEqualTo(UsageProvenance.RUNNER.name());
        assertThat(row.input()).isEqualTo(300);
        assertThat(row.output()).isEqualTo(60);
        assertThat(row.cost()).isEqualByComparingTo("0.000420");
    }

    @Test
    void shouldSelectTheProxyTotalWhenALateCallMakesItCoverTheRunnerReport() {
        ReviewJob job = runningJob("late-proxy-dominant");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 20);
        end(job, ReviewJobStatus.COMPLETED, runner(120, 25, 1), INSTANCE_CHAT);
        assertThat(reviewRow(job, 0).provenance()).isEqualTo(UsageProvenance.RUNNER.name());

        call(job, 0, 50, 10);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.provenance()).isEqualTo(UsageProvenance.PROXY.name());
        assertThat(row.input()).isEqualTo(150);
        assertThat(row.output()).isEqualTo(30);
        assertThat(row.verification()).isEqualTo(MeasurementVerification.VERIFIED.name());
        assertThat(row.cost()).isEqualByComparingTo("0.000210");
    }

    @Test
    void shouldMakeTheRowUnverifiableWhenALateCallMakesTheTotalsIncomparable() {
        ReviewJob job = runningJob("late-incomparable");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 5);
        end(job, ReviewJobStatus.COMPLETED, runner(1000, 10, 2), INSTANCE_CHAT);

        call(job, 0, 10, 50);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.verification()).isEqualTo(MeasurementVerification.UNVERIFIABLE.name());
        assertThat(row.pricing()).isEqualTo(UsagePricingState.UNPRICED.name());
        assertThat(row.cost()).isNull();
        // The rates stay frozen on the row; only the measurement changed.
        assertThat(row.funding()).isEqualTo(UsageFundingSource.INSTANCE.name());
        assertThat(row.appliedPriceId()).isEqualTo(11L);
    }

    @Test
    void shouldSettleALateChatScriptCallWithItsScriptRowOnTopOfTheRunnerReport() {
        ReviewJob job = runningJob("late-chat-script", true);
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        var chatScript = new PracticeScriptCall(ModelKind.CHAT, null, PRACTICE);
        accumulator()
                .accumulate(
                        billed(job, 0, UsageSourceType.REVIEW_JOB), chatScript, new ProxyTokenUsage(30, 3, 0, 0, 0));
        end(job, ReviewJobStatus.COMPLETED, runner(500, 50, 4), INSTANCE_CHAT);
        assertThat(reviewRow(job, 0).input()).isEqualTo(530);

        accumulator()
                .accumulate(
                        billed(job, 0, UsageSourceType.REVIEW_JOB), chatScript, new ProxyTokenUsage(20, 2, 0, 0, 0));

        // The runner never sees a direct script call, so the late one is added to its report, once.
        LedgerRow row = reviewRow(job, 0);
        assertThat(row.provenance()).isEqualTo(UsageProvenance.RUNNER.name());
        assertThat(row.input()).isEqualTo(550);
        assertThat(row.output()).isEqualTo(55);
        // The script row and the attempt's proxy total took the call together.
        assertThat(jdbc.queryForMap(
                        "SELECT calls, input_tokens FROM review_job_script_usage WHERE job_id = ? AND attempt = 0"
                                + " AND model_kind = 'CHAT'",
                        job.getId()))
                .containsEntry("calls", 2)
                .containsEntry("input_tokens", 50L);
        assertThat(jdbc.queryForObject(
                        "SELECT proxy_input_tokens FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 0",
                        Long.class,
                        job.getId()))
                .isEqualTo(50L);
    }

    @Test
    void shouldRollBackTheProxyTotalWithTheScriptRowWhenTheCallNamesNoPracticeOfTheWorkspace() {
        ReviewJob job = runningJob("late-unknown-practice");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);

        accumulator()
                .accumulate(
                        billed(job, 0, UsageSourceType.REVIEW_JOB),
                        new PracticeScriptCall(ModelKind.CHAT, null, "no-such-practice"),
                        new ProxyTokenUsage(30, 3, 0, 0, 0));

        assertThat(jdbc.queryForObject(
                        "SELECT proxy_calls FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 0",
                        Integer.class,
                        job.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT llm_calls FROM review_job WHERE id = ?", Integer.class, job.getId()))
                .isZero();
        assertThat(meters.counter("llm.proxy.usage.accumulate.failure").count()).isEqualTo(1.0);
    }

    @Test
    void shouldBillALateDecisionCallAtItsSlotChargeAndKeepAnUnreportedOneUnverifiable() {
        ReviewJob job = runningJob("late-decision", true);
        register(job, 0, ModelKind.DECISION, DECISION);
        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        Timestamp dated = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT occurred_at FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 0",
                Timestamp.class,
                job.getId()));
        var decision = new PracticeScriptCall(ModelKind.DECISION, null, PRACTICE);
        var decisionAttempt = billed(job, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION);

        accumulator().accumulate(decisionAttempt, decision, new ProxyTokenUsage(1000, 0, 0, 0, 0));

        LedgerRow row = Objects.requireNonNull(ledgerRow(job, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION));
        assertThat(row.input()).isEqualTo(1000);
        assertThat(row.appliedPriceId()).isEqualTo(33L);
        assertThat(row.verification()).isEqualTo(MeasurementVerification.VERIFIED.name());
        assertThat(row.cost()).isEqualByComparingTo("0.003000");
        // Dated when the attempt ended, though the attempt had no call then.
        assertThat(row.occurredAt()).isEqualTo(dated);

        accumulator().accumulate(decisionAttempt, decision, null);

        LedgerRow unreported = Objects.requireNonNull(ledgerRow(job, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION));
        assertThat(unreported.calls()).isEqualTo(2);
        assertThat(unreported.unreported()).isEqualTo(1);
        assertThat(unreported.verification()).isEqualTo(MeasurementVerification.UNVERIFIABLE.name());
        assertThat(unreported.pricing()).isEqualTo(UsagePricingState.UNPRICED.name());
        assertThat(ledgerRow(job, 0, UsageSourceType.REVIEW_JOB)).isNull();
    }

    @Test
    void shouldProjectTheSameRowsWhenTheTerminalReceiptRepeats() {
        ReviewJob job = runningJob("late-repeated-receipt");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 20);
        end(job, ReviewJobStatus.COMPLETED, runner(300, 60, 2), INSTANCE_CHAT);
        LedgerRow first = reviewRow(job, 0);

        end(job, ReviewJobStatus.COMPLETED, runner(9000, 900, 9), INSTANCE_CHAT);

        assertThat(reviewRow(job, 0)).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT runner_input_tokens FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 0",
                        Integer.class,
                        job.getId()))
                .isEqualTo(300);
    }

    @Test
    void shouldKeepTheAttemptsMonthWhenALateCallCorrectsAnEarlierMonth() {
        ReviewJob job = runningJob("late-earlier-month");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 10);
        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        Timestamp lastMonth =
                Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
        jdbc.update("UPDATE review_job_attempt_usage SET occurred_at = ? WHERE job_id = ?", lastMonth, job.getId());
        jdbc.update("UPDATE llm_usage_event SET occurred_at = ? WHERE source_id = ?", lastMonth, job.getId());

        call(job, 0, 50, 5);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.input()).isEqualTo(150);
        assertThat(row.occurredAt()).isEqualTo(lastMonth);
    }

    @Test
    void shouldDropALateCallWhenAnErasureForgotTheJob() throws Exception {
        ReviewJob job = runningJob("late-after-erasure");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 10);
        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        UUID rowId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM llm_usage_event WHERE workspace_id = ? AND source_id = ? AND source_attempt = 0",
                UUID.class,
                job.getWorkspaceId(),
                job.getId()));
        CountDownLatch callbackReady = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CompletableFuture<Void> late = CompletableFuture.runAsync(() -> {
            callbackReady.countDown();
            try {
                assertThat(releaseCallback.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            call(job, 0, 50, 5);
        });
        try {
            assertThat(callbackReady.await(30, TimeUnit.SECONDS)).isTrue();
            attemptUsage.forgetJob(job.getWorkspaceId(), job.getId());
            // The existing person-data contributor detaches ledger identity after it forgets the measurements.
            jdbc.update(
                    "UPDATE llm_usage_event SET source_id = ?, review_id = NULL WHERE id = ? AND workspace_id = ?",
                    UUID.randomUUID(),
                    rowId,
                    job.getWorkspaceId());
            var detached = jdbc.queryForMap(
                    "SELECT * FROM llm_usage_event WHERE id = ? AND workspace_id = ?", rowId, job.getWorkspaceId());
            releaseCallback.countDown();
            late.get(30, TimeUnit.SECONDS);
            assertThat(jdbc.queryForMap(
                            "SELECT * FROM llm_usage_event WHERE id = ? AND workspace_id = ?",
                            rowId,
                            job.getWorkspaceId()))
                    .isEqualTo(detached);
        } finally {
            releaseCallback.countDown();
        }

        assertThat(measurements(job)).isZero();
        assertThat(ledgerRow(job, 0, UsageSourceType.REVIEW_JOB)).isNull();
        assertThat(meters.counter("llm.proxy.usage.accumulate.superseded").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldDropACallThatNamesAnotherWorkspace() {
        ReviewJob job = runningJob("late-other-tenant");
        ReviewJob other = runningJob("late-other-tenant-b");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 10);
        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        LedgerRow before = reviewRow(job, 0);

        accumulator()
                .accumulate(
                        billed(job, 0, UsageSourceType.REVIEW_JOB, other.getWorkspaceId()),
                        null,
                        new ProxyTokenUsage(50, 5, 0, 0, 0));

        assertThat(reviewRow(job, 0)).isEqualTo(before);
        assertThat(measurements(other)).isZero();
    }

    @Test
    void shouldRefuseToRegisterACallWhenItsAttemptNoLongerRuns() {
        ReviewJob job = runningJob("late-register-refused");
        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        var measurementBefore = jdbc.queryForList(
                "SELECT to_jsonb(u)::text AS measurement FROM review_job_attempt_usage u WHERE job_id = ?",
                job.getId());
        var chargesBefore = jdbc.queryForList(
                "SELECT to_jsonb(c)::text AS charge FROM review_job_attempt_charge c WHERE job_id = ? ORDER BY model_kind",
                job.getId());
        assertThat(measurementBefore).hasSize(1);
        assertThat(chargesBefore).hasSize(1);
        assertThat(ledgerRow(job, 0, UsageSourceType.REVIEW_JOB)).isNull();

        assertThat(attemptUsage.registerCall(
                        job.getId(), job.getWorkspaceId(), 0, "worker-1", ModelKind.DECISION, DECISION))
                .isFalse();

        assertThat(jdbc.queryForList(
                        "SELECT to_jsonb(u)::text AS measurement FROM review_job_attempt_usage u WHERE job_id = ?",
                        job.getId()))
                .isEqualTo(measurementBefore);
        assertThat(jdbc.queryForList(
                        "SELECT to_jsonb(c)::text AS charge FROM review_job_attempt_charge c WHERE job_id = ? ORDER BY model_kind",
                        job.getId()))
                .isEqualTo(chargesBefore);
        assertThat(ledgerRow(job, 0, UsageSourceType.REVIEW_JOB)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = UsageSourceType.class, names = "MENTOR_TURN", mode = EnumSource.Mode.EXCLUDE)
    void shouldLeaveAHistoricalLedgerRowAsItIsWhenItsAttemptHasNoMeasurement(UsageSourceType sourceType) {
        ReviewJob job = runningJob(
                "late-historical-" + sourceType.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        transactions.executeWithoutResult(tx -> recorder.record(
                job.getWorkspaceId(),
                new LlmUsageRecorder.LlmUsageSample(
                        job.getReviewId(),
                        "PRACTICE_REVIEW",
                        sourceType,
                        job.getId(),
                        0,
                        "chat-a",
                        77,
                        7,
                        0,
                        0,
                        0,
                        0,
                        1,
                        INSTANCE_CHAT.price(),
                        UsageProvenance.PROXY,
                        MeasurementVerification.VERIFIED,
                        Instant.now())));
        LedgerRow historical = Objects.requireNonNull(ledgerRow(job, 0, sourceType));
        jdbc.update("UPDATE review_job SET llm_calls = 3, llm_input_tokens = 999 WHERE id = ?", job.getId());

        end(job, ReviewJobStatus.CANCELLED, null, INSTANCE_CHAT);
        call(job, 0, 50, 5);

        assertThat(ledgerRow(job, 0, sourceType)).isEqualTo(historical);
        if (sourceType != UsageSourceType.REVIEW_JOB) {
            assertThat(ledgerRow(job, 0, UsageSourceType.REVIEW_JOB)).isNull();
        }
        assertThat(measurements(job)).isZero();
    }

    @Test
    void shouldPriceAVerifiedMeasurementAgainAtTheResolvedPriceAfterItWasUnverifiableMeanwhile() {
        ReviewJob job = runningJob("late-repriced");
        FrozenCharge unpriced = new FrozenCharge("chat-u", LlmPriceSnapshot.unpricedInstance());
        register(job, 0, ModelKind.CHAT, unpriced);
        call(job, 0, 100, 10);
        end(job, ReviewJobStatus.COMPLETED, runner(200, 10, 1), unpriced);
        assertThat(reviewRow(job, 0).pricing()).isEqualTo(UsagePricingState.UNPRICED.name());
        LlmPriceSnapshot resolved = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                44L,
                null,
                new BigDecimal("4.00"),
                new BigDecimal("8.00"),
                null,
                null);
        // What the repricing pass does to a verified unpriced row: the row, then the attempt's charge.
        transactions.executeWithoutResult(tx -> {
            UUID id = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT id FROM llm_usage_event WHERE source_type = 'REVIEW_JOB' AND source_id = ?",
                    UUID.class,
                    job.getId()));
            UnpricedLedgerRow read = new UnpricedLedgerRow(
                    id,
                    job.getWorkspaceId(),
                    "chat-u",
                    UsageFundingSource.INSTANCE,
                    200,
                    10,
                    0,
                    0,
                    0,
                    null,
                    null,
                    MeasurementVerification.VERIFIED);
            assertThat(ledger.applyResolvedPrice(id, new BigDecimal("0.000880"), resolved, read))
                    .isEqualTo(1);
            assertThat(ledger.adoptResolvedPriceInAttemptCharge(id, resolved)).isEqualTo(1);
        });

        call(job, 0, 0, 20);
        assertThat(reviewRow(job, 0).pricing()).isEqualTo(UsagePricingState.UNPRICED.name());
        call(job, 0, 150, 0);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.verification()).isEqualTo(MeasurementVerification.VERIFIED.name());
        assertThat(row.provenance()).isEqualTo(UsageProvenance.PROXY.name());
        assertThat(row.pricing()).isEqualTo(UsagePricingState.PRICED.name());
        assertThat(row.appliedPriceId()).isEqualTo(44L);
        // 250 in at $4 and 30 out at $8, per 1M.
        assertThat(row.cost()).isEqualByComparingTo("0.001240");
    }

    @Test
    void shouldSettleACallThatWaitedForTheTerminalTransactionAsALateCallOnce() throws Exception {
        ReviewJob job = runningJob("late-waits-for-terminal");
        register(job, 0, ModelKind.CHAT, INSTANCE_CHAT);
        call(job, 0, 100, 10);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> terminal = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            jobs.lockById(job.getId());
            jdbc.update("UPDATE review_job SET status = 'CANCELLED', completed_at = now() WHERE id = ?", job.getId());
            attemptUsage.settleAttempt(
                    job,
                    Map.of(ModelKind.CHAT, INSTANCE_CHAT),
                    null,
                    jobs.findLlmUsageById(job.getId()).orElse(null));
            locked.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

        AtomicInteger callbackPid = new AtomicInteger();
        // The accumulator supplies this same transaction boundary; bind the lock assertion to this callback's
        // connection.
        CompletableFuture<Void> late = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            callbackPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            attemptUsage.settleCall(
                    job.getId(), job.getWorkspaceId(), 0, null, new ReviewJobLlmUsageDelta(50, 5, 0, 0, 0));
        }));
        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> waitingOnALock(callbackPid.get()) > 0);
        } finally {
            release.countDown();
        }
        terminal.get(30, TimeUnit.SECONDS);
        late.get(30, TimeUnit.SECONDS);

        LedgerRow row = reviewRow(job, 0);
        assertThat(row.input()).isEqualTo(150);
        assertThat(row.calls()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT llm_input_tokens FROM review_job WHERE id = ?", Integer.class, job.getId()))
                .isEqualTo(100);
    }

    private int waitingOnALock(int callbackPid) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'"
                        + " AND datname = current_database()",
                Integer.class,
                callbackPid));
    }
}
