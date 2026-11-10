package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewBatchSpend {
    private static final BigDecimal LEAST = new BigDecimal("0.0001");

    private final ReviewRepository reviews;
    private final ReviewRuntimeQuery runtime;

    /**
     * An unpriced attempt is not $0, so it leaves the remaining budget unknown. Priced spend only grows when the
     * unknown part gets a price, so priced spend that already reached the budget exhausts it either way.
     */
    public Snapshot remaining(ReviewBatch batch) {
        var budget = batch.getBudgetUsd();
        if (budget == null) return new Snapshot(null, true);
        var ids = reviews.findByWorkspaceIdAndBatchIdOrderByCreatedAtAsc(batch.getWorkspaceId(), batch.getId()).stream()
                .map(Review::getId)
                .collect(Collectors.toUnmodifiableSet());
        BigDecimal spent = BigDecimal.ZERO;
        boolean unpriced = false;
        for (var usage : runtime.usageByReview(batch.getWorkspaceId(), ids).values())
            for (var attempt : usage.attempts()) {
                var cost = attempt.costUsd();
                if (cost == null || attempt.pricingState() == UsagePricingState.UNPRICED) unpriced = true;
                else spent = spent.add(cost);
            }
        var remaining = budget.subtract(spent).max(BigDecimal.ZERO).setScale(4, RoundingMode.FLOOR);
        return unpriced && remaining.compareTo(LEAST) >= 0 ? new Snapshot(null, false) : new Snapshot(remaining, true);
    }

    public record Snapshot(@Nullable BigDecimal remainingUsd, boolean verified) {
        public boolean exhausted() {
            return !verified || (remainingUsd != null && remainingUsd.compareTo(LEAST) < 0);
        }
    }
}
