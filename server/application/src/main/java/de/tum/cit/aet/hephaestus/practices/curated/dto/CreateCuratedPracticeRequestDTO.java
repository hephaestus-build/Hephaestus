package de.tum.cit.aet.hephaestus.practices.curated.dto;

import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "Request to add a practice to the instance catalog",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateCuratedPracticeRequestDTO(
        @NonNull @CuratedSlug @Schema(example = "pr-description-quality")
        String slug,

        @NonNull @NotNull @Valid CuratedPracticeRequestDTO definition)
        implements ClosedPracticeInput {}
