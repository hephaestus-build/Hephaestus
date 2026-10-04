package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One practice group: how the developers with a standing split across it, and where the reader is")
public record WorkspaceGroupSplitDTO(
        @NonNull @Schema(description = "Group slug") String groupSlug,
        @NonNull @Schema(description = "Group name") String groupName,
        @Nullable @Schema(description = "Group icon name") String groupIcon,
        @Nullable @Schema(description = "Group colour name") String groupColor,

        @Nullable
        @Schema(
                description = "The reader's current standing in the group, the one their practice profile shows:"
                        + " the part they are marked in. Absent unless the split shows its parts and counts the"
                        + " reader")
        Standing yourStanding,

        @NonNull @Schema(description = "How the developers with a standing split across the group")
        WorkspaceSplitDTO split,

        @NonNull
        @Schema(
                description = "The group's practices review is admitted for, the same for every reader, each split"
                        + " on its own, in catalog order")
        List<WorkspacePracticeSplitDTO> practices) {}
