package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.SmallCounts;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.PracticesAcrossWorkspaceWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(
        description = "The reader's figures over one window beside the middle half of the developers with a standing"
                + " in it")
public record PracticesAcrossWorkspaceTilesDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The window the tiles read evidence over")
        PracticesAcrossWorkspaceWindow window,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Developers with a standing in a practice group shown in the window, the developers the"
                        + " tiles compare. Null for fewer than "
                        + SmallCounts.MINIMUM)
        @Nullable
        Integer developersWithAStandingInWindow,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The practices the reader's profile lists, the denominator of the practice tiles")
        int yourPractices,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether a counted standing in the window was read only from earlier reviews")
        boolean includesEarlierReviews,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Pieces of the reader's work reviewed in the window")
        WorkspaceTileDTO reviewedWork,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The reader's practices going well")
        WorkspaceTileDTO practicesGoingWell,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The reader's practices needing attention")
        WorkspaceTileDTO practicesNeedingAttention) {}
