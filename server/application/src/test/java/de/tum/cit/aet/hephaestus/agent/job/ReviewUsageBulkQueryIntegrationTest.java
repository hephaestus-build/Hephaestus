package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewUsageBulkQueryIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private LlmUsageEventRepository ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewRuntimeQuery runtime;

    @Autowired
    private ReviewRuntimeRepository repository;

    @Test
    void shouldReadOnlyRequestedTenantReviewsAndKeepAllCanonicalSourceAttempts() {
        var workspace = workspace("bulk-usage");
        var other = workspace("bulk-usage-other");
        var review = UUID.randomUUID();
        var freeReview = UUID.randomUUID();
        var unrequested = UUID.randomUUID();
        var missing = UUID.randomUUID();
        var foreignReview = UUID.randomUUID();
        review(workspace, review);
        review(workspace, freeReview);
        review(workspace, unrequested);
        review(other, foreignReview);
        var source = UUID.randomUUID();
        var first = event(
                workspace,
                review,
                source,
                UsageSourceType.REVIEW_JOB,
                0,
                2,
                100,
                50,
                20,
                new BigDecimal("0.25"),
                UsagePricingState.PRICED);
        first.setCacheReadTokens(10);
        first.setCacheWriteTokens(2);
        var script = event(
                workspace,
                review,
                source,
                UsageSourceType.PRACTICE_SCRIPT_DECISION,
                0,
                3,
                200,
                70,
                30,
                new BigDecimal("0.50"),
                UsagePricingState.PRICED);
        script.setCacheReadTokens(20);
        script.setCacheWriteTokens(3);
        var continuation = event(
                workspace,
                review,
                source,
                UsageSourceType.REVIEW_JOB,
                1,
                4,
                400,
                90,
                40,
                null,
                UsagePricingState.UNPRICED);
        continuation.setCacheReadTokens(40);
        continuation.setCacheWriteTokens(4);
        ledger.saveAllAndFlush(List.of(
                first,
                script,
                continuation,
                event(
                        workspace,
                        freeReview,
                        UUID.randomUUID(),
                        UsageSourceType.REVIEW_JOB,
                        0,
                        1,
                        10,
                        6,
                        2,
                        BigDecimal.ZERO,
                        UsagePricingState.NO_CHARGE),
                event(
                        workspace,
                        unrequested,
                        UUID.randomUUID(),
                        UsageSourceType.REVIEW_JOB,
                        0,
                        99,
                        9900,
                        990,
                        900,
                        new BigDecimal("99"),
                        UsagePricingState.PRICED),
                event(
                        other,
                        foreignReview,
                        UUID.randomUUID(),
                        UsageSourceType.REVIEW_JOB,
                        0,
                        88,
                        8800,
                        880,
                        800,
                        new BigDecimal("88"),
                        UsagePricingState.PRICED),
                event(
                        workspace,
                        null,
                        UUID.randomUUID(),
                        UsageSourceType.MENTOR_TURN,
                        0,
                        77,
                        7700,
                        770,
                        700,
                        new BigDecimal("77"),
                        UsagePricingState.PRICED)));

        var result = runtime.usageByReview(workspace.getId(), Set.of(review, freeReview, missing, foreignReview));

        assertThat(result).containsOnlyKeys(review, freeReview);
        var measured = Objects.requireNonNull(result.get(review));
        assertThat(measured.calls()).isEqualTo(9);
        assertThat(measured.inputTokens()).isEqualTo(700);
        assertThat(measured.outputTokens()).isEqualTo(210);
        assertThat(measured.priced()).isFalse();
        assertThat(measured.costUsd()).isNull();
        assertThat(measured.attempts())
                .containsExactly(
                        new ReviewUsageAttempt(
                                source,
                                0,
                                5,
                                300,
                                120,
                                50,
                                30,
                                5,
                                new BigDecimal("0.750000"),
                                UsagePricingState.PRICED,
                                false),
                        new ReviewUsageAttempt(
                                source, 1, 4, 400, 90, 40, 40, 4, null, UsagePricingState.UNPRICED, false));
        assertThat(runtime.usage(workspace.getId(), review)).isEqualTo(measured);
        var free = Objects.requireNonNull(result.get(freeReview));
        assertThat(free.priced()).isTrue();
        assertThat(free.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.attempts())
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.pricingState()).isEqualTo(UsagePricingState.NO_CHARGE));
        assertThat(runtime.usageByReview(other.getId(), Set.of(foreignReview)))
                .hasEntrySatisfying(
                        foreignReview, usage -> assertThat(usage.calls()).isEqualTo(88));
    }

    @Test
    void shouldReadUsageWhenTheRequestedReviewSetExceedsThePostgresqlParameterLimit() {
        var workspace = workspace("bulk-usage-large");
        var other = workspace("bulk-usage-large-other");
        var review = UUID.randomUUID();
        var foreignReview = UUID.randomUUID();
        review(workspace, review);
        review(other, foreignReview);
        var source = UUID.randomUUID();
        ledger.saveAllAndFlush(List.of(
                event(
                        workspace,
                        review,
                        source,
                        UsageSourceType.REVIEW_JOB,
                        0,
                        2,
                        100,
                        50,
                        20,
                        new BigDecimal("0.25"),
                        UsagePricingState.PRICED),
                event(
                        workspace,
                        review,
                        source,
                        UsageSourceType.REVIEW_JOB,
                        1,
                        3,
                        200,
                        60,
                        30,
                        null,
                        UsagePricingState.UNPRICED),
                event(
                        other,
                        foreignReview,
                        UUID.randomUUID(),
                        UsageSourceType.REVIEW_JOB,
                        0,
                        88,
                        8800,
                        880,
                        800,
                        new BigDecimal("88"),
                        UsagePricingState.PRICED)));
        var reviewIds = new HashSet<UUID>();
        reviewIds.add(review);
        reviewIds.add(foreignReview);
        for (long id = 0; id < 65_536; id++) {
            reviewIds.add(new UUID(0, id));
        }
        assertThat(reviewIds).hasSizeGreaterThan(65_535);

        var result = runtime.usageByReview(workspace.getId(), reviewIds);

        assertThat(result).containsOnlyKeys(review);
        var measured = Objects.requireNonNull(result.get(review));
        assertThat(measured.calls()).isEqualTo(5);
        assertThat(measured.inputTokens()).isEqualTo(300);
        assertThat(measured.outputTokens()).isEqualTo(110);
        assertThat(measured.priced()).isFalse();
        assertThat(measured.costUsd()).isNull();
        assertThat(measured.attempts())
                .containsExactly(
                        new ReviewUsageAttempt(
                                source,
                                0,
                                2,
                                100,
                                50,
                                20,
                                0,
                                0,
                                new BigDecimal("0.250000"),
                                UsagePricingState.PRICED,
                                false),
                        new ReviewUsageAttempt(
                                source, 1, 3, 200, 60, 30, 0, 0, null, UsagePricingState.UNPRICED, false));
    }

    @Test
    void shouldCountACallWhoseUsageWasNotMeasuredAtItsUpperBoundAndMarkTheCost() {
        var workspace = workspace("bulk-usage-bound");
        var review = UUID.randomUUID();
        var blind = UUID.randomUUID();
        review(workspace, review);
        review(workspace, blind);
        var source = UUID.randomUUID();
        var measured = event(
                workspace,
                review,
                source,
                UsageSourceType.REVIEW_JOB,
                0,
                1,
                100,
                50,
                0,
                new BigDecimal("0.25"),
                UsagePricingState.PRICED);
        measured.setCostBoundUsd(new BigDecimal("0.90"));
        var unmeasured = event(
                workspace, review, source, UsageSourceType.REVIEW_JOB, 0, 1, 0, 0, 0, null, UsagePricingState.UNPRICED);
        unmeasured.setMeasurementVerification(MeasurementVerification.UNVERIFIABLE);
        unmeasured.setUnreportedCalls(1);
        unmeasured.setCostBoundUsd(new BigDecimal("0.10"));
        var settled = event(
                workspace, review, source, UsageSourceType.REVIEW_JOB, 1, 1, 0, 0, 0, null, UsagePricingState.UNPRICED);
        settled.setMeasurementVerification(MeasurementVerification.UNVERIFIABLE);
        settled.setUnreportedCalls(1);
        settled.setCostBoundUsd(new BigDecimal("0.05"));
        var unbounded = event(
                workspace, blind, source, UsageSourceType.REVIEW_JOB, 0, 1, 0, 0, 0, null, UsagePricingState.UNPRICED);
        unbounded.setMeasurementVerification(MeasurementVerification.UNVERIFIABLE);
        ledger.saveAllAndFlush(List.of(measured, unmeasured, settled, unbounded));

        var usage = runtime.usage(workspace.getId(), review);

        assertThat(usage.priced()).as("a bound keeps the review's cost known").isTrue();
        assertThat(usage.includesUpperBound()).isTrue();
        assertThat(usage.costUsd()).isEqualByComparingTo("0.40");
        assertThat(usage.attempts())
                .containsExactly(
                        new ReviewUsageAttempt(
                                source,
                                0,
                                2,
                                100,
                                50,
                                0,
                                0,
                                0,
                                new BigDecimal("0.350000"),
                                UsagePricingState.PRICED,
                                true),
                        new ReviewUsageAttempt(
                                source,
                                1,
                                1,
                                0,
                                0,
                                0,
                                0,
                                0,
                                new BigDecimal("0.050000"),
                                UsagePricingState.PRICED,
                                true));
        var spent = repository.spent(workspace.getId(), review);
        assertThat(spent.priced()).isTrue();
        assertThat(spent.cost()).as("the scan budget counts the bounds").isEqualByComparingTo("0.40");
        var unknown = repository.spent(workspace.getId(), blind);
        assertThat(unknown.priced())
                .as("a call with neither a price nor a bound stays unknown")
                .isFalse();
        assertThat(runtime.usage(workspace.getId(), blind).costUsd()).isNull();
    }

    @Test
    void shouldReturnNoMeasuredRowsForAnEmptySelectionOrMissingReview() {
        var workspace = workspace("bulk-usage-empty");

        assertThat(repository.usageByReview(workspace.getId(), Set.of())).isEmpty();
        assertThat(runtime.usageByReview(workspace.getId(), Set.of(UUID.randomUUID())))
                .isEmpty();
    }

    private void review(Workspace workspace, UUID id) {
        long item = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?,'scm.pull_request',?,now()) RETURNING id",
                Long.class,
                workspace.getId(),
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)));
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at,admitted_at,completed_at)"
                        + " VALUES (?,?,?,'ITEM','bulk-version','REQUEST','COMPLETE',now(),now(),now(),now())",
                id,
                workspace.getId(),
                item);
    }

    private Workspace workspace(String slug) {
        return createWorkspace(slug, "Bulk usage", slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
    }

    private static LlmUsageEvent event(
            Workspace workspace,
            @Nullable UUID review,
            UUID source,
            UsageSourceType type,
            int attempt,
            int calls,
            long input,
            long output,
            long reasoning,
            @Nullable BigDecimal cost,
            UsagePricingState pricing) {
        var event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType(type == UsageSourceType.MENTOR_TURN ? "MENTOR_CHAT" : "PRACTICE_REVIEW");
        event.setSourceType(type);
        event.setSourceId(source);
        event.setSourceAttempt(attempt);
        event.setReviewId(review);
        event.setInputTokens(input);
        event.setOutputTokens(output);
        event.setReasoningTokens(reasoning);
        event.setTotalCalls(calls);
        event.setCostUsd(cost);
        event.setPricingState(pricing);
        event.setOccurredAt(Instant.parse("2026-10-20T00:00:00Z"));
        event.setStartedAt(event.getOccurredAt());
        return event;
    }
}
