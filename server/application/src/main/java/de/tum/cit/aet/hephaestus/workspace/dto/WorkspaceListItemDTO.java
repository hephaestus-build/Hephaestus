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

        @NonNull @Schema(description = "Canonical workspace address. Uses the apex path when subdomains are off.")
        String workspaceAddress,

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

        @NonNull @Schema(description = "Whether practice reviews are on")
        Boolean practicesEnabled,

        @NonNull
        @Schema(
                description = "Whether the public activity page is live: the instance allows it and the workspace"
                        + " turned it on")
        Boolean publishesPublicActivity) {
    public static WorkspaceListItemDTO from(
            Workspace workspace,
            ConnectionService connectionService,
            String workspaceAddress,
            boolean instanceAllowsPublicActivity) {
        IdentityProviderType providerType = connectionService
                .findActiveProviderKind(workspace.getId())
                .map(IdentityProviderType::from)
                .orElse(null);
        return new WorkspaceListItemDTO(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspaceAddress,
                workspace.getDisplayName(),
                workspace.getStatus().name(),
                workspace.getAccountLogin(),
                providerType,
                workspace.getCreatedAt(),
                workspace.getFeatures().getPracticesEnabled(),
                instanceAllowsPublicActivity
                        && workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE
                        && Boolean.TRUE.equals(workspace.getPublicActivityEnabled()));
    }
}
