package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Decision logic for Slack channel-lifecycle events (the bot removed, a channel archived/deleted/renamed),
 * keeping {@code consent_state} and channel metadata in step with Slack-side reality.
 *
 * <p>{@code channel_unarchive}/{@code group_unarchive} are deliberately NOT subscribed: resuming monitoring is a
 * decision only a workspace admin may make through the consent control plane, never an automatic reaction to the
 * channel becoming available again.
 *
 * <p>Every method is workspace-resolution-first and best-effort: an unresolved {@code team_id} (no ACTIVE Slack
 * connection) or a missing/blank channel id is a silent no-op, matching the guard-first
 * {@code *ForPlatformEvent} wrappers on {@link SlackChannelConsentService}. Nothing here ever throws back into
 * the inbox consumer.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelLifecycleService {

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackChannelConsentService consentService;

    public SlackChannelLifecycleService(
            SlackWorkspaceResolver workspaceResolver, SlackChannelConsentService consentService) {
        this.workspaceResolver = workspaceResolver;
        this.consentService = consentService;
    }

    /**
     * {@code channel_left} / {@code group_left}: Slack fires this for the app's own membership. Once the bot is no
     * longer in the channel it stops receiving messages, so an {@code ACTIVE} channel must pause — otherwise the
     * admin UI keeps reporting "monitoring on" for a channel nothing is being read from.
     */
    public void onBotRemoved(String teamId, JsonNode event) {
        withChannel(
                teamId,
                event,
                (workspaceId, channelId) ->
                        consentService.pauseForPlatformEvent(workspaceId, channelId, "bot removed from channel"));
    }

    /** {@code channel_archive} / {@code group_archive}: an archived channel receives no further messages. */
    public void onArchived(String teamId, JsonNode event) {
        withChannel(
                teamId,
                event,
                (workspaceId, channelId) ->
                        consentService.pauseForPlatformEvent(workspaceId, channelId, "channel archived"));
    }

    /**
     * {@code channel_deleted}: the channel and its data are gone on Slack's side, so erase our copy too rather than
     * leave a zombie allow-list row and lingering thread-aggregate PII.
     */
    public void onDeleted(String teamId, JsonNode event) {
        withChannel(
                teamId,
                event,
                (workspaceId, channelId) ->
                        consentService.revokeForPlatformEvent(workspaceId, channelId, "channel deleted in Slack"));
    }

    /**
     * {@code channel_rename} / {@code group_rename}: heal the stale {@code channel_name} so the admin UI and audit
     * trail do not show a name Slack no longer uses. Not a consent transition.
     */
    public void onRenamed(String teamId, JsonNode event) {
        Optional<Long> workspaceId = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceId.isEmpty()) {
            return;
        }
        JsonNode channel = event.path("channel");
        String id = channel.isObject() ? channel.path("id").asString("") : channel.asString("");
        String name = channel.isObject() ? channel.path("name").asString("") : "";
        if (id.isBlank()) {
            return;
        }
        consentService.renameChannel(workspaceId.get(), id, name);
    }

    @FunctionalInterface
    private interface ChannelAction {
        void apply(long workspaceId, String channelId);
    }

    private void withChannel(String teamId, JsonNode event, ChannelAction action) {
        Optional<Long> workspaceId = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceId.isEmpty()) {
            return;
        }
        String id = channelId(event);
        if (id.isBlank()) {
            return;
        }
        action.apply(workspaceId.get(), id);
    }

    /**
     * Defensively read the channel id off {@code event.channel}: a plain string for {@code channel_left} /
     * {@code group_left} / {@code channel_archive} / {@code group_archive} / {@code channel_unarchive} /
     * {@code channel_deleted}, or an object ({@code {id, name, created}}) for {@code channel_rename} /
     * {@code group_rename}.
     */
    private static String channelId(JsonNode event) {
        JsonNode channel = event.path("channel");
        if (channel.isObject()) {
            return channel.path("id").asString("");
        }
        return channel.asString("");
    }
}
