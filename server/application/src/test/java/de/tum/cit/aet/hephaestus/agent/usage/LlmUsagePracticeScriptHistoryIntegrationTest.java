package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The ledger keeps the practice script rows of past attempts. Nothing writes them any more, but the usage report and
 * the budget still count them with the review they belong to.
 */
class LlmUsagePracticeScriptHistoryIntegrationTest extends AbstractWorkspaceIntegrationTest {

    // Use a closed month so budget fixtures do not depend on the wall clock.
    private static final YearMonth MONTH = YearMonth.of(2026, 3);

    private static final LlmPriceSnapshot INSTANCE_PRICE = new LlmPriceSnapshot(
            UsageFundingSource.INSTANCE,
            UsagePricingState.PRICED,
            1L,
            null,
            new BigDecimal("2"),
            new BigDecimal("8"),
            null,
            null);

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
    void shouldCountRunsWithNoPriceRatherThanLedgerRowsWhenOneRunHasSeveralUnpricedRows() {
        Workspace workspace = workspace("usage-history-unpriced");
        LlmPriceSnapshot unpriced = LlmPriceSnapshot.unpricedInstance();

        ReviewJob everyRowUnpriced = runningJob(workspace);
        ledger(workspace, everyRowUnpriced, 0, UsageSourceType.REVIEW_JOB, unpriced, 9_000, 1_000, 4);
        ledger(workspace, everyRowUnpriced, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, unpriced, 2_000, 0, 2);
        ledger(workspace, everyRowUnpriced, 0, UsageSourceType.PRACTICE_SCRIPT_RERANKING, unpriced, 0, 0, 1);
        RuntimeJobTestData.reclaim(jdbc, everyRowUnpriced);
        ledger(workspace, everyRowUnpriced, 1, UsageSourceType.REVIEW_JOB, INSTANCE_PRICE, 9_000, 1_000, 4);
        ledger(workspace, everyRowUnpriced, 1, UsageSourceType.PRACTICE_SCRIPT_DECISION, unpriced, 1_000, 0, 1);

        ReviewJob onlyPracticeScriptUnpriced = runningJob(workspace);
        ledger(workspace, onlyPracticeScriptUnpriced, 0, UsageSourceType.REVIEW_JOB, INSTANCE_PRICE, 9_000, 0, 1);
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
        ledger(workspace, priced, 0, UsageSourceType.REVIEW_JOB, INSTANCE_PRICE, 9_000, 0, 1);
        ledger(workspace, priced, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION, INSTANCE_PRICE, 1_000, 0, 1);

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

    private ReviewJob runningJob(Workspace workspace) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        return jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    /** Retained usage with a known measured token vector and its original price mode. */
    private void ledger(
            Workspace workspace,
            ReviewJob job,
            int attempt,
            UsageSourceType sourceType,
            LlmPriceSnapshot price,
            long input,
            long output,
            int calls) {
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType("PRACTICE_REVIEW");
        event.setSourceType(sourceType);
        event.setModelKind(
                sourceType == UsageSourceType.REVIEW_JOB
                        ? ModelKind.CHAT
                        : ModelKind.valueOf(sourceType.name().substring("PRACTICE_SCRIPT_".length())));
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
        event.setInitialPricingState(price.pricingState());
        event.setMeasurementVerification(MeasurementVerification.VERIFIED);
        event.setOccurredAt(
                MONTH.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600));
        event.setStartedAt(event.getOccurredAt());
        usageRepository.save(event);
    }
}
