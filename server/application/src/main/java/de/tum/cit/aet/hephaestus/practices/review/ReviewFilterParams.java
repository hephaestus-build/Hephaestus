package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ReviewFilterParams(
        @Nullable List<ReviewStatus> status,
        @Nullable ReviewKind kind,
        @Nullable UUID reviewId,
        @Nullable Long itemId,
        @Nullable String practiceSlug,
        @Nullable String groupSlug,
        @Nullable Instant from,
        @Nullable Instant to,
        @Nullable ReviewListSort sort,
        @Nullable ReviewQueueState queueState) {
    public ReviewFilterParams validated() {
        QueryFilterSupport.requireWindow(from, to);
        return this;
    }
}
