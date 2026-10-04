package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "How the developers with a standing split across one practice group or one practice, counted in"
                + " developers: a part per verdict and none yet, set only for SPLIT, and their total, set for SPLIT and"
                + " TOTAL_ONLY")
public record WorkspaceSplitDTO(
        @NonNull @Schema(description = "How the split may be shown")
        Shape shape,

        @NonNull
        @Schema(
                description = "Developers at each verdict, Needs attention, Mixed feedback and Going well in that"
                        + " order; empty unless SPLIT")
        List<WorkspaceSplitPartDTO> parts,

        @Nullable @Schema(description = "Developers with a standing in a group shown but none here; set only for SPLIT")
        Integer noneYet,

        @Nullable
        @Schema(
                description = "Every developer the split counts, the parts and none yet together, the reader included"
                        + " when counted; set for SPLIT and TOTAL_ONLY")
        Integer developers) {

    public static WorkspaceSplitDTO from(Split split) {
        return new WorkspaceSplitDTO(
                split.shape(),
                split.parts().stream()
                        .map(part -> new WorkspaceSplitPartDTO(part.standing(), part.developers()))
                        .toList(),
                split.noneYet(),
                split.developers());
    }
}
