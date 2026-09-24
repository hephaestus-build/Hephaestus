package de.tum.cit.aet.hephaestus.agent.task;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Serializes a {@link TaskEnvelope} to bytes for the workspace input-files map. */
@Component
public class TaskEnvelopeWriter {

    private final ObjectWriter writer;

    public TaskEnvelopeWriter(JsonMapper baseObjectMapper) {
        this.writer = baseObjectMapper
                .writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .withDefaultPrettyPrinter();
    }

    public byte[] write(TaskEnvelope envelope) {
        try {
            return writer.writeValueAsBytes(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize TaskEnvelope", e);
        }
    }

    public String writeAsString(TaskEnvelope envelope) {
        return new String(write(envelope), StandardCharsets.UTF_8);
    }
}
