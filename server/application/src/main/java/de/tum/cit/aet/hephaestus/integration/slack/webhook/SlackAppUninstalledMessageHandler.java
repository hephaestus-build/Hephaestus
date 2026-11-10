package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackUninstallService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Handles Slack app uninstall teardown from the unified Slack event stream. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackAppUninstalledMessageHandler extends AbstractSlackEnvelopeHandler {

    private final SlackUninstallService uninstallService;

    public SlackAppUninstalledMessageHandler(
            SlackUninstallService uninstallService, EventPayloadDeserializer deserializer) {
        super("app_uninstalled", deserializer);
        this.uninstallService = uninstallService;
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        uninstallService.onUninstall(
                teamId(root), "app_uninstalled", root.path("event_id").asString(""));
    }
}
