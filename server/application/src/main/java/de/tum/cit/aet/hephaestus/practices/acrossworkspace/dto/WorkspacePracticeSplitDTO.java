package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One practice of a group: how the developers with a standing split, and where the reader is")
public record WorkspacePracticeSplitDTO(
        @NonNull @Schema(description = "Practice slug") String practiceSlug,
        @NonNull @Schema(description = "Practice name") String practiceName,

        @Nullable
        @Schema(
                description = "The reader's current standing in the practice, the one their practice profile shows:"
                        + " the part they are marked in. Absent unless the split shows its parts and counts the"
                        + " reader")
        Standing yourStanding,

        @NonNull @Schema(description = "How the developers with a standing split across the practice")
        WorkspaceSplitDTO split) {}
