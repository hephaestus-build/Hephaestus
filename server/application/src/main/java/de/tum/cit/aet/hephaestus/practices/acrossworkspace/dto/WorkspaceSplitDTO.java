package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "How the observed developers split across one practice group or one practice, counted in"
                + " developers; every count is absent outside the shape that shows it")
public record WorkspaceSplitDTO(
        @NonNull @Schema(description = "How the split may be shown")
        Shape shape,

        @Nullable @Schema(description = "Developers at Needs attention; set only for SPLIT")
        Integer needsAttention,

        @Nullable @Schema(description = "Developers at Mixed feedback; set only for SPLIT")
        Integer mixedFeedback,

        @Nullable @Schema(description = "Developers at Going well; set only for SPLIT")
        Integer goingWell,

        @Nullable @Schema(description = "Developers with a standing; set only for COLLAPSED")
        Integer hasStanding,

        @Nullable @Schema(description = "Observed developers without one; set for SPLIT and COLLAPSED")
        Integer noneYet) {

    public static WorkspaceSplitDTO from(Split split) {
        return new WorkspaceSplitDTO(
                split.shape(),
                split.needsAttention(),
                split.mixedFeedback(),
                split.goingWell(),
                split.hasStanding(),
                split.noneYet());
    }
}
