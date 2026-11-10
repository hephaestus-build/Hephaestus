package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ReviewBatchSpendTest extends BaseUnitTest {
    final ReviewRepository reviews = mock(ReviewRepository.class);
    final ReviewRuntimeQuery runtime = mock(ReviewRuntimeQuery.class);
    final ReviewBatchSpend spend = new ReviewBatchSpend(reviews, runtime);
    final ReviewBatch batch = ReviewBatch.builder()
            .id(UUID.randomUUID())
            .workspaceId(1)
            .budgetUsd(BigDecimal.TEN)
            .build();
    final Review first = Review.builder().id(UUID.randomUUID()).build();
    final Review second = Review.builder().id(UUID.randomUUID()).build();

    @Test
    void shouldGiveTheNextScanOnlyTheCumulativeRemainingBudget() {
        spent(priced("3.25"), priced("2.10001"));

        assertThat(spend.remaining(batch).remainingUsd()).isEqualByComparingTo("4.6499");
    }

    @Test
    void shouldLeaveTheBudgetUnverifiedWhenUnpricedSpendCouldStillFitUnderIt() {
        spent(priced("3"), unpriced());

        var remaining = spend.remaining(batch);

        assertThat(remaining.verified()).isFalse();
        assertThat(remaining.exhausted()).isTrue();
    }

    @Test
    void shouldExhaustTheBudgetWhenPricedSpendAloneReachesItBesideUnpricedSpend() {
        spent(unpriced(), priced("10"));

        var remaining = spend.remaining(batch);

        assertThat(remaining.verified()).isTrue();
        assertThat(remaining.exhausted()).isTrue();
        assertThat(remaining.remainingUsd()).isEqualByComparingTo("0");
    }

    @Test
    void shouldInventNoBudgetForDatedWork() {
        assertThat(spend.remaining(ReviewBatch.builder().build())).isEqualTo(new ReviewBatchSpend.Snapshot(null, true));
        verifyNoInteractions(runtime, reviews);
    }

    private void spent(ReviewUsageAttempt atFirst, ReviewUsageAttempt atSecond) {
        when(reviews.findByWorkspaceIdAndBatchIdOrderByCreatedAtAsc(1, batch.getId()))
                .thenReturn(List.of(first, second));
        when(runtime.usageByReview(1, Set.of(first.getId(), second.getId())))
                .thenReturn(Map.of(first.getId(), usage(atFirst), second.getId(), usage(atSecond)));
    }

    private static ReviewUsageSnapshot usage(ReviewUsageAttempt attempt) {
        return new ReviewUsageSnapshot(attempt.costUsd(), attempt.costUsd() != null, 1, 100, 100, List.of(attempt));
    }

    private static ReviewUsageAttempt priced(String cost) {
        return attempt(new BigDecimal(cost), UsagePricingState.PRICED);
    }

    private static ReviewUsageAttempt unpriced() {
        return attempt(null, UsagePricingState.UNPRICED);
    }

    private static ReviewUsageAttempt attempt(@Nullable BigDecimal cost, UsagePricingState state) {
        return new ReviewUsageAttempt(UUID.randomUUID(), 0, 1, 100, 100, 0, 0, 0, cost, state);
    }
}
