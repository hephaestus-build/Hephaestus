package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One page of activity grouped by the pull request or issue it happened on, latest first")
public record ActivityWorkPageDTO(
        @NonNull @Schema(description = "The work on this page, by its latest activity, newest first")
        List<ActivityWorkDTO> content,

        @Nullable
        @Schema(
                description = "Opaque cursor that fetches the next page when passed back as cursor; absent on the"
                        + " last page")
        String nextCursor) {}
