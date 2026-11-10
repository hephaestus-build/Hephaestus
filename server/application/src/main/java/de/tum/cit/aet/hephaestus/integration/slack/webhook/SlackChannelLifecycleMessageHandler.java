package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;

abstract class SlackChannelLifecycleMessageHandler extends AbstractSlackEnvelopeHandler {

    protected final SlackChannelLifecycleService lifecycleService;

    SlackChannelLifecycleMessageHandler(
            String eventType, SlackChannelLifecycleService lifecycleService, EventPayloadDeserializer deserializer) {
        super(eventType, deserializer);
        this.lifecycleService = lifecycleService;
    }
}
