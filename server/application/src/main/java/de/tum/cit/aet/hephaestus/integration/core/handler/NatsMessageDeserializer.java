package de.tum.cit.aet.hephaestus.integration.core.handler;

import io.nats.client.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class NatsMessageDeserializer {

    private final ObjectMapper objectMapper;

    public NatsMessageDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T deserialize(Message message, Class<T> type) throws IOException {
        String payload = new String(message.getData(), StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException failure) {
            throw new PayloadParsingException("Webhook payload cannot be parsed", failure);
        }
    }

    public String getPayloadAsString(Message message) {
        return new String(message.getData(), StandardCharsets.UTF_8);
    }
}
