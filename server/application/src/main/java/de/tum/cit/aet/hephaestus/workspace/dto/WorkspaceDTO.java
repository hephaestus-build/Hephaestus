package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionMetadataQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Complete workspace information including configuration and settings")
public record WorkspaceDTO(
        @NonNull @Schema(description = "Unique identifier of the workspace")
        Long id,

        @NonNull @Schema(description = "URL-friendly identifier for the workspace", example = "my-workspace")
        String workspaceSlug,

        @NonNull @Schema(description = "Canonical workspace address. Uses the apex path when subdomains are off.")
        String workspaceAddress,

        @NonNull @Schema(description = "Human-readable name of the workspace")
        String displayName,

        @NonNull @Schema(description = "Whether this workspace has enabled its public activity page")
        Boolean publicActivityEnabled,

        @NonNull @Schema(description = "Current lifecycle status of the workspace (PENDING, ACTIVE, ARCHIVED)")
        String status,

        @NonNull @Schema(description = "Git provider account login associated with this workspace")
        String accountLogin,

        @Schema(description = "GitHub App installation ID, if linked") @Nullable
        Long installationId,

        @Schema(description = "Integration kind backing this workspace (GITHUB or GITLAB)") @Nullable
        String kind,

        @Schema(description = "High-level git provider type for the workspace's SCM connection (null if none bound)")
        @Nullable
        IdentityProviderType providerType,

        @Schema(description = "Server URL of the workspace's SCM connection (null if none bound)") @Nullable
        String serverUrl,

        @NonNull @Schema(description = "Timestamp when the workspace was created")
        Instant createdAt,

        @NonNull @Schema(description = "Timestamp when the workspace was last updated")
        Instant updatedAt,

        @Schema(description = "Timestamp when the GitHub App installation was linked") @Nullable
        Instant installationLinkedAt,

        @NonNull @Schema(description = "Whether a Personal Access Token is configured")
        Boolean hasPersonalAccessToken,

        @NonNull @Schema(description = "Whether Slack token is configured")
        Boolean hasSlackToken,

        @Schema(description = "ID of the active Slack connection, if any — addresses PATCH /connections/{id}/status")
        @Nullable
        Long slackConnectionId,

        @NonNull @Schema(description = "Whether a GitLab webhook has been auto-registered for this workspace")
        Boolean gitlabWebhookRegistered,

        @NonNull @Schema(description = "Whether practice reviews are on")
        Boolean practicesEnabled,

        @NonNull @Schema(description = "Whether automatic practice reviews triggered by PR events are enabled")
        Boolean practiceReviewAutoTriggerEnabled,

        @NonNull @Schema(description = "Whether manual practice reviews triggered through a bot command are enabled")
        Boolean practiceReviewManualTriggerEnabled) {
    public static WorkspaceDTO from(
            Workspace workspace, ConnectionMetadataQuery.WorkspaceMetadata metadata, String workspaceAddress) {
        var kind = metadata.kind();
        return new WorkspaceDTO(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspaceAddress,
                workspace.getDisplayName(),
                workspace.getPublicActivityEnabled(),
                workspace.getStatus().name(),
                workspace.getAccountLogin(),
                metadata.installationId(),
                kind == null ? null : kind.name(),
                kind == null ? null : IdentityProviderType.from(kind),
                metadata.serverUrl(),
                workspace.getCreatedAt(),
                workspace.getUpdatedAt(),
                metadata.installationLinkedAt(),
                metadata.hasPersonalAccessToken(),
                metadata.hasSlackToken(),
                metadata.slackConnectionId(),
                metadata.gitlabWebhookRegistered(),
                workspace.getFeatures().getPracticesEnabled(),
                workspace.getFeatures().getPracticeReviewAutoTriggerEnabled(),
                workspace.getFeatures().getPracticeReviewManualTriggerEnabled());
    }
}
