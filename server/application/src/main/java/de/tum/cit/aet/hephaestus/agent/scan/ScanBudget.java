package de.tum.cit.aet.hephaestus.agent.scan;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMethodVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExecutionSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScanBudget {
    private final ReviewMethodVerifier methods;
    private final SandboxGatewayProperties gateway;
    private final ReviewRuntimeRepository runtime;
    private final ReviewMemberAiPolicy policy;
    private final LlmAdmissionService admission;
    private final LlmBudgetService monthly;
    private final PracticeReviewProperties limits;

    public boolean permits(ScanExecutionSnapshot scan, List<ReviewAssignmentSnapshot> selected) {
        try {
            return permitsModel(scan, selected);
        } catch (LlmModelUnavailableException unavailable) {
            throw new ScanModelUnavailableException("The frozen scan model is unavailable", unavailable);
        }
    }

    private boolean permitsModel(ScanExecutionSnapshot scan, List<ReviewAssignmentSnapshot> selected) {
        var workspaceId = scan.review().workspaceId();
        var reviewId = scan.review().id();
        var spent = spent(workspaceId, reviewId);
        if (!spent.priced())
            throw new ScanUsageUnverifiableException("The cost of earlier scan model calls is unverifiable");
        var assignmentIds = selected.stream().map(ReviewAssignmentSnapshot::id).toList();
        var binding = policy.binding(workspaceId, reviewId, assignmentIds)
                .orElseThrow(() -> new ScanModelUnavailableException("The frozen scan model is unavailable"));
        methods.require(selected.getFirst().practice().method(), "review", binding);
        var review = admission.admit(binding);
        if (!permitsMonthlySpend(workspaceId, review.price().fundingSource())) return false;
        var calls = callCap(selected.size());
        Integer contextWindow = review.resolved().contextWindow();
        if (contextWindow == null)
            throw new ScanModelUnavailableException("A budgeted scan requires a bounded model context");
        int input = contextWindow;
        var output = Objects.requireNonNullElse(review.resolved().maxOutputTokens(), input);
        if (input <= 0 || output <= 0)
            throw new ScanModelUnavailableException("The frozen scan model has no finite token bound");
        BigDecimal estimate = cost(
                        review.price(),
                        Math.multiplyExact(calls, Math.min(input, gateway.maxRequestBytes())),
                        Math.multiplyExact(calls, output))
                .add(BigDecimal.valueOf(calls).movePointLeft(6));
        return spent.cost().add(estimate).compareTo(scan.budgetUsd()) <= 0;
    }

    private boolean permitsMonthlySpend(long workspaceId, UsageFundingSource fundingSource) {
        var reason = monthly.decide(workspaceId).forFunding(fundingSource);
        if (reason == LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED)
            throw new ScanUsageUnverifiableException("The cost of earlier workspace model calls is unverifiable");
        return reason == LlmBudgetBlockReason.NONE;
    }

    public long callCap(int practices) {
        return Math.multiplyExact((long) practices, limits.practiceModelCalls());
    }

    public ReviewRuntimeRepository.Spend spent(long workspaceId, UUID reviewId) {
        return runtime.spent(workspaceId, reviewId);
    }

    public static BigDecimal cost(LlmPriceSnapshot price, long input, long output) {
        if (price.pricingState() == UsagePricingState.NO_CHARGE) return BigDecimal.ZERO;
        if (price.pricingState() == UsagePricingState.UNPRICED)
            throw new ScanModelUnavailableException("A scan cannot reserve an unpriced model");
        return inputRate(price)
                .multiply(BigDecimal.valueOf(input))
                .add(Objects.requireNonNullElse(price.per1mOutputUsd(), BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(output)))
                .movePointLeft(6);
    }

    static BigDecimal rate(LlmPriceSnapshot price) {
        if (price.pricingState() == UsagePricingState.NO_CHARGE) return BigDecimal.ZERO;
        if (price.pricingState() == UsagePricingState.UNPRICED)
            throw new ScanModelUnavailableException("A scan cannot reserve an unpriced model");
        return inputRate(price).max(Objects.requireNonNullElse(price.per1mOutputUsd(), BigDecimal.ZERO));
    }

    private static BigDecimal inputRate(LlmPriceSnapshot price) {
        var rate = Objects.requireNonNullElse(price.per1mInputUsd(), BigDecimal.ZERO);
        if (price.per1mCacheReadUsd() != null) rate = rate.max(price.per1mCacheReadUsd());
        if (price.per1mCacheWriteUsd() != null) rate = rate.max(price.per1mCacheWriteUsd());
        return rate;
    }
}
