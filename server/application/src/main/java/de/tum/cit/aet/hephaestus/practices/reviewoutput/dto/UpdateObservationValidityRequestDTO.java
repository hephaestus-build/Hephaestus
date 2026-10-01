package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

@Schema(description = "Invalidate an observation that was wrong when recorded, or restore it")
public record UpdateObservationValidityRequestDTO(
        @NonNull @NotNull @Schema(description = "false invalidates the observation; true restores it")
        Boolean valid,

        @NonNull
        @NotBlank
        @Size(max = ObservationInvalidation.MAX_REASON_LENGTH)
        @Schema(description = "Why, kept with the correction; an invalidation reason is shown to the developer")
        String reason) {}
