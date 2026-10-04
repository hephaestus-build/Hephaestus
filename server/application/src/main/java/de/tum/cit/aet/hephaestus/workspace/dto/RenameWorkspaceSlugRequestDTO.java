package de.tum.cit.aet.hephaestus.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Request to rename a workspace's URL slug")
public record RenameWorkspaceSlugRequestDTO(
        @NotBlank(message = "New slug is required")
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9-]{2,50}$",
                message =
                        "Slug must be 3 to 51 characters, start with a lowercase letter or digit, and contain only lowercase letters, digits, or hyphens")
        @Schema(description = "New URL-friendly identifier for the workspace", example = "new-workspace-slug")
        String newSlug) {}
