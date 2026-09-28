package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "A team, by name")
public record TeamRefDTO(
        @NonNull @Schema(description = "Identifier of the team")
        Long id,

        @NonNull @Schema(description = "Name of the team", example = "Platform")
        String name) {}
