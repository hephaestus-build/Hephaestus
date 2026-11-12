package de.tum.cit.aet.hephaestus.integration.slack.preferences;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record SlackUserWorkspacePreferencesDTO(
        @NonNull String workspaceSlug,
        @NonNull String workspaceName,
        @NonNull String slackTeamId,
        @Nullable String slackTeamName,
        boolean channelMessagesAllowed,
        long activeMonitoredChannelCount) {}
