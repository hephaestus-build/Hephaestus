package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
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

        @NonNull @Schema(description = "Human-readable name of the workspace")
        String displayName,

        @NonNull @Schema(description = "Whether the workspace is publicly viewable without authentication")
        Boolean isPubliclyViewable,

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

        @Schema(description = "Custom server URL for self-hosted instances (null for cloud defaults)") @Nullable
        String serverUrl,

        @NonNull @Schema(description = "Timestamp when the workspace was created")
        Instant createdAt,

        @NonNull @Schema(description = "Timestamp when the workspace was last updated")
        Instant updatedAt,

        @Schema(description = "Timestamp when the GitHub App installation was linked") @Nullable
        Instant installationLinkedAt,

        @Schema(description = "Day of week for leaderboard notifications (1=Monday, 7=Sunday)", example = "1") @Nullable
        Integer leaderboardScheduleDay,

        @Schema(description = "Time for leaderboard notifications in HH:mm format", example = "09:00") @Nullable
        String leaderboardScheduleTime,

        @Schema(description = "Whether leaderboard notifications are enabled")
        Boolean leaderboardNotificationEnabled,

        @Schema(description = "Team name for leaderboard notifications") @Nullable
        String leaderboardNotificationTeam,

        @Schema(description = "Slack channel ID for leaderboard notifications") @Nullable
        String leaderboardNotificationChannelId,

        @NonNull @Schema(description = "Whether a Personal Access Token is configured")
        Boolean hasPersonalAccessToken,

        @NonNull @Schema(description = "Whether Slack token is configured")
        Boolean hasSlackToken,

        @Schema(description = "ID of the active Slack connection, if any — addresses PATCH /connections/{id}/status")
        @Nullable
        Long slackConnectionId,

        @NonNull @Schema(description = "Whether a GitLab webhook has been auto-registered for this workspace")
        Boolean gitlabWebhookRegistered,

        @NonNull @Schema(description = "Whether the practice review feature is enabled")
        Boolean practicesEnabled,

        @NonNull @Schema(description = "Whether the Pi mentor chat feature is enabled")
        Boolean mentorEnabled,

        @NonNull @Schema(description = "Whether the leaderboard is enabled")
        Boolean leaderboardEnabled,

        @NonNull @Schema(description = "Whether the league/progression system is enabled")
        Boolean progressionEnabled,

        @NonNull @Schema(description = "Whether league tiers and rankings are enabled")
        Boolean leaguesEnabled,

        @NonNull @Schema(description = "Whether automatic practice reviews triggered by PR events are enabled")
        Boolean practiceReviewAutoTriggerEnabled,

        @NonNull @Schema(description = "Whether manual practice reviews triggered via bot command are enabled")
        Boolean practiceReviewManualTriggerEnabled) {
    /** Builds a DTO pulling integration metadata from the Connection registry. */
    public static WorkspaceDTO from(Workspace workspace, ConnectionService connectionService) {
        long workspaceId = workspace.getId();

        var providerKind = connectionService.findActiveProviderKind(workspaceId);
        var gitHubApp = connectionService.findActiveGitHubAppConfig(workspaceId);
        var gitHubPat = connectionService.findActiveGitHubPatConfig(workspaceId);
        var gitLab = connectionService.findActiveGitLabConfig(workspaceId);
        var slackCfg = connectionService.findSlackNotificationConfig(workspaceId);

        String serverUrl = gitLab.map(ConnectionConfig.GitLabConfig::serverUrl)
                .or(() -> gitHubApp.map(ConnectionConfig.GitHubAppConfig::serverUrl))
                .or(() -> gitHubPat.map(ConnectionConfig.GitHubPatConfig::serverUrl))
                .orElse(null);

        Long installationId =
                gitHubApp.map(ConnectionConfig.GitHubAppConfig::installationId).orElse(null);

        Instant installationLinkedAt = connectionService
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .filter(c -> c.getConfig() instanceof ConnectionConfig.GitHubAppConfig)
                .map(c -> c.getCreatedAt())
                .orElse(null);

        // Presence only: this view must not decrypt anything, or a credential the running key cannot
        // read takes the whole workspace page down with it.
        boolean hasPat = (gitHubPat.isPresent()
                        && storesCredentials(connectionService, workspaceId, IntegrationKind.GITHUB))
                || (gitLab.isPresent() && storesCredentials(connectionService, workspaceId, IntegrationKind.GITLAB));

        boolean hasSlackToken = storesCredentials(connectionService, workspaceId, IntegrationKind.SLACK);

        Long slackConnectionId = connectionService
                .findActive(workspaceId, IntegrationKind.SLACK)
                .map(c -> c.getId())
                .orElse(null);

        String leaderboardTeam = slackCfg.map(s -> s.teamLabel() != null ? s.teamLabel() : s.teamName())
                .orElse(null);
        String leaderboardChannelId = slackCfg.map(ConnectionConfig.SlackConfig::notificationChannelId)
                .orElse(null);

        boolean gitlabWebhookRegistered =
                gitLab.map(c -> c.gitlabWebhookId() != null).orElse(false);

        return new WorkspaceDTO(
                workspaceId,
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                workspace.getIsPubliclyViewable(),
                workspace.getStatus().name(),
                workspace.getAccountLogin(),
                installationId,
                providerKind.map(Enum::name).orElse(null),
                providerKind.map(IdentityProviderType::from).orElse(null),
                serverUrl,
                workspace.getCreatedAt(),
                workspace.getUpdatedAt(),
                installationLinkedAt,
                workspace.getLeaderboardScheduleDay(),
                workspace.getLeaderboardScheduleTime(),
                workspace.getLeaderboardNotificationEnabled(),
                leaderboardTeam,
                leaderboardChannelId,
                hasPat,
                hasSlackToken,
                slackConnectionId,
                gitlabWebhookRegistered,
                workspace.getFeatures().getPracticesEnabled(),
                workspace.getFeatures().getMentorEnabled(),
                workspace.getFeatures().getLeaderboardEnabled(),
                workspace.getFeatures().getProgressionEnabled(),
                workspace.getFeatures().getLeaguesEnabled(),
                workspace.getFeatures().getPracticeReviewAutoTriggerEnabled(),
                workspace.getFeatures().getPracticeReviewManualTriggerEnabled());
    }

    private static boolean storesCredentials(
            ConnectionService connectionService, long workspaceId, IntegrationKind kind) {
        return connectionService
                .findActive(workspaceId, kind)
                .map(Connection::hasCredentials)
                .orElse(false);
    }
}
