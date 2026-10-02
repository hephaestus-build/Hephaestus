package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "Request to move a practice to a group or Unassigned",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BindPracticeGroupRequestDTO(
        @Schema(
                description = "Destination group slug; omit or set to null for Unassigned",
                nullable = true,
                example = "review-ready-work")
        @Nullable
        String groupSlug)
        implements ClosedPracticeInput {}
