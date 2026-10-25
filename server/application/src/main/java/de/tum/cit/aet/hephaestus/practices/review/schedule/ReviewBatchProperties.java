package de.tum.cit.aet.hephaestus.practices.review.schedule;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fleet-wide limits on review-backfill batches.
 *
 * @param batchSize how many items one driver tick offers to the review path. Kept small because the
 *     usage ledger only learns a job's cost once it ends, and a budget cap is only checked between batches.
 * @param maxWindow the longest window a single batch may cover.
 * @param maxItems The largest number of items that one batch can be confirmed for. A larger batch is refused at
 *                 preflight.
 * @param costHistoryWindow how far back the estimator looks for completed reviews to derive a per-review
 *     cost from. Long enough to survive a quiet fortnight, short enough that a model change moves it.
 */
@ConfigurationProperties(prefix = "hephaestus.practice-review.backfill")
public record ReviewBatchProperties(
        @DefaultValue("25") int batchSize,
        @DefaultValue("400d") Duration maxWindow,
        @DefaultValue("5000") int maxItems,
        @DefaultValue("90d") Duration costHistoryWindow) {
    public ReviewBatchProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got " + batchSize);
        }
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive, got " + maxItems);
        }
    }
}
