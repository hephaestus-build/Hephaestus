package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackMentorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Routes the developer's DMs with the app from the unified Slack NATS stream: new messages (top level or in a
 * thread), edits and deletions. Messages of bots, the app's own included, are never routed.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackMentorDmMessageHandler extends AbstractSlackEnvelopeHandler {

    private final SlackMentorService mentorService;

    public SlackMentorDmMessageHandler(SlackMentorService mentorService, NatsMessageDeserializer deserializer) {
        super("message_im", deserializer);
        this.mentorService = mentorService;
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        JsonNode event = root.path("event");
        if (!"message".equals(event.path("type").asString(""))
                || !"im".equals(event.path("channel_type").asString(""))) {
            return;
        }
        String teamId = teamId(root);
        String channelId = event.path("channel").asString("");
        switch (event.path("subtype").asString("")) {
            case "", "thread_broadcast" -> {
                if (event.has("bot_id")) {
                    return;
                }
                String messageTs = event.path("ts").asString("");
                mentorService.handleDm(
                        teamId,
                        channelId,
                        event.path("user").asString(""),
                        event.path("text").asString(""),
                        messageTs,
                        event.path("thread_ts").asString(messageTs));
            }
            case "message_changed" -> {
                JsonNode message = event.path("message");
                // Slack also sends message_changed when a reply changes a parent's thread summary; only an edit
                // carries "edited".
                if (message.has("bot_id") || !message.has("edited")) {
                    return;
                }
                mentorService.handleEdit(
                        teamId,
                        channelId,
                        message.path("user").asString(""),
                        message.path("ts").asString(""),
                        message.path("text").asString(""));
            }
            case "message_deleted" -> {
                JsonNode previous = event.path("previous_message");
                if (previous.has("bot_id")) {
                    return;
                }
                mentorService.handleDelete(
                        teamId,
                        channelId,
                        previous.path("user").asString(""),
                        event.path("deleted_ts").asString(previous.path("ts").asString("")));
            }
            default -> {
                // bot_message, file_share, channel notices: nothing Heph answers or keeps.
            }
        }
    }
}
