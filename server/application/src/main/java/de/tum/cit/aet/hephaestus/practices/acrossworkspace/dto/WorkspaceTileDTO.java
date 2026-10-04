package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One figure: the reader's own value, then the middle half of the developers counted")
public record WorkspaceTileDTO(
        @NonNull @Schema(description = "The reader's own value")
        Integer yours,

        @Nullable @Schema(description = "The workspace's middle half; absent while too few developers are counted")
        WorkspaceRangeDTO middle) {

    public static WorkspaceTileDTO of(int yours, @Nullable MiddleHalf middle) {
        return new WorkspaceTileDTO(yours, middle == null ? null : new WorkspaceRangeDTO(middle.low(), middle.high()));
    }
}
