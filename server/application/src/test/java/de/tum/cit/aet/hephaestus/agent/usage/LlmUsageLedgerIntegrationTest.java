package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    private AgentJobService agentJobService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private WorkspaceAgentBindingRepository bindingRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private LlmUsageService llmUsageService;

    @Autowired
    private LlmUsageAdminService llmUsageAdminService;

    @Autowired
    private WorkspaceLlmConnectionRepository workspaceConnections;

    @Autowired
    private WorkspaceLlmModelRepository workspaceModels;

    @Autowired
    private PracticeRepository practices;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        return createWorkspace(slug, "Usage " + slug, slug + "-org", AccountType.ORG, owner);
    }

    private LlmUsageRecorder.LlmUsageSample sample(
            LlmUsageJobType jobType,
            LlmUsageSourceType sourceType,
            UUID sourceId,
            int sourceAttempt,
            String model,
            long inputTokens,
            long outputTokens,
            LlmPriceSnapshot price) {
        return new LlmUsageRecorder.LlmUsageSample(
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
                1,
                price,
                UsageProvenance.RUNNER,
                Instant.now());
    }

    private LlmUsageRecorder.LlmUsageSample agentSample(
            UUID sourceId, int sourceAttempt, long inputTokens, LlmPriceSnapshot price) {
        return sample(
                LlmUsageJobType.PULL_REQUEST_REVIEW,
                LlmUsageSourceType.AGENT_JOB,
                sourceId,
                sourceAttempt,
                "gpt-5",
                inputTokens,
                0,
                price);
    }

    private void record(Long workspaceId, LlmUsageRecorder.LlmUsageSample sample) {
        transactionTemplate.executeWithoutResult(status -> recorder.record(workspaceId, sample));
    }

    private void recordUnverifiable(Long workspaceId, LlmUsageRecorder.LlmUsageSample sample) {
        transactionTemplate.executeWithoutResult(status -> recorder.recordUnverifiable(workspaceId, sample));
    }

    /**
     * Production never reads the ledger row by row — every caller goes through one of the aggregates
     * — so this filter lives here rather than as a finder on the repository.
     */
    private List<LlmUsageEvent> eventsOf(Workspace workspace) {
        return usageRepository.findAll().stream()
                .filter(event -> workspace.getId().equals(event.getWorkspace().getId()))
                .toList();
    }

    private LlmPriceSnapshot pricedInstance(String perMInput, String perMOutput) {
        return new LlmPriceSnapshot(
                FundingSource.INSTANCE,
                PricingState.PRICED,
                42L,
                null,
                new BigDecimal(perMInput),
                new BigDecimal(perMOutput),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    private LlmPriceSnapshot workspacePriced(String perMInput, String perMOutput) {
        return new LlmPriceSnapshot(
                FundingSource.WORKSPACE,
                PricingState.PRICED,
                null,
                84L,
                new BigDecimal(perMInput),
                new BigDecimal(perMOutput),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    private double blockedCount(String cap) {
        return meterRegistry
                .counter("llm.budget.blocked", "surface", "agent_job", "cap", cap)
                .count();
    }

    /**
     * Give the workspace a PRACTICE_REVIEW binding funded by {@code fundingSource} — submission
     * resolves it first, because the cap that applies is the one belonging to whoever pays for it.
     */
    private WorkspaceAgentBinding bindDetectionTo(Workspace workspace, FundingSource fundingSource) {
        workspace.getFeatures().setPracticesEnabled(true);
        workspaceRepository.save(workspace);
        var practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug("budget-review");
        practice.setName("Budget review");
        practice.setCriteria("Review the pull request");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practices.save(practice);

        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setTimeoutSeconds(300);
        if (fundingSource == FundingSource.INSTANCE) {
            binding.setInstanceModel(instanceModel(workspace.getWorkspaceSlug()));
        } else {
            var connection = new WorkspaceLlmConnection();
            connection.setWorkspace(workspace);
            connection.setSlug("ledger-connection");
            connection.setDisplayName("Ledger connection");
            connection.setBaseUrl(LlmCatalogTestFixtures.BASE_URL);
            connection.setApiProtocol(LlmCatalogTestFixtures.OPENAI_COMPLETIONS);
            connection.setEnabled(true);
            connection = workspaceConnections.save(connection);
            var model = new WorkspaceLlmModel();
            model.setWorkspace(workspace);
            model.setConnection(connection);
            model.setSlug("ledger-model");
            model.setDisplayName("Ledger model");
            model.setUpstreamModelId("ledger-model");
            model.setEnabled(true);
            binding.setWorkspaceModel(workspaceModels.save(model));
        }
        return bindingRepository.save(binding);
    }

    private PullRequestReviewSubmissionRequest reviewRequest() {
        var pullRequest = new ScmEventPayload.PullRequestData(
                456L,
                42,
                "Review budget isolation",
                "Body",
                Issue.State.OPEN,
                false,
                false,
                10,
                5,
                3,
                "https://github.com/owner/repo/pull/42",
                new RepositoryRef(123L, "owner/repo", "main"),
                789L,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null);
        return new PullRequestReviewSubmissionRequest(pullRequest, "feature/budget", "abc123", "main");
    }

    private LlmModel instanceModel(String slug) {
        LlmConnection connection = llmConnectionRepository.save(LlmCatalogTestFixtures.connection(slug + "-conn"));
        return llmModelRepository.save(LlmCatalogTestFixtures.model(connection, slug + "-model", "gpt-ledger"));
    }

    private AgentJob queuedJob(Workspace workspace, Instant availableAt, @Nullable String holdReason) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setStatus(AgentJobStatus.QUEUED);
        job.setConfigSnapshot(new ObjectMapper().createObjectNode());
        job.prePersist();
        job.setAvailableAt(availableAt);
        job.setHoldReason(holdReason);
        return jobRepository.saveAndFlush(job);
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
                        LlmUsageJobType.PULL_REQUEST_REVIEW,
                        LlmUsageSourceType.AGENT_JOB,
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
        assertThat(event.getSourceType()).isEqualTo(LlmUsageSourceType.AGENT_JOB);
        assertThat(event.getSourceAttempt()).isEqualTo(2);
        assertThat(event.getCostUsd()).isEqualByComparingTo("12.00");
        assertThat(event.getPricingState()).isEqualTo(PricingState.PRICED);
        assertThat(event.getFundingSource()).isEqualTo(FundingSource.INSTANCE);
        assertThat(event.getAppliedPriceId()).isEqualTo(42L);
        assertThat(budgetService.headroom(workspace.getId()).instanceSpentUsd()).isEqualByComparingTo("12.00");
    }

    @Test
    void noChargeAdmissionIsRecordedAsZeroCostAndNeverAlerts() {
        Workspace workspace = setupWorkspace("ledger-instance-free");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("50.00"));
        workspaceRepository.save(workspace);
        double before = meterRegistry.counter("llm.budget.exhausted").count();
        LlmPriceSnapshot noCharge =
                new LlmPriceSnapshot(FundingSource.INSTANCE, PricingState.NO_CHARGE, 43L, null, null, null, null, null);

        record(
                workspace.getId(),
                sample(
                        LlmUsageJobType.MENTOR_TURN,
                        LlmUsageSourceType.MENTOR_TURN,
                        UUID.randomUUID(),
                        0,
                        "local-model",
                        1000,
                        200,
                        noCharge));

        var event = eventsOf(workspace).getFirst();
        assertThat(event.getPricingState()).isEqualTo(PricingState.NO_CHARGE);
        assertThat(event.getCostUsd()).isEqualByComparingTo("0");
        assertThat(budgetService.headroom(workspace.getId()).instanceSpentUsd()).isEqualByComparingTo("0");
        assertThat(budgetService.decide(workspace.getId()).blocks(FundingSource.INSTANCE))
                .isFalse();
        assertThat(meterRegistry.counter("llm.budget.exhausted").count()).isEqualTo(before);
    }

    @Test
    void workspaceFundedSpendNeverCountsTowardTheInstanceBudget() {
        Workspace workspace = setupWorkspace("ledger-byo");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("1.00"));
        workspaceRepository.save(workspace);
        LlmPriceSnapshot workspacePrice = new LlmPriceSnapshot(
                FundingSource.WORKSPACE,
                PricingState.PRICED,
                null,
                84L,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        record(
                workspace.getId(),
                sample(
                        LlmUsageJobType.MENTOR_TURN,
                        LlmUsageSourceType.MENTOR_TURN,
                        UUID.randomUUID(),
                        0,
                        "byo-model",
                        1_000_000,
                        1_000_000,
                        workspacePrice));

        var event = eventsOf(workspace).getFirst();
        assertThat(event.getCostUsd()).isEqualByComparingTo("200.00");
        assertThat(event.getFundingSource()).isEqualTo(FundingSource.WORKSPACE);
        assertThat(event.getAppliedWorkspaceModelId()).isEqualTo(84L);
        assertThat(budgetService.headroom(workspace.getId()).instanceSpentUsd()).isEqualByComparingTo("0");
        assertThat(budgetService.decide(workspace.getId()).blocks(FundingSource.INSTANCE))
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
        assertThat(budgetService.decide(workspace.getId()).forFunding(FundingSource.INSTANCE))
                .isEqualTo(LlmBudgetBlockReason.EXHAUSTED);
    }

    @Test
    void submitIsBlockedForAnExhaustedWorkspace() {
        Workspace workspace = setupWorkspace("ledger-block");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("0.50"));
        workspaceRepository.save(workspace);
        bindDetectionTo(workspace, FundingSource.INSTANCE);
        LlmPriceSnapshot price = pricedInstance("500.00", "0.00"); // $0.50 per 1000 input tokens
        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, price));
        double blockedBefore = blockedCount("instance");

        var job = agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, reviewRequest(), null);

        assertThat(job).isEmpty();
        assertThat(blockedCount("instance")).isEqualTo(blockedBefore + 1);
    }

    /** Work funded by the workspace can still be queued when the instance-funded allowance is exhausted. */
    @Test
    void submitIsNotBlockedByTheInstanceCapWhenDetectionRunsOnTheWorkspacesOwnProvider() {
        Workspace workspace = setupWorkspace("ledger-block-byo-open");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("0.50")); // host cap already reached
        workspaceRepository.save(workspace);
        bindDetectionTo(workspace, FundingSource.WORKSPACE);
        record(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, pricedInstance("500.00", "0.00")));
        double instanceBlockedBefore = blockedCount("instance");
        double byoBlockedBefore = blockedCount("byo");

        var job = agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, reviewRequest(), null);

        assertThat(job).isPresent();
        assertThat(blockedCount("instance")).isEqualTo(instanceBlockedBefore);
        assertThat(blockedCount("byo")).isEqualTo(byoBlockedBefore);
    }

    @Test
    void submitIsBlockedByTheWorkspacesOwnCapForOwnProviderDetection() {
        Workspace workspace = setupWorkspace("ledger-block-byo");
        workspace.setMonthlyByoLlmBudgetUsd(BigDecimal.ZERO); // an immediate pause switch
        workspaceRepository.save(workspace);
        bindDetectionTo(workspace, FundingSource.WORKSPACE);
        double blockedBefore = blockedCount("byo");

        var job = agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, reviewRequest(), null);

        assertThat(job).isEmpty();
        assertThat(blockedCount("byo")).isEqualTo(blockedBefore + 1);
    }

    @Test
    void theWorkspacesOwnCapNeverPausesSharedModelDetection() {
        Workspace workspace = setupWorkspace("ledger-byo-cap-instance-work");
        workspace.setMonthlyByoLlmBudgetUsd(BigDecimal.ZERO);
        workspaceRepository.save(workspace);
        bindDetectionTo(workspace, FundingSource.INSTANCE);
        double instanceBlockedBefore = blockedCount("instance");
        double byoBlockedBefore = blockedCount("byo");

        var job = agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, reviewRequest(), null);

        assertThat(job).isPresent();
        assertThat(blockedCount("instance")).isEqualTo(instanceBlockedBefore);
        assertThat(blockedCount("byo")).isEqualTo(byoBlockedBefore);
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
        recordUnverifiable(workspace.getId(), agentSample(UUID.randomUUID(), 0, 1000, pricedInstance("3.00", "9.00")));

        LlmBudgetDecision decision = budgetService.decide(workspace.getId());

        assertThat(decision.instanceFunded()).isEqualTo(LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED);
        assertThat(decision.workspaceFunded()).isEqualTo(LlmBudgetBlockReason.NONE);
    }

    @Test
    void unverifiableUsageRetainsAdmissionProvenanceWithoutInventingACost() {
        Workspace workspace = setupWorkspace("ledger-uncosted");
        double uncostedBefore = meterRegistry.counter("llm.usage.uncosted").count();

        recordUnverifiable(workspace.getId(), agentSample(UUID.randomUUID(), 3, 0, pricedInstance("3.00", "9.00")));

        var event = eventsOf(workspace).getFirst();
        assertThat(event.getCostUsd()).isNull();
        assertThat(event.getPricingState()).isEqualTo(PricingState.UNPRICED);
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
            AgentJob held = queuedJob(workspace, heldUntil, AgentJob.HOLD_REASON_BUDGET);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), new BigDecimal("50.00"));

            AgentJob reloaded = jobRepository.findById(held.getId()).orElseThrow();
            assertThat(reloaded.getHoldReason()).isNull();
            assertThat(reloaded.getAvailableAt()).isBefore(heldUntil);
            assertThat(reloaded.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
        }

        @Test
        @DisplayName("raising the instance cap releases that workspace's budget-held jobs too")
        void updatingTheInstanceCapReleasesBudgetHolds() {
            Workspace workspace = setupWorkspace("hold-instance");
            Instant heldUntil = Instant.now().plus(Duration.ofHours(1));
            AgentJob held = queuedJob(workspace, heldUntil, AgentJob.HOLD_REASON_BUDGET);

            llmUsageAdminService.updateBudget(workspace.getWorkspaceSlug(), new BigDecimal("50.00"));

            AgentJob reloaded = jobRepository.findById(held.getId()).orElseThrow();
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
            AgentJob backingOff = queuedJob(workspace, backoffUntil, null);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), new BigDecimal("50.00"));

            AgentJob reloaded = jobRepository.findById(backingOff.getId()).orElseThrow();
            assertThat(reloaded.getAvailableAt()).isCloseTo(backoffUntil, within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getHoldReason()).isNull();
        }

        @Test
        @DisplayName("only QUEUED jobs are released — a running job is never rewound")
        void aRunningJobIsNeverReleased() {
            Workspace workspace = setupWorkspace("hold-running");
            Instant availableAt = Instant.now().plus(Duration.ofHours(1));
            AgentJob running = queuedJob(workspace, availableAt, AgentJob.HOLD_REASON_BUDGET);
            running.setStatus(AgentJobStatus.RUNNING);
            jobRepository.saveAndFlush(running);

            llmUsageService.updateOwnProviderBudget(workspace.getId(), new BigDecimal("50.00"));

            AgentJob reloaded = jobRepository.findById(running.getId()).orElseThrow();
            assertThat(reloaded.getAvailableAt()).isCloseTo(availableAt, within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getHoldReason()).isEqualTo(AgentJob.HOLD_REASON_BUDGET);
        }

        @Test
        @DisplayName("the release is scoped to one workspace — another tenant's holds are untouched")
        void anotherWorkspacesHoldsAreUntouched() {
            Workspace mine = setupWorkspace("hold-mine");
            Workspace theirs = setupWorkspace("hold-theirs");
            Instant heldUntil = Instant.now().plus(Duration.ofHours(1));
            AgentJob otherTenantJob = queuedJob(theirs, heldUntil, AgentJob.HOLD_REASON_BUDGET);

            llmUsageService.updateOwnProviderBudget(mine.getId(), new BigDecimal("50.00"));

            AgentJob reloaded = jobRepository.findById(otherTenantJob.getId()).orElseThrow();
            assertThat(reloaded.getAvailableAt()).isCloseTo(heldUntil, within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getHoldReason()).isEqualTo(AgentJob.HOLD_REASON_BUDGET);
        }

        @Test
        @DisplayName("clearing a cap releases holds as well — uncapped can pause nothing")
        void clearingTheOwnProviderCapAlsoReleasesHolds() {
            Workspace workspace = setupWorkspace("hold-clear");
            workspace.setMonthlyByoLlmBudgetUsd(BigDecimal.ZERO);
            workspaceRepository.save(workspace);
            AgentJob held = queuedJob(workspace, Instant.now().plus(Duration.ofHours(1)), AgentJob.HOLD_REASON_BUDGET);

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

        private LlmUsageEvent unpricedRow(String slug) {
            Workspace workspace = setupWorkspace(slug);
            recordUnverifiable(
                    workspace.getId(), agentSample(UUID.randomUUID(), 1, 1_000_000, pricedInstance("3.00", "9.00")));
            LlmUsageEvent row = eventsOf(workspace).getFirst();
            assertThat(row.getPricingState()).isEqualTo(PricingState.UNPRICED);
            return row;
        }

        private int reprice(UUID eventId, BigDecimal costUsd, LlmPriceSnapshot price) {
            Integer updated =
                    transactionTemplate.execute(status -> usageRepository.applyResolvedPrice(eventId, costUsd, price));
            return updated == null ? 0 : updated;
        }

        private LlmUsageEvent reload(UUID eventId) {
            return usageRepository.findById(eventId).orElseThrow();
        }

        @Test
        @DisplayName("every part of the resolved price lands in its own column")
        void theResolvedPriceIsWrittenColumnForColumn() {
            LlmUsageEvent row = unpricedRow("reprice-columns");

            int updated = reprice(
                    row.getId(),
                    new BigDecimal("1.234567"),
                    new LlmPriceSnapshot(
                            FundingSource.INSTANCE,
                            PricingState.PRICED,
                            42L,
                            84L,
                            new BigDecimal("1.11"),
                            new BigDecimal("2.22"),
                            new BigDecimal("3.33"),
                            new BigDecimal("4.44")));

            assertThat(updated).isEqualTo(1);
            LlmUsageEvent repriced = reload(row.getId());
            assertThat(repriced.getPricingState()).isEqualTo(PricingState.PRICED);
            assertThat(repriced.getCostUsd()).isEqualByComparingTo("1.234567");
            assertThat(repriced.getAppliedPriceId()).isEqualTo(42L);
            assertThat(repriced.getAppliedWorkspaceModelId()).isEqualTo(84L);
            assertThat(repriced.getAppliedPer1mInputUsd()).isEqualByComparingTo("1.11");
            assertThat(repriced.getAppliedPer1mOutputUsd()).isEqualByComparingTo("2.22");
            assertThat(repriced.getAppliedPer1mCacheReadUsd()).isEqualByComparingTo("3.33");
            assertThat(repriced.getAppliedPer1mCacheWriteUsd()).isEqualByComparingTo("4.44");
            // The measurement was never in question, and the purse that paid is not repriceable.
            assertThat(repriced.getInputTokens()).isEqualTo(1_000_000L);
            assertThat(repriced.getFundingSource()).isEqualTo(FundingSource.INSTANCE);
        }

        @Test
        @DisplayName("a free model settles as a real zero, with no rate invented and none left behind")
        void aNoChargePriceStoresZeroAndClearsEveryRate() {
            LlmUsageEvent row = unpricedRow("reprice-no-charge");

            int updated = reprice(
                    row.getId(),
                    BigDecimal.ZERO,
                    new LlmPriceSnapshot(
                            FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null));

            assertThat(updated).isEqualTo(1);
            LlmUsageEvent settled = reload(row.getId());
            assertThat(settled.getPricingState()).isEqualTo(PricingState.NO_CHARGE);
            assertThat(settled.getCostUsd()).isEqualByComparingTo("0");
            // The provenance the admission guessed at is gone, not left pointing at a price nobody applied.
            assertThat(settled.getAppliedPriceId()).isNull();
            assertThat(settled.getAppliedPer1mInputUsd()).isNull();
            assertThat(settled.getAppliedPer1mOutputUsd()).isNull();
        }

        @Test
        @DisplayName("a row that is no longer unpriced is left exactly as it was")
        void theUnpricedFenceHoldsAgainstASecondApplication() {
            LlmUsageEvent row = unpricedRow("reprice-fence");
            LlmPriceSnapshot resolved = new LlmPriceSnapshot(
                    FundingSource.INSTANCE, PricingState.PRICED, 42L, null, new BigDecimal("1.11"), null, null, null);
            assertThat(reprice(row.getId(), new BigDecimal("1.234567"), resolved))
                    .isEqualTo(1);

            // What a second pod, or a later sweep, would try: the amount somebody was charged is frozen.
            int again = reprice(row.getId(), new BigDecimal("99.999999"), resolved);

            assertThat(again).isZero();
            assertThat(reload(row.getId()).getCostUsd()).isEqualByComparingTo("1.234567");
        }
    }
}
