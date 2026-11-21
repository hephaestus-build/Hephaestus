package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(
        description = "How the developers with a standing split across one practice group or one practice, counted in"
                + " developers: a part per verdict, none yet, and their total. A split with a part of 1 or 2, or with"
                + " every developer in one part, shows only its total")
public record WorkspaceSplitDTO(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description =
                        "Developers at each verdict, Needs attention, Mixed feedback and Going well in that order")
        List<WorkspaceSplitPartDTO> parts,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description =
                        "Developers with a standing in a group shown but none here. Null when the split shows only"
                                + " its total")
        @Nullable
        Integer noneYet,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Every developer the split counts, the parts and none yet together, the reader included"
                        + " when counted. Null for fewer than 3")
        @Nullable
        Integer developers) {
    public WorkspaceSplitDTO {
        parts = List.copyOf(parts);
    }
}
