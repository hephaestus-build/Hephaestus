package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.PracticesAcrossWorkspaceWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "The reader's figures over one window beside the middle half of the developers with a standing"
                + " in it; each window is checked on its own")
public record PracticesAcrossWorkspaceTilesDTO(
        @NonNull @Schema(description = "The window the tiles read evidence over")
        PracticesAcrossWorkspaceWindow window,

        @NonNull
        @Schema(
                description = "The fewest developers a middle half is read over, the reader among them or not; below"
                        + " it a tile shows only the reader's own value")
        Integer minimumDevelopersForMiddleHalf,

        @Nullable
        @Schema(
                description = "Eligible developers with a standing in a practice group shown in the window, the"
                        + " developers the tiles compare; absent while too few to show")
        Integer developersWithAStandingInWindow,

        @NonNull
        @Schema(description = "The practices the reader's profile lists, the denominator of the practice tiles")
        Integer yourPractices,

        @NonNull @Schema(description = "Pieces of the reader's work reviewed in the window")
        WorkspaceTileDTO reviewedWork,

        @NonNull @Schema(description = "The reader's practices going well")
        WorkspaceTileDTO practicesGoingWell,

        @NonNull @Schema(description = "The reader's practices needing attention")
        WorkspaceTileDTO practicesNeedingAttention) {}
