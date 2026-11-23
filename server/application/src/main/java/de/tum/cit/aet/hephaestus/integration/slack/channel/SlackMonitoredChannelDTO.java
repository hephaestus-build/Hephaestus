package de.tum.cit.aet.hephaestus.integration.slack.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Admin control-plane view of one allow-listed Slack channel: its discovery identity and its current consent state.
 * It carries no count of people who opted out: an admin could change their own choice and compare. This is the
 * surface the webapp activation panel lists, activates, pauses, revokes, and audits.
 */
@Schema(description = "An allow-listed Slack channel with its consent state")
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SlackMonitoredChannelDTO(
        @NonNull
        @Schema(
                description = "Internal allow-list row id",
                type = "string",
                pattern = "^[1-9][0-9]*$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String id,

        @NonNull @Schema(description = "Slack team (workspace) id the channel belongs to")
        String slackTeamId,

        @NonNull @Schema(description = "Slack channel id (stable C…/G… id; the natural key)")
        String slackChannelId,

        @Schema(description = "Human-readable channel name, if known") @Nullable
        String channelName,

        @NonNull @Schema(description = "Current per-channel consent lifecycle state")
        ConsentState consentState,

        @Schema(description = "When the in-channel consent announcement was posted (stamped on first activation)")
        @Nullable
        Instant consentAnnouncedAt,

        @NonNull @Schema(description = "When the channel was first discovered / allow-listed")
        Instant createdAt) {
    public static SlackMonitoredChannelDTO from(SlackMonitoredChannel channel) {
        return new SlackMonitoredChannelDTO(
                channel.getId().toString(),
                channel.getSlackTeamId(),
                channel.getSlackChannelId(),
                channel.getChannelName(),
                channel.getConsentState(),
                channel.getConsentAnnouncedAt(),
                channel.getCreatedAt());
    }
}
