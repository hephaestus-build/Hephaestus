package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptCallUsage;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// Expected costs are independent constants; recomputing weights here would hide lost remainders.
class LlmUsageByPracticeIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final UUID LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");

    @Autowired
    private PracticeVersionService runtimePracticeVersions;

    // Use a closed month so budget fixtures do not depend on the wall clock.
    private static final YearMonth MONTH = YearMonth.of(2026, 3);

    private static final String COMMENTS = "comment-quality";
    private static final String TESTS = "test-coverage";
    private static final String ANOTHER = "another-practice";

    private static final LlmPriceSnapshot INSTANCE_DECISION = new LlmPriceSnapshot(
            UsageFundingSource.INSTANCE,
            UsagePricingState.PRICED,
            1L,
            null,
            new BigDecimal("2"),
            new BigDecimal("8"),
            null,
            null);

    private static final LlmPriceSnapshot OWN_DECISION = new LlmPriceSnapshot(
            UsageFundingSource.WORKSPACE,
            UsagePricingState.PRICED,
            null,
            2L,
            new BigDecimal("3.33333333"),
            new BigDecimal("3.33333333"),
            null,
            null);

    private static final LlmPriceSnapshot NO_CHARGE = new LlmPriceSnapshot(
            UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, 3L, null, null, null, null);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private LlmUsageEventRepository usageRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithAdminUser
    void shouldSplitThePracticeScriptLedgerByPracticeAndAddUpToItWhenTheMonthMixesEveryCase() {
        Workspace workspace = workspace("usage-by-practice");
        Workspace other = workspace("usage-by-practice-other");
        practice(workspace, COMMENTS, "Comment quality");
        practice(workspace, TESTS, "Test coverage");
        practice(other, COMMENTS, "Comment quality");

        ReviewJob review = runningJob(workspace);
        calls(review, "DECISION", COMMENTS, 300_000, 50_000, 2);
        calls(review, "DECISION", TESTS, 100_000, 50_000, 1);
        calls(review, "EMBEDDING", COMMENTS, 5_000, 0, 1);
        calls(review, "RERANKING", TESTS, 0, 0, 2);
        calls(review, "RERANKING", COMMENTS, 0, 0, 1);
        calls(review, "CHAT", COMMENTS, 70, 20, 1);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(transaction -> jdbc.update(
                        "INSERT INTO review_job_script_usage (job_id, attempt, model_kind, practice_id, workspace_id,"
                                + " calls, input_tokens, output_tokens) VALUES (?, 0, 'DECISION', (SELECT id FROM practice"
                                + " WHERE workspace_id=? AND slug=?), ?, 1, 1000000, 0)",
                        review.getId(),
                        other.getId(),
                        COMMENTS,
                        other.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
        ledger(workspace, review, 0, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 9_000, 1_000, 4, MONTH);
        ledger(workspace, review, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_DECISION, 400_000, 100_000, 3);
        ledger(workspace, review, 0, UsageSourceType.PRACTICE_SCRIPT_EMBEDDING, NO_CHARGE, 5_000, 0, 1);
        ledger(
                workspace,
                review,
                0,
                UsageSourceType.PRACTICE_SCRIPT_RERANKING,
                LlmPriceSnapshot.unpricedInstance(),
                0,
                0,
                3);
        retry(review);
        calls(review, "DECISION", COMMENTS, 1, 0, 1);
        calls(review, "DECISION", TESTS, 2, 0, 1);
        ledger(workspace, review, 1, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 9_000, 1_000, 4, MONTH);
        ledger(workspace, review, 1, UsageSourceType.PRACTICE_SCRIPT_DECISION, OWN_DECISION, 3, 0, 2);

        practice(workspace, ANOTHER, "Another practice");
        ReviewJob another = runningJob(workspace);
        calls(another, "DECISION", ANOTHER, 10_000, 0, 1);
        ledger(workspace, another, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_DECISION, 10_000, 0, 1);

        ReviewJob deleted = runningJob(workspace);
        calls(deleted, "DECISION", COMMENTS, 20_000, 0, 1);
        ledger(workspace, deleted, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_DECISION, 20_000, 0, 1);
        jdbc.update("DELETE FROM review_job WHERE id = ?", deleted.getId());

        ReviewJob lastMonth = runningJob(workspace);
        calls(lastMonth, "DECISION", COMMENTS, 500_000, 0, 1);
        ledger(
                workspace,
                lastMonth,
                0,
                UsageSourceType.PRACTICE_SCRIPT_DECISION,
                INSTANCE_DECISION,
                500_000,
                0,
                1,
                MONTH.minusMonths(1));

        ReviewJob elsewhere = runningJob(other);
        calls(elsewhere, "DECISION", COMMENTS, 700_000, 0, 1);
        ledger(other, elsewhere, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_DECISION, 700_000, 0, 1);

        WorkspaceLlmUsageReportDTO report = report(workspace);

        assertThat(report.byPractice())
                .extracting(LlmUsageByPracticeDTO::practiceSlug)
                .as("most spend first, whatever the purse")
                .containsExactly(COMMENTS, TESTS, null, ANOTHER);
        List<LlmUsageByPracticeDTO> rows = report.byPractice();

        // Decision, attempt 0: weights 0.3M·2 + 0.05M·8 = 1.0 and 0.1M·2 + 0.05M·8 = 0.6 of a $1.60 row.
        // Decision, attempt 1: weights 1 and 2 of a $0.00001 row. Truncated: 0.000003 and 0.000006; the
        // heavier practice takes the remaining 0.000001.
        // The review with the unpriced reranker row counts once for each practice and once in the total.
        assertPractice(
                rows.get(0),
                "Comment quality",
                List.of(
                        AgentPurpose.PRACTICE_DECISION,
                        AgentPurpose.PRACTICE_EMBEDDING,
                        AgentPurpose.PRACTICE_RERANKING),
                new Usage(1, 5, 305_001, 50_000, "1.000000", "0.000003", 1));
        assertPractice(
                rows.get(1),
                "Test coverage",
                List.of(AgentPurpose.PRACTICE_DECISION, AgentPurpose.PRACTICE_RERANKING),
                new Usage(1, 4, 100_002, 50_000, "0.600000", "0.000007", 1));
        assertPractice(
                rows.get(3),
                "Another practice",
                List.of(AgentPurpose.PRACTICE_DECISION),
                new Usage(1, 1, 10_000, 0, "0.020000", "0", 0));
        assertPractice(
                rows.get(2),
                null,
                List.of(AgentPurpose.PRACTICE_DECISION),
                new Usage(1, 1, 20_000, 0, "0.040000", "0", 0));

        LlmUsagePracticeScriptTotalDTO total = report.practiceScriptTotal();
        assertThat(total.reviews()).isEqualTo(3);
        assertThat(total.calls()).isEqualTo(11);
        assertThat(total.inputTokens()).isEqualTo(435_003);
        assertThat(total.outputTokens()).isEqualTo(100_000);
        assertThat(total.instanceTotalCostUsd()).isEqualByComparingTo("1.66");
        assertThat(total.ownProviderTotalCostUsd()).isEqualByComparingTo("0.00001");
        assertThat(total.unpricedEventCount()).isEqualTo(1);
        assertThat(sum(report.byPractice(), LlmUsageByPracticeDTO::instanceTotalCostUsd))
                .isEqualByComparingTo(total.instanceTotalCostUsd());
        assertThat(sum(report.byPractice(), LlmUsageByPracticeDTO::ownProviderTotalCostUsd))
                .isEqualByComparingTo(total.ownProviderTotalCostUsd());
        assertThat(report.byPractice().stream()
                        .mapToLong(LlmUsageByPracticeDTO::inputTokens)
                        .sum())
                .isEqualTo(total.inputTokens());
        assertThat(report.byPractice().stream()
                        .mapToLong(LlmUsageByPracticeDTO::outputTokens)
                        .sum())
                .isEqualTo(total.outputTokens());
        assertThat(report.byPractice().stream()
                        .mapToLong(LlmUsageByPracticeDTO::calls)
                        .sum())
                .isEqualTo(total.calls());
        assertThat(report.byPractice().stream()
                        .mapToLong(LlmUsageByPracticeDTO::unpricedEventCount)
                        .sum())
                .isEqualTo(2);
        assertThat(report.byPractice().stream()
                        .mapToLong(LlmUsageByPracticeDTO::reviews)
                        .sum())
                .isEqualTo(4);

        LlmUsageByJobTypeDTO reviews = report.byJobType().stream()
                .filter(row -> row.jobType().equals("PRACTICE_REVIEW"))
                .findFirst()
                .orElseThrow();
        assertThat(reviews.events())
                .as("two attempts ran; practice script rows are not runs")
                .isEqualTo(2);
        assertThat(reviews.totalCalls()).as("calls still count every row").isEqualTo(19);
        assertThat(report.byDay())
                .singleElement()
                .extracting(LlmUsageByDayDTO::events)
                .isEqualTo(2L);
    }

    @Test
    @WithAdminUser
    void shouldGiveTheWholeRemainderToOnePracticeWhenThreeShareARowEvenly() {
        Workspace workspace = workspace("usage-by-practice-thirds");
        ReviewJob review = runningJob(workspace);
        for (String slug : List.of("gamma", "alpha", "beta")) {
            practice(workspace, slug, slug);
            calls(review, "DECISION", slug, 1, 0, 1);
        }
        ledger(workspace, review, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, OWN_DECISION, 3, 0, 3);

        WorkspaceLlmUsageReportDTO report = report(workspace);

        assertThat(report.byPractice())
                .extracting(
                        LlmUsageByPracticeDTO::practiceSlug,
                        practice -> practice.ownProviderTotalCostUsd()
                                .stripTrailingZeros()
                                .toPlainString())
                .containsExactly(tuple("alpha", "0.000004"), tuple("beta", "0.000003"), tuple("gamma", "0.000003"));
        assertThat(report.practiceScriptTotal().ownProviderTotalCostUsd()).isEqualByComparingTo("0.00001");
    }

    @Test
    @WithAdminUser
    void shouldCountALedgerRowInTheMonthItsInstantFallsInWhenItLiesOnABoundary() {
        Workspace workspace = workspace("usage-by-practice-boundary");
        practice(workspace, COMMENTS, "Comment quality");
        Instant from = MONTH.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = MONTH.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<Instant, Long> inputByInstant = Map.of(
                from.minus(1, ChronoUnit.MICROS),
                100_000L,
                from,
                1_000L,
                to.minus(1, ChronoUnit.MICROS),
                1_000_000L,
                to,
                10_000L);
        inputByInstant.forEach((occurredAt, input) -> {
            ReviewJob review = runningJob(workspace);
            calls(review, "DECISION", COMMENTS, input, 0, 1);
            ledger(
                    workspace,
                    review,
                    0,
                    UsageSourceType.PRACTICE_SCRIPT_DECISION,
                    INSTANCE_DECISION,
                    input,
                    0,
                    1,
                    occurredAt);
        });

        WorkspaceLlmUsageReportDTO report = report(workspace);

        assertThat(report.byPractice()).singleElement().satisfies(practice -> {
            assertThat(practice.inputTokens()).isEqualTo(1_001_000);
            assertThat(practice.instanceTotalCostUsd()).isEqualByComparingTo("2.002");
        });
        assertThat(report.practiceScriptTotal().inputTokens()).isEqualTo(1_001_000);
        assertThat(report.practiceScriptTotal().reviews()).isEqualTo(2);
    }

    @Test
    void shouldCountAReviewOnceWhenItsPracticeScriptRowsCarryItsJobId() {
        Workspace workspace = workspace("usage-by-practice-mean");
        ReviewJob priced = runningJob(workspace);
        ReviewJob partlyUnpriced = runningJob(workspace);
        ledger(workspace, priced, 0, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 500_000, 0, 1, MONTH);
        ledger(workspace, priced, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_DECISION, 500_000, 0, 1);
        ledger(workspace, partlyUnpriced, 0, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 500_000, 0, 1, MONTH);
        ledger(
                workspace,
                partlyUnpriced,
                0,
                UsageSourceType.PRACTICE_SCRIPT_RERANKING,
                LlmPriceSnapshot.unpricedInstance(),
                0,
                0,
                1);

        LlmUsageEventRepository.ReviewCostAggregate mean = usageRepository.aggregateCostPerReview(
                workspace.getId(),
                "PRACTICE_REVIEW",
                MONTH.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                MONTH.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        assertThat(mean.getReviews()).isEqualTo(1);
        assertThat(mean.getTotalCostUsd()).isEqualByComparingTo("2");
    }

    @Test
    @WithAdminUser
    void shouldCountRunsAndReviewsWithNoPriceRatherThanLedgerRowsWhenOneRunHasSeveralUnpricedRows() {
        Workspace workspace = workspace("usage-by-practice-unpriced");
        practice(workspace, COMMENTS, "Comment quality");
        practice(workspace, TESTS, "Test coverage");
        LlmPriceSnapshot unpriced = LlmPriceSnapshot.unpricedInstance();

        ReviewJob everyRowUnpriced = runningJob(workspace);
        calls(everyRowUnpriced, "DECISION", COMMENTS, 1_000, 0, 1);
        calls(everyRowUnpriced, "DECISION", TESTS, 1_000, 0, 1);
        calls(everyRowUnpriced, "RERANKING", COMMENTS, 0, 0, 1);
        ledger(workspace, everyRowUnpriced, 0, UsageSourceType.REVIEW_JOB, unpriced, 9_000, 1_000, 4);
        ledger(workspace, everyRowUnpriced, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, unpriced, 2_000, 0, 2);
        ledger(workspace, everyRowUnpriced, 0, UsageSourceType.PRACTICE_SCRIPT_RERANKING, unpriced, 0, 0, 1);
        retry(everyRowUnpriced);
        calls(everyRowUnpriced, "DECISION", COMMENTS, 1_000, 0, 1);
        ledger(workspace, everyRowUnpriced, 1, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 9_000, 1_000, 4);
        ledger(workspace, everyRowUnpriced, 1, UsageSourceType.PRACTICE_SCRIPT_DECISION, unpriced, 1_000, 0, 1);

        ReviewJob onlyPracticeScriptUnpriced = runningJob(workspace);
        calls(onlyPracticeScriptUnpriced, "DECISION", COMMENTS, 1_000, 0, 1);
        ledger(workspace, onlyPracticeScriptUnpriced, 0, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 9_000, 0, 1);
        ledger(
                workspace,
                onlyPracticeScriptUnpriced,
                0,
                UsageSourceType.PRACTICE_SCRIPT_DECISION,
                unpriced,
                1_000,
                0,
                1);

        ReviewJob priced = runningJob(workspace);
        calls(priced, "DECISION", TESTS, 1_000, 0, 1);
        ledger(workspace, priced, 0, UsageSourceType.REVIEW_JOB, INSTANCE_DECISION, 9_000, 0, 1);
        ledger(workspace, priced, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_DECISION, 1_000, 0, 1);

        WorkspaceLlmUsageReportDTO report = report(workspace);

        // Five unpriced rows in three runs: two attempts of one review and one attempt of another.
        assertThat(report.unpricedEventCount()).isEqualTo(3);
        assertThat(report.byJobType())
                .singleElement()
                .extracting(LlmUsageByJobTypeDTO::unpricedEventCount)
                .isEqualTo(3L);
        assertThat(report.byDay())
                .singleElement()
                .extracting(LlmUsageByDayDTO::unpricedEventCount)
                .isEqualTo(3L);
        assertThat(report.byPractice())
                .extracting(LlmUsageByPracticeDTO::practiceSlug, LlmUsageByPracticeDTO::unpricedEventCount)
                .containsExactlyInAnyOrder(tuple(COMMENTS, 2L), tuple(TESTS, 1L));
        assertThat(report.practiceScriptTotal().unpricedEventCount()).isEqualTo(2);
    }

    @Test
    @WithAdminUser
    void shouldWeighEachPracticesCacheReadsAtTheCacheRateWhenItSplitsADecisionRow() {
        Workspace workspace = workspace("usage-by-practice-cache");
        practice(workspace, COMMENTS, "Comment quality");
        practice(workspace, TESTS, "Test coverage");
        ReviewJob review = runningJob(workspace);
        transactionTemplate.executeWithoutResult(tx -> {
            jobRepository.accumulatePracticeScriptUsage(
                    review.getId(),
                    0,
                    new PracticeScriptCallUsage(ModelKind.DECISION, COMMENTS, null, 100_000, 0, 300_000, 0, 0));
            jobRepository.accumulatePracticeScriptUsage(
                    review.getId(),
                    0,
                    new PracticeScriptCallUsage(ModelKind.DECISION, TESTS, null, 200_000, 0, 0, 0, 0));
        });
        LlmPriceSnapshot cached = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                1L,
                null,
                new BigDecimal("2"),
                new BigDecimal("8"),
                new BigDecimal("0.5"),
                null);
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType("PRACTICE_REVIEW");
        event.setSourceType(UsageSourceType.PRACTICE_SCRIPT_DECISION);
        event.setSourceId(review.getId());
        event.setModel("model");
        event.setInputTokens(300_000);
        event.setCacheReadTokens(300_000);
        event.setTotalCalls(2);
        event.setCostUsd(cached.calculateCost(300_000, 0, 300_000, 0).usd());
        event.setPricingState(UsagePricingState.PRICED);
        event.setFundingSource(UsageFundingSource.INSTANCE);
        event.setAppliedPer1mInputUsd(cached.per1mInputUsd());
        event.setAppliedPer1mOutputUsd(cached.per1mOutputUsd());
        event.setAppliedPer1mCacheReadUsd(cached.per1mCacheReadUsd());
        event.setOccurredAt(MONTH.atDay(2).atStartOfDay(ZoneOffset.UTC).toInstant());
        usageRepository.save(event);

        WorkspaceLlmUsageReportDTO report = report(workspace);

        // A $0.75 row: 0.1M·2 + 0.3M·0.5 = 0.35 and 0.2M·2 = 0.40. Weighing the cache reads at the input rate
        // would give 0.60 to comment-quality.
        assertPractice(
                report.byPractice().get(0),
                "Test coverage",
                List.of(AgentPurpose.PRACTICE_DECISION),
                new Usage(1, 1, 200_000, 0, "0.400000", "0", 0));
        assertPractice(
                report.byPractice().get(1),
                "Comment quality",
                List.of(AgentPurpose.PRACTICE_DECISION),
                new Usage(1, 1, 400_000, 0, "0.350000", "0", 0));
        assertThat(report.practiceScriptTotal().inputTokens()).isEqualTo(600_000);
        assertThat(report.practiceScriptTotal().instanceTotalCostUsd()).isEqualByComparingTo("0.75");
    }

    private record Usage(
            long reviews,
            long calls,
            long inputTokens,
            long outputTokens,
            String instanceCostUsd,
            String ownProviderCostUsd,
            long unpriced) {}

    private static void assertPractice(
            LlmUsageByPracticeDTO practice, @Nullable String name, List<AgentPurpose> purposes, Usage expected) {
        assertThat(practice.practiceName()).isEqualTo(name);
        assertThat(practice.purposes()).isEqualTo(purposes);
        assertThat(practice.reviews()).isEqualTo(expected.reviews());
        assertThat(practice.calls()).isEqualTo(expected.calls());
        assertThat(practice.inputTokens()).isEqualTo(expected.inputTokens());
        assertThat(practice.outputTokens()).isEqualTo(expected.outputTokens());
        assertThat(practice.instanceTotalCostUsd()).isEqualByComparingTo(expected.instanceCostUsd());
        assertThat(practice.ownProviderTotalCostUsd()).isEqualByComparingTo(expected.ownProviderCostUsd());
        assertThat(practice.unpricedEventCount()).isEqualTo(expected.unpriced());
    }

    private static BigDecimal sum(List<LlmUsageByPracticeDTO> rows, Function<LlmUsageByPracticeDTO, BigDecimal> cost) {
        return rows.stream().map(cost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private WorkspaceLlmUsageReportDTO report(Workspace workspace) {
        WorkspaceLlmUsageReportDTO report = webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/usage?month={month}", workspace.getWorkspaceSlug(), MONTH.toString())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceLlmUsageReportDTO.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(report);
    }

    private Workspace workspace(String slug) {
        Workspace workspace =
                createWorkspace(slug, "Usage " + slug, slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        ensureAdminMembership(workspace);
        return workspace;
    }

    private void practice(Workspace workspace, String slug, String name) {
        var kind = new ItemKind("scm.pull_request");
        runtimePracticeVersions.create(new PracticeVersionService.NewPractice(
                workspace,
                slug,
                kind,
                DefinitionTestFixtures.definition(kind, name, "Criteria for " + name, null),
                null,
                0,
                null));
    }

    private ReviewJob runningJob(Workspace workspace) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofMinutes(10)));
        job.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        return jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private void retry(ReviewJob job) {
        job.setAttempt(job.getAttempt() + 1);
        jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private void calls(ReviewJob job, String kind, String practice, long input, long output, int count) {
        transactionTemplate.executeWithoutResult(tx -> {
            for (int call = 0; call < count; call++) {
                boolean first = call == 0;
                jobRepository.accumulatePracticeScriptUsage(
                        job.getId(),
                        job.getAttempt(),
                        new PracticeScriptCallUsage(
                                ModelKind.valueOf(kind),
                                practice,
                                null,
                                first ? input : 0,
                                first ? output : 0,
                                0,
                                0,
                                0));
            }
        });
    }

    private void ledger(
            Workspace workspace,
            ReviewJob job,
            int attempt,
            UsageSourceType sourceType,
            LlmPriceSnapshot price,
            long input,
            long output,
            int calls) {
        ledger(workspace, job, attempt, sourceType, price, input, output, calls, MONTH);
    }

    private void ledger(
            Workspace workspace,
            ReviewJob job,
            int attempt,
            UsageSourceType sourceType,
            LlmPriceSnapshot price,
            long input,
            long output,
            int calls,
            YearMonth month) {
        Instant occurredAt =
                month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        ledger(workspace, job, attempt, sourceType, price, input, output, calls, occurredAt);
    }

    private void ledger(
            Workspace workspace,
            ReviewJob job,
            int attempt,
            UsageSourceType sourceType,
            LlmPriceSnapshot price,
            long input,
            long output,
            int calls,
            Instant occurredAt) {
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType("PRACTICE_REVIEW");
        event.setSourceType(sourceType);
        event.setSourceId(job.getId());
        event.setSourceAttempt(attempt);
        event.setModel("model");
        event.setInputTokens(input);
        event.setOutputTokens(output);
        event.setTotalCalls(calls);
        event.setCostUsd(price.calculateCost(input, output, 0L, 0L).usd());
        event.setPricingState(price.pricingState());
        event.setFundingSource(price.fundingSource());
        event.setAppliedPer1mInputUsd(price.per1mInputUsd());
        event.setAppliedPer1mOutputUsd(price.per1mOutputUsd());
        event.setOccurredAt(occurredAt);
        usageRepository.save(event);
    }
}
