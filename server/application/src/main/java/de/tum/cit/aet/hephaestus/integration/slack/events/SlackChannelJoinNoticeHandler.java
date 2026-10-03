package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Just-in-time consent transparency for a member who joins an <em>already-active</em> monitored channel.
 *
 * <p>The one-time activation announcement ({@code SlackChannelConsentService}) reaches only the members present when
 * a channel is turned on; a person who joins afterwards would be ingested with no notice. This handler closes that
 * gap: on a {@code member_joined_channel} event it posts an EPHEMERAL notice, visible only to the joiner, carrying
 * the same plain-language copy and one-click opt-out as the announcement — the ICO "ongoing + just-in-time"
 * transparency expectation (tell people at the moment their data starts being used).
 *
 * <p>It fires only for a real member joining an actively-ingested channel: a join into a non-{@code ACTIVE} or
 * non-allow-listed channel discloses nothing because nothing is being read, and the bot's own self-join (the app
 * being added to a channel) never posts a notice — it registers the channel as {@code PENDING} instead.
 * Best-effort throughout: a Slack-side failure is logged and swallowed — a dropped notice blocks nothing and
 * forward-only ingestion is unaffected.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelJoinNoticeHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelJoinNoticeHandler.class);

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackChannelConsentGate consentGate;
    private final SlackParticipantConsentGate participantConsentGate;
    private final SlackMessageService messageService;
    private final SlackChannelConsentService consentService;

    public SlackChannelJoinNoticeHandler(
            SlackWorkspaceResolver workspaceResolver,
            SlackChannelConsentGate consentGate,
            SlackParticipantConsentGate participantConsentGate,
            SlackMessageService messageService,
            SlackChannelConsentService consentService) {
        this.workspaceResolver = workspaceResolver;
        this.consentGate = consentGate;
        this.participantConsentGate = participantConsentGate;
        this.messageService = messageService;
        this.consentService = consentService;
    }

    /**
     * Handle a {@code member_joined_channel} event: post the just-in-time consent notice iff the joiner is a real
     * member of an actively-ingested channel. Never throws.
     *
     * @param teamId        the Slack {@code T…} workspace id from the verified event envelope
     * @param event         the inner {@code member_joined_channel} event ({@code user} = joiner, {@code channel} = channel)
     * @param noticeAllowed whether the time-sensitive ephemeral notice may still be posted (false on a stale
     *                      redelivery); the bot-self-join registration is durable and applies regardless
     */
    public void onMemberJoined(String teamId, JsonNode event, boolean noticeAllowed) {
        String channelId = event.path("channel").asString("");
        String joinerUserId = event.path("user").asString("");
        if (channelId.isBlank() || joinerUserId.isBlank()) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();

        // Adding the app to a channel is Slack-native discovery. Register PENDING only; an admin still has to
        // activate the channel before anything is read.
        if (messageService
                .resolveBotUserId(workspaceId)
                .filter(joinerUserId::equals)
                .isPresent()) {
            consentService.register(workspaceId, channelId, null);
            return;
        }

        if (!noticeAllowed) {
            return;
        }
        // Only disclose where we are actually reading.
        if (!consentGate.ingestAllowed(workspaceId, channelId)) {
            return;
        }
        if (!participantConsentGate.ingestionAllowed(workspaceId, joinerUserId)) {
            return;
        }

        try {
            messageService.sendEphemeralForWorkspace(
                    workspaceId,
                    channelId,
                    joinerUserId,
                    SlackConsentBlocks.lateJoinNotice(),
                    SlackConsentBlocks.lateJoinFallbackText());
        } catch (SlackSendException e) {
            log.warn(
                    "Slack join consent notice failed to post: workspaceId={}, channelId={}, error={}",
                    workspaceId,
                    channelId,
                    e.slackError());
        }
    }
}
