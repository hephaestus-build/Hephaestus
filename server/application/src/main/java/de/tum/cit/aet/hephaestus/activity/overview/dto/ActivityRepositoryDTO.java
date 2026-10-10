package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.jspecify.annotations.NonNull;

/** The full provider path avoids collisions between repositories with the same name. */
public record ActivityRepositoryDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) long id,
        @NonNull String key,
        @NonNull String name) {}
