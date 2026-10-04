package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.PracticesAcrossWorkspaceWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "How the workspace's developers with a standing split across the practice groups, counted in"
                + " developers and never naming one, with the reader's own figures beside the workspace's")
public record PracticesAcrossWorkspaceDTO(
        @NonNull
        @Schema(
                description = "The window the tiles read evidence over; the splits read every developer's current"
                        + " standing, whatever the window")
        PracticesAcrossWorkspaceWindow window,

        @NonNull @Schema(description = "The fewest developers other than the reader a shown count stands for")
        Integer minimumOthers,

        @Nullable
        @Schema(
                description = "Eligible developers with a current standing in a practice group shown, the"
                        + " developers every split counts; absent while fewer than minimumOthers of them are other"
                        + " than the reader")
        Integer developersWithAStanding,

        @NonNull
        @Schema(
                description = "Whether the reader is one of the developers with a current standing and so inside"
                        + " the splits")
        Boolean readerCounted,

        @Nullable
        @Schema(
                description = "Eligible developers with a standing in a practice group shown in the window, the"
                        + " developers the tiles compare; absent while fewer than minimumOthers of them are other"
                        + " than the reader")
        Integer developersWithAStandingInWindow,

        @NonNull
        @Schema(description = "The practices the reader's profile lists, the denominator of the practice tiles")
        Integer yourPractices,

        @NonNull @Schema(description = "Pieces of the reader's work reviewed in the window")
        WorkspaceTileDTO reviewedWork,

        @NonNull @Schema(description = "The reader's practices going well")
        WorkspaceTileDTO practicesGoingWell,

        @NonNull @Schema(description = "The reader's practices needing attention")
        WorkspaceTileDTO practicesNeedingAttention,

        @NonNull
        @Schema(
                description = "The reader's open feedback, counted by the rule the practice profile shows it open by,"
                        + " beside the middle half of every eligible developer's: both open now, whatever the window")
        WorkspaceTileDTO openFeedback,

        @NonNull @Schema(description = "One row per practice group shown on the practice pages, in catalog order")
        List<WorkspaceGroupSplitDTO> groups) {}
