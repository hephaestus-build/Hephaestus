package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Renders Slack App Home from the unified Slack event stream. The Messages tab needs nothing: its suggested prompts
 * are static in the app manifest ({@code agent_view.suggested_prompts}).
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackAppHomeOpenedMessageHandler extends AbstractSlackEnvelopeHandler {

    private final SlackAppHomeService appHomeService;

    public SlackAppHomeOpenedMessageHandler(SlackAppHomeService appHomeService, NatsMessageDeserializer deserializer) {
        super("app_home_opened", deserializer);
        this.appHomeService = appHomeService;
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        JsonNode event = root.path("event");
        if ("home".equals(event.path("tab").asString("home"))) {
            appHomeService.onHomeOpened(teamId(root), event.path("user").asString(""));
        }
    }
}
