package de.tum.cit.aet.hephaestus.core.web;

import de.tum.cit.aet.hephaestus.core.PeopleCount;
import io.swagger.v3.oas.annotations.media.Schema;

/** How many rows a {@link CursorPageDTO} list holds. A cursor page does not count its list. */
@Schema(description = "How many rows match")
public record TotalCountDTO(
        @PeopleCount(exactBecause = "Rows of a list the reader can page through in full, each one named")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long total) {}
