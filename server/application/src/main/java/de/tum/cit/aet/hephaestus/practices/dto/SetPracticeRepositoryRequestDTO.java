package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** The monitored repository that holds the workspace's practices, and the directory of their folders in it. */
public record SetPracticeRepositoryRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$")
        @NotNull
        @Pattern(regexp = "^[1-9][0-9]*$")
        String repositoryItemId,

        @Schema(description = "A path relative to the repository root; .hephaestus/practices when absent")
        @Size(max = 255)
        @Pattern(
                regexp = "^(?!/)(?!.*(^|/)\\.\\.?(/|$))[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$",
                message = "is a path relative to the repository root, without . or .. segments")
        @Nullable
        String directory) {}
