package de.tum.cit.aet.hephaestus.practices.observation.trend.dto;

import de.tum.cit.aet.hephaestus.practices.observation.trend.OutcomeVector;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record OutcomeVectorDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int met,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int notMet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int notApplicable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int undetermined) {
    public static @Nullable OutcomeVectorDTO from(@Nullable OutcomeVector vector) {
        return vector == null
                ? null
                : new OutcomeVectorDTO(vector.met(), vector.notMet(), vector.notApplicable(), vector.undetermined());
    }
}
