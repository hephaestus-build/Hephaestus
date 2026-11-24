package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.core.PeopleCount;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The middle half of the developers counted: the 25th to the 75th percentile")
public record WorkspaceRangeDTO(
        @PeopleCount(exactBecause = QUARTILE)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The 25th percentile")
        int low,

        @PeopleCount(exactBecause = QUARTILE)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The 75th percentile")
        int high) {

    /** Why the two ends may show exactly. */
    static final String QUARTILE = "A quartile of other developers' figures, rounded; WorkspaceSplits.middleHalf builds"
            + " a range only from at least 5 of them, and never one whose ends meet";
}
