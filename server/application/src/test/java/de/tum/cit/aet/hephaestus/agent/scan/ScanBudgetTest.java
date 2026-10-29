package de.tum.cit.aet.hephaestus.agent.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMethodVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.usage.AdmittedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExecutionSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScanBudgetTest extends BaseUnitTest {
    private final ReviewRuntimeRepository runtime = mock(ReviewRuntimeRepository.class);
    private final ReviewMemberAiPolicy policy = mock(ReviewMemberAiPolicy.class);
    private final LlmAdmissionService admission = mock(LlmAdmissionService.class);
    private final LlmBudgetService monthly = mock(LlmBudgetService.class);
    private final ScanBudget budget = new ScanBudget(
            mock(ReviewMethodVerifier.class),
            mock(ReviewJobRepository.class),
            new SandboxGatewayProperties(8081, 4194304, 120),
            runtime,
            policy,
            admission,
            monthly,
            new PracticeReviewProperties(false, 15, 5, null, 12, 16000, 3, 200000, 1000, Duration.ofMinutes(10)));
    private final ReviewAssignmentSnapshot assignment = RuntimeReviewTestData.assignment(null, null);
    private final ScanExecutionSnapshot scan = new ScanExecutionSnapshot(
            RuntimeReviewTestData.review(ReviewKind.SCAN, assignment),
            ReviewStatus.RUNNING,
            BigDecimal.TEN,
            Instant.now(),
            false,
            true,
            true,
            Set.of(31L),
            2,
            0,
            0,
            0);

    private void admitPricedModel(LlmBudgetDecision monthlyDecision) {
        var binding = mock(WorkspaceAgentBinding.class);
        when(policy.binding(1L, scan.review().id(), List.of(assignment.id()))).thenReturn(Optional.of(binding));
        when(admission.admit(binding))
                .thenReturn(new AdmittedLlmModel(
                        new ResolvedLlmModel(
                                "https://models.example",
                                LlmApiProtocol.OPENAI_COMPLETIONS.wire(),
                                "model",
                                1000,
                                1000,
                                null,
                                false),
                        new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 1L, 2L, 1L),
                        new LlmPriceSnapshot(
                                UsageFundingSource.INSTANCE,
                                UsagePricingState.PRICED,
                                null,
                                null,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                BigDecimal.ONE)));
        when(monthly.decide(1L)).thenReturn(monthlyDecision);
    }

    @Test
    void shouldPermitTheNextFileWhenAnEarlierZeroCallFailureLeftNoSpend() {
        when(runtime.spent(1L, scan.review().id()))
                .thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, true));
        admitPricedModel(LlmBudgetDecision.ALLOWED);
        assertThat(budget.permits(scan, List.of(assignment))).isTrue();
    }

    @Test
    void shouldReportUnverifiableUsageWhenAnEarlierRealCallHasNoPrice() {
        when(runtime.spent(1L, scan.review().id()))
                .thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, false));
        assertThatThrownBy(() -> budget.permits(scan, List.of(assignment)))
                .isInstanceOf(ScanUsageUnverifiableException.class)
                .hasMessageContaining("earlier scan model calls");
    }

    @Test
    void shouldKeepBudgetExhaustionWhenPricedSpendExceedsTheScanCap() {
        when(runtime.spent(1L, scan.review().id()))
                .thenReturn(new ReviewRuntimeRepository.Spend(new BigDecimal("11"), true));
        admitPricedModel(LlmBudgetDecision.ALLOWED);
        assertThat(budget.permits(scan, List.of(assignment))).isFalse();
    }

    @Test
    void shouldReportUnverifiableUsageWhenTheMonthlyGateCannotPriceEarlierCalls() {
        when(runtime.spent(1L, scan.review().id()))
                .thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, true));
        admitPricedModel(new LlmBudgetDecision(LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED, LlmBudgetBlockReason.NONE));
        assertThatThrownBy(() -> budget.permits(scan, List.of(assignment)))
                .isInstanceOf(ScanUsageUnverifiableException.class)
                .hasMessageContaining("earlier workspace model calls");
    }

    @Test
    void shouldKeepBudgetExhaustionWhenThePricedMonthlyCapIsReached() {
        when(runtime.spent(1L, scan.review().id()))
                .thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, true));
        admitPricedModel(new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.NONE));
        assertThat(budget.permits(scan, List.of(assignment))).isFalse();
    }

    @Test
    void reservesTheHighestCacheRateAsAnInputBound() {
        var price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                1L,
                null,
                new BigDecimal("2"),
                new BigDecimal("4"),
                new BigDecimal("1"),
                new BigDecimal("8"));
        assertThat(ScanBudget.cost(price, 1000000, 500000)).isEqualByComparingTo("10");
        assertThat(ScanBudget.rate(price)).isEqualByComparingTo("8");
    }

    @Test
    void missingPricedBucketsFollowTheLedgerContract() {
        var price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE, UsagePricingState.PRICED, 1L, null, null, BigDecimal.ONE, null, null);
        assertThat(ScanBudget.cost(price, 1000000, 1000000)).isEqualByComparingTo("1");
    }

    @Test
    void anUnknownPriceCannotBeReportedAsFree() {
        assertThatThrownBy(() -> ScanBudget.cost(LlmPriceSnapshot.unpricedInstance(), 1, 1))
                .isInstanceOf(ScanModelUnavailableException.class);
        assertThatThrownBy(() -> ScanBudget.rate(LlmPriceSnapshot.unpricedInstance()))
                .isInstanceOf(ScanModelUnavailableException.class);
    }

    @Test
    void explicitlyFreeModelsHaveZeroDollarReservation() {
        var price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, null, null, null, null, null);
        assertThat(ScanBudget.cost(price, 1000000, 1000000)).isZero();
    }
}
