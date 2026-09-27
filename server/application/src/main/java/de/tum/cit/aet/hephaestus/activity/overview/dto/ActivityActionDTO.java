package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityKind;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "How often one kind of activity happened on a piece of work")
public record ActivityActionDTO(
        @NonNull @Schema(description = "What was done") ActivityKind kind,

        @NonNull @Schema(description = "How often it was done", example = "3")
        Integer count) {}
