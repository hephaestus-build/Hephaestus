package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Split;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "How the developers with a standing split across one practice group or one practice, counted in"
                + " developers: a part per verdict, none yet, and their total")
public record WorkspaceSplitDTO(
        @NonNull
        @Schema(
                description =
                        "Developers at each verdict, Needs attention, Mixed feedback and Going well in that order")
        List<WorkspaceSplitPartDTO> parts,

        @NonNull @Schema(description = "Developers with a standing in a group shown but none here")
        Integer noneYet,

        @NonNull
        @Schema(
                description = "Every developer the split counts, the parts and none yet together, the reader included"
                        + " when counted")
        Integer developers) {

    public static WorkspaceSplitDTO from(Split split) {
        return new WorkspaceSplitDTO(
                split.parts().stream()
                        .map(part -> new WorkspaceSplitPartDTO(part.standing(), part.developers()))
                        .toList(),
                split.noneYet(),
                split.developers());
    }
}
