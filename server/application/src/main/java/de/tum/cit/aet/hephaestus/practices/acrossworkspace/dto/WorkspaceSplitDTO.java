package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.ShownCount;
import de.tum.cit.aet.hephaestus.core.SmallCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(
        description = "How the developers with a standing split across one practice group or one practice, counted in"
                + " developers: a part per verdict, none yet, and their total. A split with a part of 1 to "
                + (SmallCounts.MINIMUM - 1)
                + ", or with every developer in one part, shows only its total")
public record WorkspaceSplitDTO(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description =
                        "Developers at each verdict, Needs attention, Mixed feedback and Going well in that order")
        List<WorkspaceSplitPartDTO> parts,

        @Schema(
                implementation = Integer.class,
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Developers the page counts who have no verdict here. Null when the split shows only its"
                        + " total")
        @Nullable
        ShownCount noneYet,

        @Schema(
                implementation = Integer.class,
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Every developer the split counts, the parts and none yet together, the reader included"
                        + " when counted. Null for 1 to "
                        + (SmallCounts.MINIMUM - 1))
        @Nullable
        ShownCount developers) {
    public WorkspaceSplitDTO {
        parts = List.copyOf(parts);
    }
}
