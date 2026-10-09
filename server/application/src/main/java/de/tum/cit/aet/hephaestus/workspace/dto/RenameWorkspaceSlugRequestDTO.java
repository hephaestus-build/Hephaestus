package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlug;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to rename a workspace's URL slug")
public record RenameWorkspaceSlugRequestDTO(
        @NotBlank(message = "New slug is required")
        @WorkspaceSlug
        @Schema(
                description = "Non-reserved lowercase ASCII DNS label. Consecutive hyphens are prohibited.",
                pattern = WorkspaceSlugValidator.LABEL_PATTERN,
                minLength = 1,
                maxLength = WorkspaceSlugValidator.MAX_LENGTH,
                example = "new-workspace-slug")
        String newSlug) {}
