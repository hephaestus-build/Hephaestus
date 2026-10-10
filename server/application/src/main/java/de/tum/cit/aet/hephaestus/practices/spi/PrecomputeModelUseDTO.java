package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** One model that a precompute script declared, in one review attempt. */
@Schema(description = "One model a precompute script declared, and what became of its calls in one review")
public record PrecomputeModelUseDTO(
        @NonNull @Schema(description = "The purpose whose binding serves this model")
        PrecomputeModelPurpose purpose,

        @NonNull PrecomputeNeed need,

        @NonNull @Schema(description = "Whether the review had a model bound for this purpose")
        Boolean bound,

        @NonNull
        @Schema(description = "Calls to this model that were not rated, by reason. Empty when every call was rated.")
        List<PrecomputeNotRatedDTO> notRated) {}
