package de.tum.cit.aet.hephaestus.core.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many rows a {@link CursorPageDTO} list holds. A cursor page does not count its list. */
@Schema(description = "How many rows match")
public record TotalCountDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long total) {}
