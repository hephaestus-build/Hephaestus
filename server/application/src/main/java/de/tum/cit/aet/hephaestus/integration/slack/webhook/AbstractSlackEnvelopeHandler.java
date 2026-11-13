package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.io.IOException;
import tools.jackson.databind.JsonNode;

abstract class AbstractSlackEnvelopeHandler implements IntegrationMessageHandler {

    private final EventTypeKey key;
    private final EventPayloadDeserializer deserializer;

    AbstractSlackEnvelopeHandler(String eventType, EventPayloadDeserializer deserializer) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        this.key = new EventTypeKey(IntegrationKind.SLACK, eventType);
        this.deserializer = deserializer;
    }

    @Override
    public EventTypeKey key() {
        return key;
    }

    @Override
    public void onMessage(ProviderEvent event) {
        JsonNode root;
        try {
            root = deserializer.deserialize(event, JsonNode.class);
        } catch (IOException e) {
            throw new PayloadParsingException(
                    "Payload parsing failed for Slack subject: " + sanitizeForLog(event.subject()), e);
        }
        handleEnvelope(root);
    }

    protected abstract void handleEnvelope(JsonNode root);

    protected static String teamId(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        String team = root.path("team_id").asString("");
        if (!team.isBlank()) {
            return team;
        }
        return root.path("authorizations").path(0).path("team_id").asString("");
    }
}
