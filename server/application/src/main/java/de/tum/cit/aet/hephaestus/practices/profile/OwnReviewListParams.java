package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** The query parameters of the list of reviews with results about the reader. */
public record OwnReviewListParams(
        @Parameter(description = "Only work of these kinds") @Nullable
        List<String> itemKind,

        @Parameter(description = "Only the reviews of this work and, for a pull request, of the code reviews on it")
        @Nullable
        Long itemId,

        @Parameter(description = "Only reviews in these statuses") @Nullable
        List<ReviewStatus> status,

        @Nullable Instant since,

        @Parameter(description = "OLDEST lists the oldest review first; NEWEST, the default, the newest")
        @Schema(defaultValue = "NEWEST")
        @Nullable
        ReviewListOrder order,

        @Schema(defaultValue = "0") @Nullable Integer page,
        @Schema(defaultValue = "20") @Nullable Integer size) {
    public OwnReviewFilter filter() {
        return new OwnReviewFilter(itemKind, itemId, null, status, since, order == ReviewListOrder.OLDEST);
    }

    public Pageable pageable() {
        return PageRequest.of(page == null ? 0 : page, size == null ? 20 : size);
    }
}
