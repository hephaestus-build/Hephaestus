package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.practices.result.ProblemQueryFilter;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueryFilter;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ReviewProblemFilterParams(
        @Nullable UUID reviewId,
        @Nullable Long itemId,
        @Nullable String itemKind,
        @Nullable String practiceSlug,
        @Nullable String groupSlug,
        @Nullable Long personId,
        @Nullable List<ResultOutcome> outcome,
        @Nullable List<Severity> severity,
        @Nullable Currentness currentness,
        @Nullable Boolean markedIncorrect,
        @Nullable Boolean accepted,
        @Nullable Boolean disagreed,
        @Nullable Instant from,
        @Nullable Instant to,
        @Nullable UUID resultId,
        @Nullable String path,
        @Nullable Boolean openOnly,
        @Nullable ReviewListSort sort) {
    public ResultQueryFilter toResultFilter() {
        return new ReviewResultFilterParams(
                        reviewId,
                        itemId,
                        itemKind,
                        practiceSlug,
                        groupSlug,
                        personId,
                        outcome,
                        null,
                        currentness,
                        markedIncorrect,
                        accepted,
                        disagreed,
                        from,
                        to)
                .toFilter();
    }

    public ProblemQueryFilter toProblemFilter() {
        return new ProblemQueryFilter(
                resultId, path, severity, openOnly == null || openOnly, sort == null ? ReviewListSort.NEWEST : sort);
    }
}
