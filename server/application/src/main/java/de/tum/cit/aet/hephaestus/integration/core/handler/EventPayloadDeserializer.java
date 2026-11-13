package de.tum.cit.aet.hephaestus.integration.core.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Parses the JSON payload of a {@link ProviderEvent}; a payload that does not parse is permanent. */
@Component
public class EventPayloadDeserializer {

    private final ObjectMapper objectMapper;

    public EventPayloadDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T deserialize(ProviderEvent event, Class<T> type) throws IOException {
        String payload = new String(event.payload(), StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException failure) {
            throw new PayloadParsingException("Webhook payload cannot be parsed", failure);
        }
    }

    public String getPayloadAsString(ProviderEvent event) {
        return new String(event.payload(), StandardCharsets.UTF_8);
    }
}
