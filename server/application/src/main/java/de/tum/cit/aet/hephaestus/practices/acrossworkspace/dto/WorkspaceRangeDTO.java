package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "The middle half of the developers counted: the 25th to the 75th percentile")
public record WorkspaceRangeDTO(
        @NonNull @Schema(description = "The 25th percentile")
        Integer low,

        @NonNull @Schema(description = "The 75th percentile")
        Integer high) {}
