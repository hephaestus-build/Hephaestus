package de.tum.cit.aet.hephaestus.practices.spi;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ReviewUsageSnapshot(
        @Nullable BigDecimal costUsd,
        boolean priced,
        long calls,
        long inputTokens,
        long outputTokens,
        List<ReviewUsageAttempt> attempts) {
    /** Whether {@link #costUsd} counts the upper bound of a call whose usage was not measured. */
    public boolean includesUpperBound() {
        return attempts.stream().anyMatch(ReviewUsageAttempt::includesUpperBound);
    }

    public ReviewUsageSnapshot {
        attempts = List.copyOf(attempts);
    }
}
