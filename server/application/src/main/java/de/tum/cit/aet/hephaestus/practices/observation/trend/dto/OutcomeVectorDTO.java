package de.tum.cit.aet.hephaestus.practices.observation.trend.dto;

import de.tum.cit.aet.hephaestus.practices.observation.trend.OutcomeVector;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record OutcomeVectorDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int demonstratedStrengths,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int safeAvoidances,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int commissionProblems,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int omissionGaps,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int notApplicable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int undetermined) {
    public static @Nullable OutcomeVectorDTO from(@Nullable OutcomeVector vector) {
        return vector == null
                ? null
                : new OutcomeVectorDTO(
                        vector.demonstratedStrengths(),
                        vector.safeAvoidances(),
                        vector.commissionProblems(),
                        vector.omissionGaps(),
                        vector.notApplicable(),
                        vector.undetermined());
    }
}
