package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "One practice of a group: the reader's own standing and how the developers with a standing split")
public record WorkspacePracticeSplitDTO(
        @NonNull @Schema(description = "Practice slug") String practiceSlug,
        @NonNull @Schema(description = "Practice name") String practiceName,

        @NonNull
        @Schema(
                description = "The reader's own standing in the practice",
                allowableValues = {"DEVELOPING", "STRENGTH", "MIXED", "NOT_OBSERVED", "NO_OPPORTUNITY"})
        Standing yourStanding,

        @NonNull @Schema(description = "How the developers with a standing split across the practice")
        WorkspaceSplitDTO split) {}
