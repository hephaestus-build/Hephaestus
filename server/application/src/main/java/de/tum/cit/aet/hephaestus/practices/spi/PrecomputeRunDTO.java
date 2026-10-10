package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "What one practice's precompute script did in one review")
public record PrecomputeRunDTO(
        @NonNull PrecomputeRunStatus status,

        @NonNull @Schema(description = "Places to check that the script gave the review; 0 when it did not run")
        Integer leads,

        @NonNull
        @Schema(
                description = "The models the script declared and what became of their calls. Empty for a script "
                        + "that declares none, and for one that ended before it declared them.")
        List<PrecomputeModelUseDTO> models) {}
