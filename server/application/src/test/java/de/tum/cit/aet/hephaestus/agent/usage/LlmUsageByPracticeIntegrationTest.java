package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class LlmUsageByPracticeIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private PracticeVersionService runtimePracticeVersions;

    // Use a closed month so budget fixtures do not depend on the wall clock.
    private static final YearMonth MONTH = YearMonth.of(2026, 3);
    private static final Instant START =
            MONTH.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

    private static final String COMMENTS = "comment-quality";
    private static final String TESTS = "test-coverage";

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
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithAdminUser
    void shouldGroupPracticeScriptCallsByThePracticeThatMadeThem() {
        Workspace workspace = workspace("usage-by-practice-groups");
        long comments = practice(workspace, COMMENTS, "Comment quality");
        long tests = practice(workspace, TESTS, "Test coverage");
        ReviewJob first = runningJob(workspace);
        ReviewJob second = runningJob(workspace);

        call(workspace, first, ModelKind.DECISION, comments, INSTANCE_DECISION, 1_000_000, 100_000);
        call(workspace, first, ModelKind.DECISION, tests, OWN_DECISION, 300_000, 0);
        call(workspace, second, ModelKind.EMBEDDING, comments, NO_CHARGE, 50_000, 0);
        call(workspace, second, ModelKind.DECISION, comments, LlmPriceSnapshot.unpricedInstance(), 7_000, 0);
        // Another job of the first review, whose job row is gone.
        call(
                workspace,
                UUID.randomUUID(),
                0,
                first.getReviewId(),
                ModelKind.DECISION,
                comments,
                INSTANCE_DECISION,
                500_000,
                0,
                START);
        // A row from before each call had its own row names no practice.
        call(workspace, second, ModelKind.DECISION, null, INSTANCE_DECISION, 250_000, 0);
        // A chat call of a practice script bills on the review's own model, not in this report.
        call(workspace, second, ModelKind.CHAT, comments, INSTANCE_DECISION, 9_000_000, 0);

        WorkspaceLlmUsageReportDTO report = report(workspace);

        assertThat(report.byPractice())
                .extracting(LlmUsageByPracticeDTO::practiceSlug)
                .containsExactly(COMMENTS, TESTS, null);
        assertPractice(
                report.byPractice().get(0),
                "Comment quality",
                List.of(AgentPurpose.PRACTICE_DECISION, AgentPurpose.PRACTICE_EMBEDDING),
                new Usage(2, 4, 1_557_000, 100_000, "3.8", "0", 1));
        assertPractice(
                report.byPractice().get(1),
                "Test coverage",
                List.of(AgentPurpose.PRACTICE_DECISION),
                new Usage(1, 1, 300_000, 0, "0", "1", 0));
        assertPractice(
                report.byPractice().get(2),
                null,
                List.of(AgentPurpose.PRACTICE_DECISION),
                new Usage(1, 1, 250_000, 0, "0.5", "0", 0));
        LlmUsagePracticeScriptTotalDTO total = report.practiceScriptTotal();
        assertThat(total.reviews()).as("two jobs of one review are one review").isEqualTo(2);
        assertThat(total.calls()).isEqualTo(6);
        assertThat(total.inputTokens()).isEqualTo(2_107_000);
        assertThat(total.instanceTotalCostUsd())
                .isEqualByComparingTo(sum(report.byPractice(), LlmUsageByPracticeDTO::instanceTotalCostUsd));
        assertThat(total.ownProviderTotalCostUsd())
                .isEqualByComparingTo(sum(report.byPractice(), LlmUsageByPracticeDTO::ownProviderTotalCostUsd));
        assertThat(total.unpricedEventCount()).isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void shouldCountACallInTheMonthItsStartFallsInWhenItLiesOnABoundary() {
        Workspace workspace = workspace("usage-by-practice-boundary");
        long comments = practice(workspace, COMMENTS, "Comment quality");
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
        inputByInstant.forEach((startedAt, input) -> call(
                workspace,
                runningJob(workspace),
                ModelKind.DECISION,
                comments,
                INSTANCE_DECISION,
                input,
                0,
                startedAt));

        WorkspaceLlmUsageReportDTO report = report(workspace);

        assertThat(report.byPractice()).singleElement().satisfies(practice -> {
            assertThat(practice.inputTokens()).isEqualTo(1_001_000);
            assertThat(practice.instanceTotalCostUsd()).isEqualByComparingTo("2.002");
        });
        assertThat(report.practiceScriptTotal().inputTokens()).isEqualTo(1_001_000);
        assertThat(report.practiceScriptTotal().reviews()).isEqualTo(2);
    }

    @Test
    @WithAdminUser
    void shouldCountRunsWithNoPriceRatherThanCallsWhenOneRunHasSeveralUnpricedCalls() {
        Workspace workspace = workspace("usage-by-practice-unpriced");
        long comments = practice(workspace, COMMENTS, "Comment quality");
        long tests = practice(workspace, TESTS, "Test coverage");
        LlmPriceSnapshot unpriced = LlmPriceSnapshot.unpricedInstance();

        ReviewJob everyCallUnpriced = runningJob(workspace);
        call(workspace, everyCallUnpriced, ModelKind.CHAT, null, unpriced, 9_000, 1_000);
        call(workspace, everyCallUnpriced, ModelKind.DECISION, comments, unpriced, 1_000, 0);
        call(workspace, everyCallUnpriced, ModelKind.DECISION, tests, unpriced, 1_000, 0);
        call(workspace, everyCallUnpriced, ModelKind.RERANKING, comments, unpriced, 0, 0);
        retry(everyCallUnpriced);
        call(workspace, everyCallUnpriced, ModelKind.CHAT, null, INSTANCE_DECISION, 9_000, 1_000);
        call(workspace, everyCallUnpriced, ModelKind.DECISION, comments, unpriced, 1_000, 0);

        ReviewJob onlyPracticeScriptUnpriced = runningJob(workspace);
        call(workspace, onlyPracticeScriptUnpriced, ModelKind.CHAT, null, INSTANCE_DECISION, 9_000, 0);
        call(workspace, onlyPracticeScriptUnpriced, ModelKind.DECISION, comments, unpriced, 1_000, 0);

        ReviewJob priced = runningJob(workspace);
        call(workspace, priced, ModelKind.CHAT, null, INSTANCE_DECISION, 9_000, 0);
        call(workspace, priced, ModelKind.DECISION, tests, INSTANCE_DECISION, 1_000, 0);

        WorkspaceLlmUsageReportDTO report = report(workspace);

        // Six unpriced calls in three runs: two attempts of one review and one attempt of another.
        assertThat(report.unpricedEventCount()).isEqualTo(3);
        assertThat(report.byJobType()).singleElement().satisfies(jobType -> {
            assertThat(jobType.unpricedEventCount()).isEqualTo(3L);
            assertThat(jobType.events()).as("four runs: one job ran twice").isEqualTo(4L);
            assertThat(jobType.totalCalls()).isEqualTo(10L);
        });
        assertThat(report.byDay())
                .singleElement()
                .extracting(LlmUsageByDayDTO::unpricedEventCount)
                .isEqualTo(3L);
        assertThat(report.byPractice())
                .extracting(LlmUsageByPracticeDTO::practiceSlug, LlmUsageByPracticeDTO::unpricedEventCount)
                .containsExactlyInAnyOrder(tuple(COMMENTS, 2L), tuple(TESTS, 1L));
        assertThat(report.practiceScriptTotal().unpricedEventCount()).isEqualTo(2);
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

    private long practice(Workspace workspace, String slug, String name) {
        var kind = new ItemKind("scm.pull_request");
        runtimePracticeVersions.create(new PracticeVersionService.NewPractice(
                workspace,
                slug,
                kind,
                DefinitionTestFixtures.definition(kind, name, "Criteria for " + name, null),
                null,
                0,
                null));
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM practice WHERE workspace_id = ? AND slug = ?", Long.class, workspace.getId(), slug));
    }

    private ReviewJob runningJob(Workspace workspace) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        return jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private void retry(ReviewJob job) {
        RuntimeJobTestData.reclaim(jdbc, job);
    }

    private void call(
            Workspace workspace,
            ReviewJob job,
            ModelKind kind,
            @Nullable Long practiceId,
            LlmPriceSnapshot price,
            long input,
            long output) {
        call(workspace, job, kind, practiceId, price, input, output, START);
    }

    /** One ended model call of the job's current attempt, measured at {@code price}. */
    private void call(
            Workspace workspace,
            ReviewJob job,
            ModelKind kind,
            @Nullable Long practiceId,
            LlmPriceSnapshot price,
            long input,
            long output,
            Instant startedAt) {
        ReviewJob current = jobRepository.findById(job.getId()).orElseThrow();
        call(
                workspace,
                current.getId(),
                current.getAttempt(),
                current.getReviewId(),
                kind,
                practiceId,
                price,
                input,
                output,
                startedAt);
    }

    /** One ended model call of a source's attempt, measured at {@code price}. */
    private void call(
            Workspace workspace,
            UUID sourceId,
            int attempt,
            UUID reviewId,
            ModelKind kind,
            @Nullable Long practiceId,
            LlmPriceSnapshot price,
            long input,
            long output,
            Instant startedAt) {
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType("PRACTICE_REVIEW");
        event.setSourceType(UsageSourceType.ofReviewCall(kind));
        event.setSourceId(sourceId);
        event.setSourceAttempt(attempt);
        event.setReviewId(reviewId);
        event.setModelKind(kind);
        event.setPracticeId(practiceId);
        event.setModel("model");
        event.setInputTokens(input);
        event.setOutputTokens(output);
        event.setCostUsd(price.calculateCost(input, output, 0L, 0L).usd());
        event.setPricingState(price.pricingState());
        event.setFundingSource(price.fundingSource());
        event.setAppliedPer1mInputUsd(price.per1mInputUsd());
        event.setAppliedPer1mOutputUsd(price.per1mOutputUsd());
        event.setUsageProvenance(UsageProvenance.PROXY);
        event.setMeasurementVerification(MeasurementVerification.VERIFIED);
        event.setStartedAt(startedAt);
        event.setOccurredAt(startedAt);
        usageRepository.save(event);
    }
}
