package de.tum.cit.aet.hephaestus.integration.slack.sync;

import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.ConversationLookup;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Nightly {@code conversations.info} pass over every non-REVOKED monitored channel: heals stale names and pauses an
 * ACTIVE channel Slack says we can no longer read (archived, deleted, or the bot is not a member). The lifecycle
 * event handlers cover these live; this pass is the reconciliation net for events dropped during an outage.
 *
 * <p>Only DEFINITIVE signals transition consent state — {@code is_archived}, {@code channel_not_found}, or
 * {@code is_member == false}. Transport failures and other errors skip the channel: pausing a healthy channel on a
 * network blip would mislead the admin in the opposite direction. Pause, never revoke — an accidental kick must not
 * destroy consented history.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelMetadataRefresher {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelMetadataRefresher.class);

    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackMessageService slackMessageService;
    private final SlackChannelConsentService consentService;

    public SlackChannelMetadataRefresher(
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackMessageService slackMessageService,
            SlackChannelConsentService consentService) {
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.slackMessageService = slackMessageService;
        this.consentService = consentService;
    }

    /** Refresh one workspace's channel metadata, uninterruptibly. Returns the number of channels checked. */
    public int refreshWorkspace(long workspaceId) {
        return refreshWorkspace(workspaceId, () -> false);
    }

    /**
     * Refresh one workspace's channel metadata, checking {@code cancelled} before each channel.
     *
     * <p>The check is per channel because each channel costs one {@code conversations.info} round trip: a
     * workspace with a few hundred allow-listed channels otherwise keeps calling Slack for minutes after the
     * admin pressed Cancel, and the pass runs <em>before</em> the history sync (which already honors the
     * handle), so the cancel appeared to do nothing at all. Aborting here is safe at any point: every channel
     * is refreshed independently and nothing is inferred from absence, so a partial pass is simply a smaller
     * pass — the next one repeats it.
     *
     * @return the number of channels actually checked (fewer than the workspace's total when cancelled)
     */
    public int refreshWorkspace(long workspaceId, BooleanSupplier cancelled) {
        List<SlackMonitoredChannel> channels =
                monitoredChannelRepository.findByWorkspaceIdAndConsentStateNot(workspaceId, ConsentState.REVOKED);
        int checked = 0;
        for (SlackMonitoredChannel channel : channels) {
            if (cancelled.getAsBoolean()) {
                log.info(
                        "slack.sync: metadata refresh cancelled for workspaceId={} after {} of {} channel(s)",
                        workspaceId,
                        checked,
                        channels.size());
                return checked;
            }
            checked++;
            try {
                refreshChannel(workspaceId, channel);
            } catch (RuntimeException e) {
                log.warn(
                        "slack.sync: metadata refresh failed for workspaceId={} channelId={}: {}",
                        workspaceId,
                        channel.getSlackChannelId(),
                        e.toString());
            }
        }
        return checked;
    }

    private void refreshChannel(long workspaceId, SlackMonitoredChannel channel) {
        String channelId = channel.getSlackChannelId();
        ConversationLookup lookup = slackMessageService.lookupConversationDetailed(workspaceId, channelId);
        switch (lookup) {
            case ConversationLookup.Found(var info) -> {
                if (info.channelName() != null && !Objects.equals(info.channelName(), channel.getChannelName())) {
                    consentService.renameChannel(workspaceId, channelId, info.channelName());
                }
                if (channel.getConsentState() != ConsentState.ACTIVE) {
                    return;
                }
                if (info.archived()) {
                    consentService.pauseForPlatformEvent(workspaceId, channelId, "channel archived — detected by sync");
                } else if (!info.member()) {
                    consentService.pauseForPlatformEvent(
                            workspaceId, channelId, "bot removed from channel — detected by sync");
                }
            }
            case ConversationLookup.NotFound(var ignored) -> {
                if (channel.getConsentState() == ConsentState.ACTIVE) {
                    consentService.pauseForPlatformEvent(
                            workspaceId, channelId, "channel no longer exists — detected by sync");
                }
            }
            case ConversationLookup.Unavailable(var error) ->
                log.debug(
                        "slack.sync: metadata for workspaceId={} channelId={} unavailable ({}) — skipped",
                        workspaceId,
                        channelId,
                        error);
        }
    }
}
