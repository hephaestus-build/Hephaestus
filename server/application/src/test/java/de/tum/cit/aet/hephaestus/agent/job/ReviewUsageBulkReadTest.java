package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ReviewUsageBulkReadTest extends BaseUnitTest {
    private static final long WORKSPACE = 7;
    private final ReviewRuntimeRepository runtime = mock(ReviewRuntimeRepository.class);
    private final ReviewRuntimeQueryAdapter adapter = new ReviewRuntimeQueryAdapter(
            mock(ReviewJobRepository.class),
            mock(ReviewAssignmentQuery.class),
            runtime,
            mock(ReviewTimingFactsQuery.class));

    @Test
    void shouldKeepEveryAttemptAndUnknownPricingWithoutCountingReasoningTwice() {
        var review = UUID.randomUUID();
        var noChargeReview = UUID.randomUUID();
        var ids = Set.of(review, noChargeReview);
        var source = UUID.randomUUID();
        var attempts = List.of(
                attempt(source, 0, 2, 100, 40, 30, new BigDecimal("0.25"), UsagePricingState.PRICED),
                attempt(source, 1, 3, 200, 50, 40, null, UsagePricingState.UNPRICED));
        var noCharge =
                List.of(attempt(UUID.randomUUID(), 0, 1, 10, 6, 2, BigDecimal.ZERO, UsagePricingState.NO_CHARGE));
        when(runtime.usageByReview(WORKSPACE, ids)).thenReturn(Map.of(review, attempts, noChargeReview, noCharge));

        var result = adapter.usageByReview(WORKSPACE, ids);

        var measured = Objects.requireNonNull(result.get(review));
        assertThat(measured.calls()).isEqualTo(5);
        assertThat(measured.inputTokens()).isEqualTo(300);
        assertThat(measured.outputTokens()).isEqualTo(90);
        assertThat(measured.priced()).isFalse();
        assertThat(measured.costUsd()).isNull();
        assertThat(measured.attempts()).containsExactlyElementsOf(attempts);
        var free = Objects.requireNonNull(result.get(noChargeReview));
        assertThat(free.priced()).isTrue();
        assertThat(free.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.calls()).isOne();
    }

    @Test
    void shouldUseTheSameWholeReviewTotalsForScalarAndBulkReads() {
        var review = UUID.randomUUID();
        var source = UUID.randomUUID();
        var attempts = List.of(
                attempt(source, 0, 1, 10, 8, 4, new BigDecimal("0.25"), UsagePricingState.PRICED),
                attempt(source, 1, 2, 20, 9, 5, new BigDecimal("0.75"), UsagePricingState.PRICED));
        when(runtime.usage(WORKSPACE, review)).thenReturn(attempts);
        when(runtime.usageByReview(WORKSPACE, Set.of(review))).thenReturn(Map.of(review, attempts));

        var scalar = adapter.usage(WORKSPACE, review);
        var bulk = adapter.usageByReview(WORKSPACE, Set.of(review));

        assertThat(bulk).containsEntry(review, scalar);
        assertThat(scalar.costUsd()).isEqualByComparingTo("1.00");
        assertThat(scalar.calls()).isEqualTo(3);
        assertThat(scalar.outputTokens()).isEqualTo(17);
    }

    @Test
    void shouldLeaveUnmeasuredReviewsAbsentWithoutChangingTheExistingScalarEmptyResult() {
        var review = UUID.randomUUID();
        when(runtime.usageByReview(WORKSPACE, Set.of(review))).thenReturn(Map.of());
        when(runtime.usage(WORKSPACE, review)).thenReturn(List.of());

        assertThat(adapter.usageByReview(WORKSPACE, Set.of(review))).isEmpty();
        var scalar = adapter.usage(WORKSPACE, review);
        assertThat(scalar.calls()).isZero();
        assertThat(scalar.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(scalar.priced()).isTrue();
        assertThat(scalar.attempts()).isEmpty();
    }

    @Test
    void shouldNotReadTheLedgerWhenNoReviewsWereRequested() {
        assertThat(adapter.usageByReview(WORKSPACE, Set.of())).isEmpty();
        verifyNoInteractions(runtime);
    }

    private static ReviewUsageAttempt attempt(
            UUID source,
            int index,
            long calls,
            long input,
            long output,
            long reasoning,
            @Nullable BigDecimal cost,
            UsagePricingState pricing) {
        return new ReviewUsageAttempt(source, index, calls, input, output, reasoning, 0, 0, cost, pricing, false);
    }
}
