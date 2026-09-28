package de.tum.cit.aet.hephaestus.workspace.settings.dto;

import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * DTO representing workspace-scoped team settings.
 *
 * @param workspaceId the workspace ID these settings belong to
 * @param teamId the team ID these settings apply to
 * @param hidden whether the team is hidden from workspace activity
 */
@Schema(description = "Team visibility settings for a specific workspace")
public record WorkspaceTeamSettingsDTO(
        @NonNull @Schema(description = "The workspace ID these settings belong to")
        Long workspaceId,

        @NonNull @Schema(description = "The team ID these settings apply to")
        Long teamId,

        @NonNull @Schema(description = "Whether the team is hidden from workspace activity")
        Boolean hidden) {
    /**
     * Creates a DTO from the entity.
     *
     * @param settings the entity to convert
     * @return the DTO representation
     */
    public static WorkspaceTeamSettingsDTO from(WorkspaceTeamSettings settings) {
        return new WorkspaceTeamSettingsDTO(
                settings.getId().getWorkspaceId(), settings.getId().getTeamId(), settings.isHidden());
    }

    /**
     * Creates a default (non-hidden) DTO for when no settings exist.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @return the default DTO with hidden=false
     */
    public static WorkspaceTeamSettingsDTO defaultSettings(Long workspaceId, Long teamId) {
        return new WorkspaceTeamSettingsDTO(workspaceId, teamId, false);
    }
}
