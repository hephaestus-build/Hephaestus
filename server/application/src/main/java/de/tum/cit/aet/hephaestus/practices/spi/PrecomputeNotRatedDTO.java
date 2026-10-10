package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "Model calls that were not rated, for one reason")
public record PrecomputeNotRatedDTO(
        @NonNull PrecomputeNotRatedReason reason, @NonNull Integer count) {}
