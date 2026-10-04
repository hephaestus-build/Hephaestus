package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One figure: the reader's own value, then the middle half of the developers counted")
public record WorkspaceTileDTO(
        @NonNull @Schema(description = "The reader's own value")
        Integer yours,

        @Nullable @Schema(description = "Lower bound of the workspace's middle half; null when too few are counted")
        Integer middleLow,

        @Nullable @Schema(description = "Upper bound of the workspace's middle half; null when too few are counted")
        Integer middleHigh) {}
