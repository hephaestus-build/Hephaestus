package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendDirection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.TrendSupportDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description =
                "One practice group: the reader's own standing and how the developers with a standing split across it")
public record WorkspaceGroupSplitDTO(
        @NonNull @Schema(description = "Group slug") String groupSlug,
        @NonNull @Schema(description = "Group name") String groupName,
        @Nullable @Schema(description = "Group icon name") String groupIcon,
        @Nullable @Schema(description = "Group colour name") String groupColor,

        @NonNull @Schema(description = "The reader's own standing in the group, shown in every shape")
        Standing yourStanding,

        @Nullable @Schema(description = "The direction of the reader's own standing in the group, read over the window")
        TrendDirection yourDirection,

        @Nullable @Schema(description = "Evidence support and provenance for the reader's direction")
        TrendSupportDTO yourTrendSupport,

        @NonNull @Schema(description = "How the developers with a standing split across the group")
        WorkspaceSplitDTO split,

        @NonNull
        @Schema(
                description = "The group's practices review is admitted for, the same for every reader, each split"
                        + " on its own, in catalog order")
        List<WorkspacePracticeSplitDTO> practices) {}
