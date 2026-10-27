package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackLocation;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFeedbackSort;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

public record FeedbackQueryFilter(
        @Nullable UUID reviewId,

        @Positive @Schema(type = "string", pattern = "^[1-9][0-9]*$") @Nullable
        Long itemId,

        @Nullable String itemKind,
        @Nullable String practiceSlug,
        @Nullable String groupSlug,

        @Positive @Schema(type = "string", pattern = "^[1-9][0-9]*$") @Nullable
        Long developerId,

        @Nullable List<FeedbackDestination> destination,
        @Nullable List<FeedbackState> state,
        @Nullable FeedbackLocation location,
        @Nullable List<FeedbackStopReason> stopReason,
        @Nullable Boolean withdrawn,
        @Nullable Boolean disagreed,
        @Nullable Boolean partlyDelivered,
        @Nullable ReviewFeedbackSort sort,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant to) {
    public FeedbackQueryFilter {
        state = state == null ? null : List.copyOf(state);
        destination = destination == null ? null : List.copyOf(destination);
        stopReason = stopReason == null ? null : List.copyOf(stopReason);
        QueryFilterSupport.requireWindow(from, to);
        if (itemKind != null) new ItemKind(itemKind);
        sort = sort == null ? ReviewFeedbackSort.NEWEST : sort;
    }
}
