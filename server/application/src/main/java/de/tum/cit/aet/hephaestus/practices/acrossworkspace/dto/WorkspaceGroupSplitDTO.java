package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One practice group: the reader's own standing and how the observed developers split across it")
public record WorkspaceGroupSplitDTO(
        @NonNull @Schema(description = "Group slug") String groupSlug,
        @NonNull @Schema(description = "Group name") String groupName,
        @Nullable @Schema(description = "Group icon name") String groupIcon,
        @Nullable @Schema(description = "Group colour name") String groupColor,

        @NonNull @Schema(description = "The reader's own standing in the group, shown in every shape")
        Standing yourStanding,

        @NonNull @Schema(description = "How the split may be shown")
        Shape shape,

        @Nullable @Schema(description = "Developers at Needs attention; set only for SPLIT")
        Integer needsAttention,

        @Nullable @Schema(description = "Developers at Mixed feedback; set only for SPLIT")
        Integer mixedFeedback,

        @Nullable @Schema(description = "Developers at Going well; set only for SPLIT")
        Integer goingWell,

        @Nullable @Schema(description = "Developers with a standing; set only for COLLAPSED")
        Integer hasStanding,

        @Nullable @Schema(description = "Observed developers without one; set only for COLLAPSED")
        Integer noneYet) {}
