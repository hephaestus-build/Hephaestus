package de.tum.cit.aet.hephaestus.workspace.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for updating team visibility settings in a workspace.
 *
 * @param hidden whether the team should be hidden from workspace activity
 */
@Schema(description = "Request to update team visibility settings in a workspace")
public record UpdateTeamSettingsRequestDTO(
        @NotNull(message = "hidden is required")
        @Schema(description = "Whether the team should be hidden from workspace activity")
        Boolean hidden) {}
