package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "How the workspace's developers with a current standing split across the practice groups,"
                + " counted in developers and never naming one, and the open feedback beside the reader's own. Nothing"
                + " here reads a window; the tiles that do are read on their own")
public record PracticesAcrossWorkspaceDTO(
        @NonNull @Schema(description = "The fewest developers other than the reader a shown count stands for")
        Integer minimumOthers,

        @Nullable
        @Schema(
                description = "Eligible developers with a current standing in a practice group shown, the"
                        + " developers every split counts; absent while too few to show")
        Integer developersWithAStanding,

        @NonNull
        @Schema(
                description = "Whether the reader is one of the developers with a current standing and so inside"
                        + " the splits")
        Boolean readerCounted,

        @NonNull
        @Schema(
                description = "The reader's open feedback, counted by the rule the practice profile shows it open by,"
                        + " beside the middle half of every eligible developer's: both open now")
        WorkspaceTileDTO openFeedback,

        @NonNull @Schema(description = "One row per practice group shown on the practice pages, in catalog order")
        List<WorkspaceGroupSplitDTO> groups) {}
