package de.tum.cit.aet.hephaestus.workspace.settings.dto;

import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettings;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * DTO representing workspace-scoped team repository settings.
 *
 * @param workspaceId the workspace ID these settings belong to
 * @param teamId the team ID these settings apply to
 * @param repositoryId the repository ID these settings apply to
 * @param hiddenFromContributions whether contributions from this repository are hidden
 */
@Schema(description = "Repository contribution visibility settings for a specific team within a workspace")
public record WorkspaceTeamRepositorySettingsDTO(
        @NonNull @Schema(description = "The workspace ID these settings belong to")
        Long workspaceId,

        @NonNull @Schema(description = "The team ID these settings apply to")
        Long teamId,

        @NonNull @Schema(description = "The repository ID these settings apply to")
        Long repositoryId,

        @NonNull @Schema(description = "Whether activity in this repository is left out of the team's activity")
        Boolean hiddenFromContributions) {
    /**
     * Creates a DTO from the entity.
     *
     * @param settings the entity to convert
     * @return the DTO representation
     */
    public static WorkspaceTeamRepositorySettingsDTO from(WorkspaceTeamRepositorySettings settings) {
        return new WorkspaceTeamRepositorySettingsDTO(
                settings.getId().getWorkspaceId(),
                settings.getId().getTeamId(),
                settings.getId().getRepositoryId(),
                settings.isHiddenFromContributions());
    }

    /**
     * Creates a default (not hidden from contributions) DTO for when no settings exist.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @param repositoryId the repository ID
     * @return the default DTO with hiddenFromContributions=false
     */
    public static WorkspaceTeamRepositorySettingsDTO defaultSettings(Long workspaceId, Long teamId, Long repositoryId) {
        return new WorkspaceTeamRepositorySettingsDTO(workspaceId, teamId, repositoryId, false);
    }
}
