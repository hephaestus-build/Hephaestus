package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "Pull requests or issues, most recently updated first, up to a limit")
public record WorkItemListDTO(
        @NonNull @Schema(description = "The work, most recently updated first")
        List<WorkItemDTO> content,

        @NonNull @Schema(description = "Whether more work exists than the list holds")
        Boolean hasMore) {}
