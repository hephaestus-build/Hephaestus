package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptCallUsage;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.job.ScriptUsage;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class LlmUsageLedgerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private LlmUsageRecorder recorder;

    @Autowired
    private LlmUsageEventRepository usageRepository;

    @Autowired
    private LlmBudgetService budgetService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private LlmModelPriceRepository priceRepository;

    @Autowired
    private LlmUsageRepricer repricer;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private LlmUsageService llmUsageService;

    @Autowired
    private LlmUsageAdminService llmUsageAdminService;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        return createWorkspace(slug, "Usage " + slug, slug + "-org", AccountType.ORG, owner);
    }

    private LlmUsageRecorder.LlmUsageSample sample(
            String jobType,
            UsageSourceType sourceType,
            UUID sourceId,
            int sourceAttempt,
            String model,
            long inputTokens,
            long outputTokens,
            LlmPriceSnapshot price) {
        return new LlmUsageRecorder.LlmUsageSample(
                null,
                jobType,
                sourceType,
                sourceId,
                sourceAttempt,
                model,
                inputTokens,
                outputTokens,
                0,
                0,
                0,
                0,
                1,
                price,
                UsageProvenance.RUNNER,
                MeasurementVerification.VERIFIED,
                Instant.now());
    }

    private LlmUsageRecorder.LlmUsageSample agentSample(
            UUID sourceId, int sourceAttempt, long inputTokens, LlmPriceSnapshot price) {
        return sample(
                "PRACTICE_REVIEW", UsageSourceType.REVIEW_JOB, sourceId, sourceAttempt, "gpt-5", inputTokens, 0, price);
    }

    private LlmUsageRecorder.LlmUsageSample agentSample(
            UUID sourceId,
            int sourceAttempt,
            long inputTokens,
            LlmPriceSnapshot price,
            MeasurementVerification verification) {
        return new LlmUsageRecorder.LlmUsageSample(
                null,
                "PRACTICE_REVIEW",
                UsageSourceType.REVIEW_JOB,
                sourceId,
                sourceAttempt,
                "gpt-5",
                inputTokens,
                0,
                0,
                0,
                0,
                0,
                1,
                price,
                UsageProvenance.RUNNER,
                verification,
                Instant.now());
    }

    private void record(Long workspaceId, LlmUsageRecorder.LlmUsageSample sample) {
        transactionTemplate.executeWithoutResult(status -> recorder.record(workspaceId, sample));
    }

    /**
     * Production never reads the ledger row by row — every caller goes through one of the aggregates
     * — so this filter lives here rather than as a finder on the repository.
     */
    private List<LlmUsageEvent> eventsOf(Workspace workspace) {
        return usageRepository.findAll().stream()
                .filter(event -> workspace.getId().equals(event.getWorkspaceId()))
                .toList();
    }

    private LlmPriceSnapshot pricedInstance(String perMInput, String perMOutput) {
        return new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                42L,
                null,
                new BigDecimal(perMInput),
                new BigDecimal(perMOutput),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    /**
     * Give the workspace a PRACTICE_REVIEW binding funded by {@code fundingSource} — submission
     * resolves it first, because the cap that applies is the one belonging to whoever pays for it.
     */
    private LlmModel instanceModel(String slug) {
        LlmConnection connection = llmConnectionRepository.save(LlmCatalogTestFixtures.connection(slug + "-conn"));
        return llmModelRepository.save(LlmCatalogTestFixtures.model(connection, slug + "-model", "gpt-ledger"));
    }

    private ReviewJob queuedJob(Workspace workspace, Instant availableAt, @Nullable String holdReason) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.QUEUED);
        job.setConfigSnapshot(new ObjectMapper().createObjectNode());
        job.prePersist();
        job.setHoldReason(holdReason);
        ReviewJob saved = jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        // The run offers the job no earlier than this.
        jdbc.update("UPDATE agent_run SET available_at=? WHERE id=?", Timestamp.from(availableAt), saved.getRunId());
        return jobRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    void recordAppendsOneLedgerRowUsingTheAdmissionPriceSnapshot() {
        Workspace workspace = setupWorkspace("ledger-instance-priced");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("100.00"));
        workspaceRepository.save(workspace);
        UUID sourceId = UUID.randomUUID();

        record(
                workspace.getId(),
                sample(
                        "PRACTICE_REVIEW",
                        UsageSourceType.REVIEW_JOB,
                        sourceId,
                        2,
                        "gpt-5",
                        1_000_000,
                        1_000_000,
                        pricedInstance("3.00", "9.00")));

        var events = eventsOf(workspace);
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.getSourceId()).isEqualTo(sourceId);
        assertThat(event.getSourceType()).isEqualTo(UsageSourceType.REVIEW_JOB);
        assertThat(event.getSourceAttempt()).isEqualTo(2);
        assertThat(event.getCostUsd()).isEqualByComparingTo("12.00");
        assertThat(event.getPricingState()).isEqualTo(UsagePricingState.PRICED);
        assertThat(event.getFundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
        assertThat(event.getAppliedPriceId()).isEqualTo(42L);
        assertThat(budgetService.headroom(workspace.getId()).instanceSpentUsd()).isEqualByComparingTo("12.00");
    }

    @Test
    void shouldPersistReviewIdentitySeparatelyFromTheRuntimeSourceAndPreserveUsageFields() {
        var workspace = setupWorkspace("ledger-review-identity");
        var job = queuedJob(workspace, Instant.now(), null);
        assertThat(job.getReviewId()).isNotEqualTo(job.getId());
        record(
                workspace.getId(),
                new LlmUsageRecorder.LlmUsageSample(
                        job.getReviewId(),
                        "PRACTICE_REVIEW",
                        UsageSourceType.REVIEW_JOB,
                        job.getId(),
                        7,
                        "gpt-ledger",
                        1234,
                        3456,
                        56,
                        78,
                        444,
                        0,
                        2,
                        pricedInstance("3.00", "9.00"),
                        UsageProvenance.PROXY,
                        MeasurementVerification.VERIFIED,
                        Instant.now()));
        var event = eventsOf(workspace).getFirst();
        assertThat(event.getReviewId()).isEqualTo(job.getReviewId());
        assertThat(event.getSourceId()).isEqualTo(job.getId());
        assertThat(event.getSourceAttempt()).isEqualTo(7);
        assertThat(event.getInputTokens()).isEqualTo(1234);
        assertThat(event.getOutputTokens()).isEqualTo(3456);
        assertThat(event.getCacheReadTokens()).isEqualTo(56);
        assertThat(event.getCacheWriteTokens()).isEqualTo(78);
        assertThat(event.getReasoningTokens()).isEqualTo(444);
        assertThat(event.getSearchUnits()).isZero();
        assertThat(event.getUnreportedCalls()).isZero();
        assertThat(event.getTotalCalls()).isEqualTo(2);
        assertThat(event.getUsageProvenance()).isEqualTo(UsageProvenance.PROXY);
    }

    @Test
    void noChargeAdmissionIsRecordedAsZeroCostAndNeverAlerts() {
        Workspace workspace = setupWorkspace("ledger-instance-free");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("50.00"));
        workspaceRepository.save(workspace);
        double before = meterRegistry.counter("llm.budget.exhausted").count();
        LlmPriceSnapshot noCharge = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, 43L, null, null, null, null, null);

        record(
                workspace.getId(),
                sample(
                        "MENTOR_TURN",
                        UsageSourceType.MENTOR_TURN,
                        UUID.randomUUID(),
                        0,
                        "local-model",
                        1000,
                        200,
                        noCharge));

        var event = eventsOf(workspace).getFirst();
        assertThat(event.getPricingState()).isEqualTo(UsagePricingState.NO_CHARGE);
        assertThat(event.getCostUsd()).isEqualByComparingTo("0");
        assertThat(budgetService.headroom(workspace.getId()).instanceSpentUsd()).isEqualByComparingTo("0");
        assertThat(budgetService.decide(workspace.getId()).blocks(UsageFundingSource.INSTANCE))
                .isFalse();
        assertThat(meterRegistry.counter("llm.budget.exhausted").count()).isEqualTo(before);
    }

    @Test
    void workspaceFundedSpendNeverCountsTowardTheInstanceBudget() {
        Workspace workspace = setupWorkspace("ledger-byo");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("1.00"));
        workspaceRepository.save(workspace);
        LlmPriceSnapshot workspacePrice = new LlmPriceSnapshot(
                UsageFundingSource.WORKSPACE,
                UsagePricingState.PRICED,
                null,
                84L,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        record(
                workspace.getId(),
                sample(
                        "MENTOR_TURN",
                        UsageSourceType.MENTOR_TURN,
                        UUID.randomUUID(),
                        0,
                        "byo-model",
                        1_000_000,
                        1_000_000,
                        workspacePrice));

        var event = eventsOf(workspace).getFirst();
        assertThat(event.getCostUsd()).isEqualByComparingTo("200.00");
        assertThat(event.getFundingSource()).isEqualTo(UsageFundingSource.WORKSPACE);
        assertThat(event.getAppliedWorkspaceModelId()).isEqualTo(84L);
        assertThat(budgetService.headroom(workspace.getId()).instanceSpentUsd()).isEqualByComparingTo("0");
        assertThat(budgetService.decide(workspace.getId()).blocks(UsageFundingSource.INSTANCE))
                .isFalse();
    }

    @Test
    void sourceAttemptIsTheIdempotencyBoundary() {
        Workspace workspace = setupWorkspace("ledger-dup");
        UUID sourceId = UUID.randomUUID();
        LlmPriceSnapshot price = pricedInstance("1.00", "0.00");

        record(workspace.getId(), agentSample(sourceId, 0, 1000, price));
        record(workspace.getId(), agentSample(sourceId, 0, 1000, price));
        record(workspace.getId(), agentSample(sourceId, 1, 1000, price));

        assertThat(eventsOf(workspace))
                .extracting(LlmUsageEvent::getSourceAttempt)
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void crossingTheBudgetFiresTheExhaustedCounterOnce() {
        Workspace workspace = setupWorkspace("ledger-cross");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("1.50"));
        workspaceRepository.save(workspace);
        LlmPriceSnapshot price = pricedInstance("1000.00", "0.00"); // $1.00 per 1000 input tokens
        double before = meterRegistry.counter("llm.budget.exhausted").count();

        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, price));
        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, price));
        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, price));

        assertThat(meterRegistry.counter("llm.budget.exhausted").count()).isEqualTo(before + 1);
        assertThat(budgetService.decide(workspace.getId()).forFunding(UsageFundingSource.INSTANCE))
                .isEqualTo(LlmBudgetBlockReason.EXHAUSTED);
    }

    @Test
    void submitIsBlockedForAnExhaustedWorkspace() {
        Workspace workspace = setupWorkspace("ledger-block");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("0.50"));
        workspaceRepository.save(workspace);
        LlmPriceSnapshot price = pricedInstance("500.00", "0.00"); // $0.50 per 1000 input tokens
        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, price));

        var decision = budgetService.decide(workspace.getId()).forFunding(UsageFundingSource.INSTANCE);

        assertThat(decision).isEqualTo(LlmBudgetBlockReason.EXHAUSTED);
    }

    /** Work funded by the workspace can still be queued when the instance-funded allowance is exhausted. */
    @Test
    void submitIsNotBlockedByTheInstanceCapWhenPracticeReviewRunsOnTheWorkspacesOwnProvider() {
        Workspace workspace = setupWorkspace("ledger-block-byo-open");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("0.50")); // host cap already reached
        workspaceRepository.save(workspace);
        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, pricedInstance("500.00", "0.00")));

        var decision = budgetService.decide(workspace.getId()).forFunding(UsageFundingSource.WORKSPACE);

        assertThat(decision).isEqualTo(LlmBudgetBlockReason.NONE);
    }

    @Test
    void submitIsBlockedByTheWorkspacesOwnCapForOwnProviderPracticeReview() {
        Workspace workspace = setupWorkspace("ledger-block-byo");
        workspace.setMonthlyByoLlmBudgetUsd(BigDecimal.ZERO); // an immediate pause switch
        workspaceRepository.save(workspace);

        var decision = budgetService.decide(workspace.getId()).forFunding(UsageFundingSource.WORKSPACE);

        assertThat(decision).isEqualTo(LlmBudgetBlockReason.EXHAUSTED);
    }

    @Test
    void theWorkspacesOwnCapNeverPausesSharedModelPracticeReview() {
        Workspace workspace = setupWorkspace("ledger-byo-cap-instance-work");
        workspace.setMonthlyByoLlmBudgetUsd(BigDecimal.ZERO);
        workspaceRepository.save(workspace);

        var decision = budgetService.decide(workspace.getId()).forFunding(UsageFundingSource.INSTANCE);

        assertThat(decision).isEqualTo(LlmBudgetBlockReason.NONE);
    }

    /**
     * The workspace's own cap is measured against PRICED own-provider rows only, and its unverifiable
     * signal comes exclusively from unpriced OWN-PROVIDER rows — an unpriced shared model is the
     * host's blind spot and must never pause the workspace's own work.
     */
    @Test
    void theWorkspacesOwnCapReadsOnlyOwnProviderLedgerRows() {
        Workspace workspace = setupWorkspace("ledger-byo-reads");
        workspace.setMonthlyByoLlmBudgetUsd(new BigDecimal("100.00"));
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("100.00"));
        workspaceRepository.save(workspace);
        // An unpriced INSTANCE-funded event: it makes the host's month unverifiable, nothing more.
        record(
                workspace.getId(),
                agentSample(
                        UUID.randomUUID(),
                        0,
                        1000,
                        pricedInstance("3.00", "9.00"),
                        MeasurementVerification.UNVERIFIABLE));

        LlmBudgetDecision decision = budgetService.decide(workspace.getId());

        assertThat(decision.instanceFunded()).isEqualTo(LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED);
        assertThat(decision.workspaceFunded()).isEqualTo(LlmBudgetBlockReason.NONE);
    }

    @Test
    void unverifiableUsageRetainsAdmissionProvenanceWithoutInventingACost() {
        Workspace workspace = setupWorkspace("ledger-uncosted");
        double uncostedBefore = meterRegistry.counter("llm.usage.uncosted").count();

        record(
                workspace.getId(),
                agentSample(
                        UUID.randomUUID(), 3, 0, pricedInstance("3.00", "9.00"), MeasurementVerification.UNVERIFIABLE));

        var event = eventsOf(workspace).getFirst();
        assertThat(event.getCostUsd()).isNull();
        assertThat(event.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
        assertThat(event.getSourceAttempt()).isEqualTo(3);
        assertThat(event.getAppliedPriceId()).isEqualTo(42L);
        assertThat(meterRegistry.counter("llm.usage.uncosted").count()).isEqualTo(uncostedBefore + 1);
    }

    /**
     * Raising a cap takes effect on the next poll rather than up to an hour later. Its precision is
     * the point — it must not fast-forward a crash-retry backoff, which is a different kind of future
     * {@code available_at} entirely.
     */
    @Nested
    @DisplayName("Raising a cap releases the jobs it held")
    class BudgetHoldRelease {

        @Test
        @DisplayName("raising the workspace's own cap releases its budget-held jobs immediately")
        void updatingTheOwnProviderCapReleasesBudgetHolds() {
            Workspace workspace = setupWorkspace("hold-byo");
            Instant heldUntil = Instant.now().plus(Duration.ofHours(1));
            ReviewJob held = queuedJob(workspace, heldUntil, ReviewJob.HOLD_REASON_BUDGET);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), new BigDecimal("50.00"));

            ReviewJob reloaded = jobRepository.findById(held.getId()).orElseThrow();
            assertThat(reloaded.getHoldReason()).isNull();
            assertThat(reloaded.getAvailableAt()).isBefore(heldUntil);
            assertThat(reloaded.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        }

        @Test
        @DisplayName("raising the instance cap releases that workspace's budget-held jobs too")
        void updatingTheInstanceCapReleasesBudgetHolds() {
            Workspace workspace = setupWorkspace("hold-instance");
            Instant heldUntil = Instant.now().plus(Duration.ofHours(1));
            ReviewJob held = queuedJob(workspace, heldUntil, ReviewJob.HOLD_REASON_BUDGET);

            llmUsageAdminService.updateBudget(workspace.getWorkspaceSlug(), new BigDecimal("50.00"));

            ReviewJob reloaded = jobRepository.findById(held.getId()).orElseThrow();
            assertThat(reloaded.getHoldReason()).isNull();
            assertThat(reloaded.getAvailableAt()).isBefore(heldUntil);
        }

        @Test
        @DisplayName("a crash-retry backoff is never fast-forwarded by a cap change")
        void aCrashRetryBackoffIsNeverFastForwardedByACapChange() {
            // A QUEUED job with a future available_at and NO hold reason is backing off from a failed
            // attempt. Releasing it would send a crash-looping job straight back at a failing upstream.
            Workspace workspace = setupWorkspace("hold-retry-backoff");
            Instant backoffUntil = Instant.now().plus(Duration.ofHours(1));
            ReviewJob backingOff = queuedJob(workspace, backoffUntil, null);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), new BigDecimal("50.00"));

            ReviewJob reloaded = jobRepository.findById(backingOff.getId()).orElseThrow();
            assertThat(reloaded.getAvailableAt()).isCloseTo(backoffUntil, within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getHoldReason()).isNull();
        }

        @Test
        @DisplayName("only QUEUED jobs are released — a running job is never rewound")
        void aRunningJobIsNeverReleased() {
            Workspace workspace = setupWorkspace("hold-running");
            Instant availableAt = Instant.now().plus(Duration.ofHours(1));
            ReviewJob running = queuedJob(workspace, availableAt, ReviewJob.HOLD_REASON_BUDGET);
            running.setStatus(ReviewJobStatus.RUNNING);
            RuntimeJobTestData.save(transactionTemplate, jdbc, jobRepository, running);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), new BigDecimal("50.00"));

            ReviewJob reloaded = jobRepository.findById(running.getId()).orElseThrow();
            assertThat(reloaded.getAvailableAt()).isCloseTo(availableAt, within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getHoldReason()).isEqualTo(ReviewJob.HOLD_REASON_BUDGET);
        }

        @Test
        @DisplayName("the release is scoped to one workspace — another tenant's holds are untouched")
        void anotherWorkspacesHoldsAreUntouched() {
            Workspace mine = setupWorkspace("hold-mine");
            Workspace theirs = setupWorkspace("hold-theirs");
            Instant heldUntil = Instant.now().plus(Duration.ofHours(1));
            ReviewJob otherTenantJob = queuedJob(theirs, heldUntil, ReviewJob.HOLD_REASON_BUDGET);

            llmUsageService.updateOwnProviderBudget(mine.getId(), new BigDecimal("50.00"));

            ReviewJob reloaded = jobRepository.findById(otherTenantJob.getId()).orElseThrow();
            assertThat(reloaded.getAvailableAt()).isCloseTo(heldUntil, within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getHoldReason()).isEqualTo(ReviewJob.HOLD_REASON_BUDGET);
        }

        @Test
        @DisplayName("clearing a cap releases holds as well — uncapped can pause nothing")
        void clearingTheOwnProviderCapAlsoReleasesHolds() {
            Workspace workspace = setupWorkspace("hold-clear");
            workspace.setMonthlyByoLlmBudgetUsd(BigDecimal.ZERO);
            workspaceRepository.save(workspace);
            ReviewJob held =
                    queuedJob(workspace, Instant.now().plus(Duration.ofHours(1)), ReviewJob.HOLD_REASON_BUDGET);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), null);

            assertThat(jobRepository.findById(held.getId()).orElseThrow().getHoldReason())
                    .isNull();
            assertThat(workspaceRepository
                            .findById(workspace.getId())
                            .orElseThrow()
                            .getMonthlyByoLlmBudgetUsd())
                    .isNull();
        }
    }

    /**
     * The one UPDATE the ledger allows, against the real table.
     *
     * <p>Its eight assignments are bound out of a single {@link LlmPriceSnapshot} by accessor name, and
     * only a live database can say that each name reached the column it was meant for: a pair swapped
     * between two same-typed rates costs money and is invisible to a mock. Each rate below is therefore a
     * different number.
     */
    @Nested
    @DisplayName("Applying a resolved price to an unpriced row")
    class ApplyResolvedPrice {

        private void accumulate(ReviewJob job, PracticeScriptCallUsage call) {
            transactionTemplate.executeWithoutResult(status -> {
                if (practices
                        .findByWorkspaceIdAndSlug(job.getWorkspaceId(), call.practiceSlug())
                        .isEmpty()) {
                    var workspace =
                            workspaceRepository.findById(job.getWorkspaceId()).orElseThrow();
                    var kind = new ItemKind("scm.pull_request");
                    practiceVersions.create(new PracticeVersionService.NewPractice(
                            workspace,
                            call.practiceSlug(),
                            kind,
                            DefinitionTestFixtures.definition(call.practiceSlug(), "Explain the change."),
                            null,
                            0,
                            null));
                }
                jobRepository.accumulatePracticeScriptUsage(job.getId(), job.getAttempt(), call);
            });
        }

        private LlmUsageEvent scriptRow(
                Workspace workspace, ReviewJob job, ModelKind kind, MeasurementVerification verification) {
            LlmModelPrice price = new LlmModelPrice();
            price.setModel(instanceModel(workspace.getWorkspaceSlug()));
            price.setPricingMode(PricingMode.UNPRICED);
            price.setEffectiveFrom(Instant.now().minusSeconds(60));
            price = priceRepository.saveAndFlush(price);
            record(
                    workspace.getId(),
                    new LlmUsageRecorder.LlmUsageSample(
                            null,
                            "PRACTICE_REVIEW",
                            ScriptUsage.ledgerSourceType(kind),
                            job.getId(),
                            job.getAttempt(),
                            "gpt-ledger",
                            1_000_000,
                            0,
                            0,
                            0,
                            0,
                            0,
                            2,
                            new LlmPriceSnapshot(
                                    UsageFundingSource.INSTANCE,
                                    UsagePricingState.UNPRICED,
                                    price.getId(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null),
                            UsageProvenance.PROXY,
                            verification,
                            Instant.now()));
            price.setPricingMode(PricingMode.PRICED);
            price.setPer1mInputUsd(new BigDecimal("3.00"));
            price.setPer1mOutputUsd(new BigDecimal("9.00"));
            priceRepository.saveAndFlush(price);
            return eventsOf(workspace).getFirst();
        }

        private UnpricedLedgerRow projection(LlmUsageEvent row) {
            return new UnpricedLedgerRow(
                    row.getId(),
                    row.getWorkspaceId(),
                    row.getModel(),
                    row.getFundingSource(),
                    row.getInputTokens(),
                    row.getOutputTokens(),
                    row.getCacheReadTokens(),
                    row.getCacheWriteTokens(),
                    row.getSearchUnits(),
                    row.getAppliedPriceId(),
                    row.getAppliedWorkspaceModelId(),
                    row.getMeasurementVerification());
        }

        @ParameterizedTest
        @EnumSource(ModelKind.class)
        void shouldKeepPartialUsageUnpricedAfterSourceDeletionWhenSomeCallsHaveNoReceipt(ModelKind kind) {
            Workspace workspace =
                    setupWorkspace("reprice-partial-" + kind.name().toLowerCase(Locale.ROOT));
            ReviewJob job = queuedJob(workspace, Instant.now(), null);
            job.setStatus(ReviewJobStatus.RUNNING);
            RuntimeJobTestData.save(transactionTemplate, jdbc, jobRepository, job);
            accumulate(job, new PracticeScriptCallUsage(kind, "known", null, 1_000_000, 0, 0, 0, 0));
            accumulate(job, new PracticeScriptCallUsage(kind, "unknown", null, 0, 0, 0, 0, 0, false));
            LlmUsageEvent row = scriptRow(workspace, job, kind, MeasurementVerification.UNVERIFIABLE);

            assertThat(row.getUnreportedCalls()).isEqualTo(1);
            assertThat(usageRepository.findUnpricedInWindow(
                            row.getOccurredAt().minusSeconds(60),
                            row.getOccurredAt().plusSeconds(60),
                            PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS)))
                    .extracting(UnpricedLedgerRow::id)
                    .doesNotContain(row.getId());

            jobRepository.deleteById(job.getId());
            jobRepository.flush();

            assertThat(usageRepository.sumUnreportedPracticeScriptCalls(
                            workspace.getId(),
                            job.getId(),
                            job.getAttempt(),
                            row.getSourceType().name()))
                    .isZero();
            assertThat(usageRepository.findUnpricedInWindow(
                            row.getOccurredAt().minusSeconds(60),
                            row.getOccurredAt().plusSeconds(60),
                            PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS)))
                    .extracting(UnpricedLedgerRow::id)
                    .doesNotContain(row.getId());
            assertThat(repricer.reprice(projection(row)))
                    .isEqualTo(LlmUsageRepricer.ResultOutcome.UNVERIFIED_MEASUREMENT);
            LlmUsageEvent unchanged = reload(row.getId());
            assertThat(unchanged.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(unchanged.getCostUsd()).isNull();
            assertThat(unchanged.getInputTokens()).isEqualTo(1_000_000);
            assertThat(unchanged.getUnreportedCalls()).isEqualTo(1);
        }

        @ParameterizedTest
        @EnumSource(ModelKind.class)
        void shouldRepriceCompleteUsageWhenZeroIsReportedAndUnknownCallsBelongToAnotherKindOrAttempt(ModelKind kind) {
            Workspace workspace =
                    setupWorkspace("reprice-complete-" + kind.name().toLowerCase(Locale.ROOT));
            ReviewJob job = queuedJob(workspace, Instant.now(), null);
            job.setStatus(ReviewJobStatus.RUNNING);
            RuntimeJobTestData.save(transactionTemplate, jdbc, jobRepository, job);
            accumulate(job, new PracticeScriptCallUsage(kind, "earlier", null, 0, 0, 0, 0, 0, false));
            RuntimeJobTestData.reclaim(jdbc, job);
            accumulate(job, new PracticeScriptCallUsage(kind, "known", null, 1_000_000, 0, 0, 0, 0));
            accumulate(job, new PracticeScriptCallUsage(kind, "reported-zero", null, 0, 0, 0, 0, 0));
            ModelKind other = kind == ModelKind.CHAT ? ModelKind.EMBEDDING : ModelKind.CHAT;
            accumulate(job, new PracticeScriptCallUsage(other, "unknown", null, 0, 0, 0, 0, 0, false));
            LlmUsageEvent row = scriptRow(workspace, job, kind, MeasurementVerification.VERIFIED);

            assertThat(row.getUnreportedCalls()).isZero();
            assertThat(usageRepository.findUnpricedInWindow(
                            row.getOccurredAt().minusSeconds(60),
                            row.getOccurredAt().plusSeconds(60),
                            PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS)))
                    .extracting(UnpricedLedgerRow::id)
                    .contains(row.getId());
            assertThat(repricer.reprice(projection(row))).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);
            LlmUsageEvent repriced = reload(row.getId());
            assertThat(repriced.getPricingState()).isEqualTo(UsagePricingState.PRICED);
            assertThat(repriced.getCostUsd()).isEqualByComparingTo("3.00");
            assertThat(repriced.getUnreportedCalls()).isZero();
        }

        private LlmUsageEvent unpricedRow(String slug) {
            Workspace workspace = setupWorkspace(slug);
            record(
                    workspace.getId(),
                    agentSample(
                            UUID.randomUUID(),
                            1,
                            1_000_000,
                            new LlmPriceSnapshot(
                                    UsageFundingSource.INSTANCE,
                                    UsagePricingState.PRICED,
                                    42L,
                                    null,
                                    null,
                                    new BigDecimal("9.00"),
                                    BigDecimal.ZERO,
                                    BigDecimal.ZERO)));
            LlmUsageEvent row = eventsOf(workspace).getFirst();
            assertThat(row.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            return row;
        }

        private int reprice(UUID eventId, BigDecimal costUsd, LlmPriceSnapshot price) {
            return reprice(eventId, costUsd, price, measured(reload(eventId), 0));
        }

        private int reprice(UUID eventId, BigDecimal costUsd, LlmPriceSnapshot price, UnpricedLedgerRow priced) {
            Integer updated = transactionTemplate.execute(
                    status -> usageRepository.applyResolvedPrice(eventId, costUsd, price, priced));
            return updated == null ? 0 : updated;
        }

        /** The row as the repricing pass read it, with {@code extraInput} tokens it no longer holds. */
        private UnpricedLedgerRow measured(LlmUsageEvent row, long extraInput) {
            return new UnpricedLedgerRow(
                    row.getId(),
                    row.getWorkspaceId(),
                    row.getModel(),
                    row.getFundingSource(),
                    row.getInputTokens() + extraInput,
                    row.getOutputTokens(),
                    row.getCacheReadTokens(),
                    row.getCacheWriteTokens(),
                    row.getSearchUnits(),
                    row.getAppliedPriceId(),
                    row.getAppliedWorkspaceModelId(),
                    row.getMeasurementVerification());
        }

        @Test
        void shouldLeaveARowForTheNextPassWhenALateCallRemeasuredItAfterThePassReadIt() {
            LlmUsageEvent row = unpricedRow("reprice-stale-tokens");
            LlmPriceSnapshot price = new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE,
                    UsagePricingState.PRICED,
                    42L,
                    null,
                    new BigDecimal("1.00"),
                    new BigDecimal("9.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO);

            assertThat(reprice(row.getId(), new BigDecimal("1.000000"), price, measured(row, 5)))
                    .isZero();

            LlmUsageEvent unchanged = reload(row.getId());
            assertThat(unchanged.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(unchanged.getCostUsd()).isNull();
        }

        private LlmUsageEvent reload(UUID eventId) {
            return usageRepository.findById(eventId).orElseThrow();
        }

        /** A verified reranking row, unpriced when it was recorded, whose catalogue price is now PRICED. */
        private LlmUsageEvent rerankingRow(Workspace workspace, ReviewJob job, long inputTokens, long searchUnits) {
            LlmModelPrice price = new LlmModelPrice();
            price.setModel(instanceModel(workspace.getWorkspaceSlug()));
            price.setPricingMode(PricingMode.UNPRICED);
            price.setEffectiveFrom(Instant.now().minusSeconds(60));
            price = priceRepository.saveAndFlush(price);
            record(workspace.getId(), rerankingSample(job, price.getId(), inputTokens, searchUnits, 1));
            price.setPricingMode(PricingMode.PRICED);
            price.setPer1mInputUsd(new BigDecimal("3.00"));
            price.setPer1mOutputUsd(new BigDecimal("9.00"));
            priceRepository.saveAndFlush(price);
            return eventsOf(workspace).getFirst();
        }

        /** The attempt's reranking measurement, frozen at the unpriced snapshot it was recorded with. */
        private LlmUsageRecorder.LlmUsageSample rerankingSample(
                ReviewJob job, long priceId, long inputTokens, long searchUnits, int calls) {
            return new LlmUsageRecorder.LlmUsageSample(
                    null,
                    "PRACTICE_REVIEW",
                    UsageSourceType.PRACTICE_SCRIPT_RERANKING,
                    job.getId(),
                    job.getAttempt(),
                    "gpt-ledger",
                    inputTokens,
                    0,
                    0,
                    0,
                    0,
                    searchUnits,
                    calls,
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.UNPRICED,
                            priceId,
                            null,
                            null,
                            null,
                            null,
                            null),
                    UsageProvenance.PROXY,
                    MeasurementVerification.VERIFIED,
                    Instant.now());
        }

        private List<UnpricedLedgerRow> workList(LlmUsageEvent row) {
            return usageRepository.findUnpricedInWindow(
                    row.getOccurredAt().minusSeconds(60),
                    row.getOccurredAt().plusSeconds(60),
                    PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS));
        }

        @Test
        void shouldRefuseAStalePriceWhenALateRerankingCallAddedSearchUnitsAfterThePassReadTheRow() {
            Workspace workspace = setupWorkspace("reprice-stale-search-units");
            ReviewJob job = queuedJob(workspace, Instant.now(), null);
            LlmUsageEvent row = rerankingRow(workspace, job, 1_000_000, 0);
            long priceId = Objects.requireNonNull(row.getAppliedPriceId());
            // The repricing pass reads the row: tokens it can price, no search units.
            UnpricedLedgerRow read = workList(row).stream()
                    .filter(candidate -> candidate.id().equals(row.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(read.searchUnits()).isZero();

            // A late reranking call adds search units and no tokens; the attempt's row is projected again.
            transactionTemplate.executeWithoutResult(
                    status -> recorder.project(workspace.getId(), rerankingSample(job, priceId, 1_000_000, 5, 2)));
            assertThat(reload(row.getId()).getSearchUnits()).isEqualTo(5);

            // The pass prices what it read; the row it read no longer exists in that form.
            assertThat(repricer.reprice(read)).isEqualTo(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
            LlmUsageEvent unchanged = reload(row.getId());
            assertThat(unchanged.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(unchanged.getCostUsd()).isNull();
            assertThat(unchanged.getInputTokens()).isEqualTo(1_000_000);
            assertThat(unchanged.getSearchUnits()).isEqualTo(5);

            // The next pass reads the new measurement: no price covers search units, so it stays unpriced.
            UnpricedLedgerRow fresh = workList(row).stream()
                    .filter(candidate -> candidate.id().equals(row.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(fresh.searchUnits()).isEqualTo(5);
            assertThat(repricer.reprice(fresh)).isEqualTo(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
            assertThat(reload(row.getId()).getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(reload(row.getId()).getCostUsd()).isNull();
        }

        @Test
        void shouldPriceTheReadTokenMeasurementWhenNoLateCallChangedIt() {
            Workspace workspace = setupWorkspace("reprice-search-units-control");
            ReviewJob job = queuedJob(workspace, Instant.now(), null);
            LlmUsageEvent row = rerankingRow(workspace, job, 1_000_000, 0);
            UnpricedLedgerRow read = workList(row).stream()
                    .filter(candidate -> candidate.id().equals(row.getId()))
                    .findFirst()
                    .orElseThrow();

            assertThat(repricer.reprice(read)).isEqualTo(LlmUsageRepricer.ResultOutcome.REPRICED);

            LlmUsageEvent repriced = reload(row.getId());
            assertThat(repriced.getPricingState()).isEqualTo(UsagePricingState.PRICED);
            assertThat(repriced.getCostUsd()).isEqualByComparingTo("3.00");
            assertThat(repriced.getSearchUnits()).isZero();
        }

        @Test
        void shouldNeverPriceAMeasurementOfSearchUnitsWithoutTokens() {
            Workspace workspace = setupWorkspace("reprice-search-units-only");
            ReviewJob job = queuedJob(workspace, Instant.now(), null);
            LlmUsageEvent row = rerankingRow(workspace, job, 0, 5);
            UnpricedLedgerRow read = projection(row);

            assertThat(workList(row)).extracting(UnpricedLedgerRow::id).doesNotContain(row.getId());
            assertThat(repricer.reprice(read)).isEqualTo(LlmUsageRepricer.ResultOutcome.NO_TOKEN_EVIDENCE);
            assertThat(reprice(
                            row.getId(),
                            new BigDecimal("1.000000"),
                            new LlmPriceSnapshot(
                                    UsageFundingSource.INSTANCE,
                                    UsagePricingState.PRICED,
                                    row.getAppliedPriceId(),
                                    null,
                                    new BigDecimal("3.00"),
                                    new BigDecimal("9.00"),
                                    null,
                                    null),
                            read))
                    .as("the update itself refuses a row without tokens, also with an exact read")
                    .isZero();

            LlmUsageEvent unchanged = reload(row.getId());
            assertThat(unchanged.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(unchanged.getCostUsd()).isNull();
            assertThat(unchanged.getSearchUnits()).isEqualTo(5);
        }

        @Test
        @DisplayName("every part of the resolved price lands in its own column")
        void theResolvedPriceIsWrittenColumnForColumn() {
            LlmUsageEvent row = unpricedRow("reprice-columns");

            int updated = reprice(
                    row.getId(),
                    new BigDecimal("1.234567"),
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.PRICED,
                            42L,
                            84L,
                            new BigDecimal("1.11"),
                            new BigDecimal("2.22"),
                            new BigDecimal("3.33"),
                            new BigDecimal("4.44")));

            assertThat(updated).isEqualTo(1);
            LlmUsageEvent repriced = reload(row.getId());
            assertThat(repriced.getPricingState()).isEqualTo(UsagePricingState.PRICED);
            assertThat(repriced.getCostUsd()).isEqualByComparingTo("1.234567");
            assertThat(repriced.getAppliedPriceId()).isEqualTo(42L);
            assertThat(repriced.getAppliedWorkspaceModelId()).isEqualTo(84L);
            assertThat(repriced.getAppliedPer1mInputUsd()).isEqualByComparingTo("1.11");
            assertThat(repriced.getAppliedPer1mOutputUsd()).isEqualByComparingTo("2.22");
            assertThat(repriced.getAppliedPer1mCacheReadUsd()).isEqualByComparingTo("3.33");
            assertThat(repriced.getAppliedPer1mCacheWriteUsd()).isEqualByComparingTo("4.44");
            // The measurement was never in question, and the purse that paid is not repriceable.
            assertThat(repriced.getInputTokens()).isEqualTo(1_000_000L);
            assertThat(repriced.getFundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
        }

        @Test
        @DisplayName("a free model settles as a real zero, with no rate invented and none left behind")
        void aNoChargePriceStoresZeroAndClearsEveryRate() {
            LlmUsageEvent row = unpricedRow("reprice-no-charge");

            int updated = reprice(
                    row.getId(),
                    BigDecimal.ZERO,
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.NO_CHARGE,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));

            assertThat(updated).isEqualTo(1);
            LlmUsageEvent settled = reload(row.getId());
            assertThat(settled.getPricingState()).isEqualTo(UsagePricingState.NO_CHARGE);
            assertThat(settled.getCostUsd()).isEqualByComparingTo("0");
            // The provenance the admission guessed at is gone, not left pointing at a price nobody applied.
            assertThat(settled.getAppliedPriceId()).isNull();
            assertThat(settled.getAppliedPer1mInputUsd()).isNull();
            assertThat(settled.getAppliedPer1mOutputUsd()).isNull();
        }

        @Test
        @DisplayName("an unverified positive measurement is neither selected nor repriced")
        void shouldNeitherSelectNorRepriceWhenPositiveMeasurementIsUnverified() {
            Workspace workspace = setupWorkspace("reprice-unverified-positive");
            UUID sourceId = UUID.randomUUID();
            var sample = agentSample(
                    sourceId, 1, 1_000_000, pricedInstance("3.00", "9.00"), MeasurementVerification.UNVERIFIABLE);
            record(workspace.getId(), sample);
            LlmUsageEvent row = eventsOf(workspace).getFirst();

            assertThat(row.getSourceId()).isEqualTo(sourceId);
            assertThat(row.getSourceType()).isEqualTo(UsageSourceType.REVIEW_JOB);
            assertThat(row.getSourceAttempt()).isEqualTo(1);
            assertThat(row.getFundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
            assertThat(row.getInputTokens()).isEqualTo(1_000_000L);
            assertThat(row.getMeasurementVerification()).isEqualTo(MeasurementVerification.UNVERIFIABLE);
            assertThat(row.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(row.getCostUsd()).isNull();
            assertThat(row.getAppliedPriceId()).isEqualTo(42L);
            assertThat(row.getAppliedPer1mInputUsd()).isEqualByComparingTo("3.00");
            assertThat(row.getAppliedPer1mOutputUsd()).isEqualByComparingTo("9.00");

            List<UnpricedLedgerRow> worklist = usageRepository.findUnpricedInWindow(
                    sample.occurredAt().minus(Duration.ofHours(1)),
                    sample.occurredAt().plus(Duration.ofHours(1)),
                    PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS));
            int updated = reprice(
                    row.getId(),
                    new BigDecimal("1.110000"),
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.PRICED,
                            42L,
                            84L,
                            new BigDecimal("1.11"),
                            new BigDecimal("2.22"),
                            new BigDecimal("3.33"),
                            new BigDecimal("4.44")));
            LlmUsageEvent after = reload(row.getId());

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(worklist)
                        .as("repricing worklist")
                        .extracting(UnpricedLedgerRow::id)
                        .doesNotContain(row.getId());
                softly.assertThat(updated).as("atomic repricing update count").isZero();
                softly.assertThat(after.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
                softly.assertThat(after.getCostUsd()).isNull();
            });
        }

        @ParameterizedTest(name = "historical UNKNOWN, priced={0}")
        @ValueSource(booleans = {false, true})
        void shouldPreserveHistoricalCostAndBudgetBasisWhenVerificationIsUnknown(boolean priced) {
            Workspace workspace = setupWorkspace("reprice-historical-unknown-" + priced);
            workspace.setMonthlyLlmBudgetUsd(new BigDecimal("100.00"));
            workspaceRepository.saveAndFlush(workspace);
            UUID sourceId = UUID.randomUUID();
            LlmPriceSnapshot admitted = new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE,
                    UsagePricingState.PRICED,
                    42L,
                    null,
                    priced ? new BigDecimal("3.00") : null,
                    new BigDecimal("9.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO);
            var sample = agentSample(sourceId, 2, 1_000_000, admitted);
            record(workspace.getId(), sample);
            LlmUsageEvent original = eventsOf(workspace).getFirst();
            assertThat(original.getMeasurementVerification()).isEqualTo(MeasurementVerification.VERIFIED);
            transactionTemplate.executeWithoutResult(status -> assertThat(jdbc.update(
                            "UPDATE llm_usage_event SET measurement_verification = 'UNKNOWN' WHERE id = ?",
                            original.getId()))
                    .isEqualTo(1));
            LlmUsageEvent historical = reload(original.getId());
            assertThat(historical.getMeasurementVerification()).isEqualTo(MeasurementVerification.UNKNOWN);

            List<UnpricedLedgerRow> worklist = usageRepository.findUnpricedInWindow(
                    sample.occurredAt().minus(Duration.ofHours(1)),
                    sample.occurredAt().plus(Duration.ofHours(1)),
                    PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS));
            int updated = reprice(
                    original.getId(),
                    new BigDecimal("1.110000"),
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.PRICED,
                            43L,
                            84L,
                            new BigDecimal("1.11"),
                            new BigDecimal("2.22"),
                            new BigDecimal("3.33"),
                            new BigDecimal("4.44")));
            LlmUsageEvent after = reload(original.getId());
            var headroom = budgetService.headroom(workspace.getId());

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(worklist).extracting(UnpricedLedgerRow::id).doesNotContain(original.getId());
                softly.assertThat(updated).isZero();
                softly.assertThat(after.getMeasurementVerification()).isEqualTo(MeasurementVerification.UNKNOWN);
                softly.assertThat(after.getPricingState()).isEqualTo(original.getPricingState());
                softly.assertThat(after.getCostUsd()).isEqualTo(original.getCostUsd());
                softly.assertThat(after.getInputTokens()).isEqualTo(1_000_000L);
                softly.assertThat(after.getOutputTokens()).isZero();
                softly.assertThat(after.getCacheReadTokens()).isZero();
                softly.assertThat(after.getCacheWriteTokens()).isZero();
                softly.assertThat(after.getAppliedPriceId()).isEqualTo(42L);
                softly.assertThat(after.getAppliedWorkspaceModelId()).isNull();
                softly.assertThat(after.getAppliedPer1mInputUsd()).isEqualTo(original.getAppliedPer1mInputUsd());
                softly.assertThat(after.getAppliedPer1mOutputUsd()).isEqualByComparingTo("9.00");
                softly.assertThat(after.getAppliedPer1mCacheReadUsd()).isEqualByComparingTo("0");
                softly.assertThat(after.getAppliedPer1mCacheWriteUsd()).isEqualByComparingTo("0");
                softly.assertThat(after.getUsageProvenance()).isEqualTo(original.getUsageProvenance());
                softly.assertThat(after.getSourceId()).isEqualTo(sourceId);
                softly.assertThat(after.getSourceAttempt()).isEqualTo(2);
                softly.assertThat(after.getFundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
                softly.assertThat(after.getOccurredAt()).isEqualTo(original.getOccurredAt());
                softly.assertThat(headroom.instanceSpentUsd()).isEqualByComparingTo(priced ? "3.00" : "0");
                softly.assertThat(headroom.instanceHasUnpricedSpend()).isEqualTo(!priced);
                softly.assertThat(headroom.decide().instanceFunded())
                        .isEqualTo(priced ? LlmBudgetBlockReason.NONE : LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED);
                softly.assertThat(headroom.decide().workspaceFunded()).isEqualTo(LlmBudgetBlockReason.NONE);
            });
        }

        @Test
        @DisplayName("a row with no measured token is never selected or settled, even at a free model")
        void aRowWithNoTokenEvidenceStaysUnknown() {
            Workspace workspace = setupWorkspace("reprice-no-evidence");
            Instant occurredAt = Instant.now();
            record(
                    workspace.getId(),
                    new LlmUsageRecorder.LlmUsageSample(
                            null,
                            "PRACTICE_REVIEW",
                            UsageSourceType.REVIEW_JOB,
                            UUID.randomUUID(),
                            0,
                            "gpt-5",
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            2,
                            pricedInstance("3.00", "9.00"),
                            UsageProvenance.NONE,
                            MeasurementVerification.UNVERIFIABLE,
                            occurredAt));
            LlmUsageEvent unmeasured = eventsOf(workspace).getFirst();
            LlmUsageEvent measured = unpricedRow("reprice-measured-beside");

            int updated = reprice(
                    unmeasured.getId(),
                    BigDecimal.ZERO,
                    new LlmPriceSnapshot(
                            UsageFundingSource.INSTANCE,
                            UsagePricingState.NO_CHARGE,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));

            assertThat(updated).isZero();
            LlmUsageEvent stillUnknown = reload(unmeasured.getId());
            assertThat(stillUnknown.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(stillUnknown.getCostUsd()).isNull();
            assertThat(stillUnknown.getAppliedPriceId()).isEqualTo(42L);
            assertThat(usageRepository.findUnpricedInWindow(
                            occurredAt.minus(Duration.ofHours(1)),
                            occurredAt.plus(Duration.ofHours(1)),
                            PageRequest.of(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS)))
                    .extracting(UnpricedLedgerRow::id)
                    .contains(measured.getId())
                    .doesNotContain(unmeasured.getId());
        }

        @Test
        @DisplayName("a row that is no longer unpriced is left exactly as it was")
        void theUnpricedFenceHoldsAgainstASecondApplication() {
            LlmUsageEvent row = unpricedRow("reprice-fence");
            LlmPriceSnapshot resolved = new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE,
                    UsagePricingState.PRICED,
                    42L,
                    null,
                    new BigDecimal("1.11"),
                    null,
                    null,
                    null);
            assertThat(reprice(row.getId(), new BigDecimal("1.234567"), resolved))
                    .isEqualTo(1);

            // What a second pod, or a later sweep, would try: the amount somebody was charged is frozen.
            int again = reprice(row.getId(), new BigDecimal("99.999999"), resolved);

            assertThat(again).isZero();
            assertThat(reload(row.getId()).getCostUsd()).isEqualByComparingTo("1.234567");
        }
    }
}
