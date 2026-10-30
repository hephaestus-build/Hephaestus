package de.tum.cit.aet.hephaestus.practices.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.MemberReviewReadinessDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticeProfileOverviewDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OverviewWindowDTO window,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ProfileReviewRefDTO latestReview,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProfileChangeDTO> changes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ItemRefDTO> reviewedItems,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether and how this workspace reviews the reader's work; null when the request"
                        + " names no account, so no member's readiness can be stated")
        @Nullable
        MemberReviewReadinessDTO reviewReadiness,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The newest queued or running review of a piece of work whose subject is the reader"
                        + " and that has recorded no result about the reader yet; at is when it was ordered. Null"
                        + " when there is none, or when the reader may not see the work")
        @Nullable
        ProfileReviewRefDTO activeReview) {
    public PracticeProfileOverviewDTO {
        changes = List.copyOf(changes);
        reviewedItems = List.copyOf(reviewedItems);
    }
}
