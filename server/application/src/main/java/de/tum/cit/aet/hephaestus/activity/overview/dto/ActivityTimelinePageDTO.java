package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One page of activity, newest first")
public record ActivityTimelinePageDTO(
        @NonNull @Schema(description = "The activity on this page, newest first")
        List<ActivityItemDTO> content,

        @Nullable
        @Schema(
                description = "Opaque cursor that fetches the next page when passed back as cursor; absent on the"
                        + " last page")
        String nextCursor) {}
