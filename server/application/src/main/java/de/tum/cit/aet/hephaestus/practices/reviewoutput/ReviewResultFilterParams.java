package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueryFilter;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

public record ReviewResultFilterParams(
        @Nullable UUID reviewId,
        @Positive @Nullable Long itemId,
        @Nullable String itemKind,
        @Nullable String practiceSlug,
        @Nullable String groupSlug,
        @Nullable List<ResultOutcome> outcome,
        @Nullable List<Severity> severity,
        @Nullable Currentness currentness,
        @Nullable Boolean markedIncorrect,
        @Nullable Boolean accepted,
        @Nullable Boolean disagreed,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant to) {
    public ResultQueryFilter toFilter() {
        QueryFilterSupport.requireWindow(from, to);
        if (itemKind != null) new ItemKind(itemKind);
        return new ResultQueryFilter(
                reviewId,
                itemId,
                itemKind,
                practiceSlug,
                groupSlug,
                // A staff read never selects a person (ADR 0125).
                null,
                outcome,
                severity,
                currentness,
                markedIncorrect,
                accepted,
                disagreed,
                from,
                to);
    }
}
