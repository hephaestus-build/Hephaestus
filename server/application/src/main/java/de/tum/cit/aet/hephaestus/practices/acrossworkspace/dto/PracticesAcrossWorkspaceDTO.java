package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "How the workspace's developers with a current standing split across the practice groups,"
                + " counted in developers and never naming one, and the open feedback beside the reader's own. Nothing"
                + " here reads a window; the tiles that do are read on their own")
public record PracticesAcrossWorkspaceDTO(
        @NonNull @Schema(description = "The fewest developers other than the reader a shown count stands for")
        Integer minimumOthers,

        @NonNull
        @Schema(
                description = "The reader's open feedback, counted by the rule the practice profile shows it open by,"
                        + " beside the middle half of every eligible developer's: both open now")
        WorkspaceTileDTO openFeedback,

        @NonNull @Schema(description = "One row per practice group shown on the practice pages, in catalog order")
        List<WorkspaceGroupSplitDTO> groups) {}
