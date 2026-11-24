package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.core.SmallCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        description = "How the workspace's developers with a current standing split across the practice groups,"
                + " counted in developers and never naming one, and the open feedback beside the reader's own. Nothing"
                + " here reads a window; the tiles that do are read on their own")
public record PracticesAcrossWorkspaceDTO(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The reader's open feedback, open now, counted by the rule the practice profile shows it"
                        + " open by. It has no middle half: whether another person's card is open follows a delivery"
                        + " rule that staff can change for one person")
        WorkspaceTileDTO openFeedback,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether at least "
                        + SmallCounts.MINIMUM
                        + " counted developers have a standing read only from earlier reviews, which carry no current"
                        + " proof")
        boolean includesEarlierReviews,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "One row per practice group shown on the practice pages with a practice review is"
                        + " admitted for, in catalog order")
        List<WorkspaceGroupSplitDTO> groups) {
    public PracticesAcrossWorkspaceDTO {
        groups = List.copyOf(groups);
    }
}
