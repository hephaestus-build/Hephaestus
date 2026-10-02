package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.PracticesAcrossWorkspaceWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "The reader's own practice group standings beside how the developers observed in the"
                + " workspace split across the same groups, counted in developers and never naming one")
public record PracticesAcrossWorkspaceDTO(
        @NonNull @Schema(description = "The window the evidence was read over")
        PracticesAcrossWorkspaceWindow window,

        @NonNull @Schema(description = "Start of the window")
        Instant since,

        @NonNull @Schema(description = "End of the window, the moment the page was read")
        Instant until,

        @NonNull @Schema(description = "The fewest developers other than the reader a shown count stands for")
        Integer minimumOthers,

        @NonNull @Schema(description = "Members practice review is eligible for, hidden members left out")
        Integer eligibleDevelopers,

        @NonNull @Schema(description = "Eligible developers with at least one practice standing in the window")
        Integer observedDevelopers,

        @NonNull @Schema(description = "Whether the reader is one of the observed developers and so inside the counts")
        Boolean readerCounted,

        @NonNull
        @Schema(description = "The practices the reader's profile lists, the denominator of the practice tiles")
        Integer yourPractices,

        @NonNull @Schema(description = "Pieces of the reader's work reviewed in the window")
        WorkspaceTileDTO reviewedWork,

        @NonNull @Schema(description = "The reader's practices going well")
        WorkspaceTileDTO practicesGoingWell,

        @NonNull @Schema(description = "The reader's practices needing attention")
        WorkspaceTileDTO practicesNeedingAttention,

        @NonNull @Schema(description = "One row per practice group shown on the practice pages, in catalog order")
        List<WorkspaceGroupSplitDTO> groups) {}
