package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Summary information about a workspace for list views")
public record WorkspaceListItemDTO(
        @NonNull @Schema(description = "Unique identifier of the workspace")
        Long id,

        @NonNull @Schema(description = "URL-friendly identifier for the workspace", example = "my-workspace")
        String workspaceSlug,

        @NonNull @Schema(description = "Human-readable name of the workspace")
        String displayName,

        @NonNull @Schema(description = "Current lifecycle status of the workspace (PENDING, ACTIVE, ARCHIVED)")
        String status,

        @NonNull @Schema(description = "Git provider account login associated with this workspace")
        String accountLogin,

        @Schema(description = "High-level git provider type (GITHUB or GITLAB), or null if no SCM connection bound")
        @Nullable
        IdentityProviderType providerType,

        @NonNull @Schema(description = "Timestamp when the workspace was created")
        Instant createdAt,

        @NonNull @Schema(description = "Whether the practice review feature is enabled")
        Boolean practicesEnabled,

        @NonNull @Schema(description = "Whether the Pi mentor chat feature is enabled")
        Boolean mentorEnabled,

        @NonNull @Schema(description = "Whether the leaderboard is enabled")
        Boolean leaderboardEnabled,

        @NonNull @Schema(description = "Whether the league/progression system is enabled")
        Boolean progressionEnabled,

        @NonNull @Schema(description = "Whether league tiers and rankings are enabled")
        Boolean leaguesEnabled) {
    public static WorkspaceListItemDTO from(Workspace workspace, ConnectionService connectionService) {
        IdentityProviderType providerType = connectionService
                .findActiveProviderKind(workspace.getId())
                .map(IdentityProviderType::from)
                .orElse(null);
        return new WorkspaceListItemDTO(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                workspace.getStatus().name(),
                workspace.getAccountLogin(),
                providerType,
                workspace.getCreatedAt(),
                workspace.getFeatures().getPracticesEnabled(),
                workspace.getFeatures().getMentorEnabled(),
                workspace.getFeatures().getLeaderboardEnabled(),
                workspace.getFeatures().getProgressionEnabled(),
                workspace.getFeatures().getLeaguesEnabled());
    }
}
