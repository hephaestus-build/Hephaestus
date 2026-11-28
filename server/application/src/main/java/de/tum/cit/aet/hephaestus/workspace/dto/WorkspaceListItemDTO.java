package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceListData;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
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

        @NonNull @Schema(description = "Current lifecycle status of the workspace")
        WorkspaceStatus status,

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
        Boolean publishesPublicActivity,

        @NonNull
        @Schema(
                description = "Hosts that a link in model-written text may point to in this workspace: Hephaestus and"
                        + " the workspace's providers. A host that starts with a dot also allows its subdomains."
                        + " Any other link shows as text.")
        Set<String> linkHosts) {
    public static WorkspaceListItemDTO from(
            WorkspaceListData workspace,
            @Nullable IdentityProviderType providerType,
            String workspaceAddress,
            boolean instanceAllowsPublicActivity,
            Set<String> linkHosts) {
        return new WorkspaceListItemDTO(
                workspace.id(),
                workspace.workspaceSlug(),
                workspaceAddress,
                workspace.displayName(),
                workspace.status(),
                workspace.accountLogin(),
                providerType,
                workspace.createdAt(),
                workspace.practicesEnabled(),
                instanceAllowsPublicActivity
                        && workspace.status() == WorkspaceStatus.ACTIVE
                        && Boolean.TRUE.equals(workspace.publicActivityEnabled()),
                linkHosts);
    }
}
