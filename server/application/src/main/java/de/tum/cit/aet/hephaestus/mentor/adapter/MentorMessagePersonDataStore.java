package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Conversation text is exportable; copied tool context and arbitrary runtime metadata are not. */
final class MentorMessagePersonDataStore extends JdbcPersonDataStore {
    private static final List<String> BILLING_FACTS = List.of(
            "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "costUsd", "durationMs", "toolCalls");
    private final ObjectMapper mapper;

    MentorMessagePersonDataStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        super(
                jdbc,
                mapper,
                "chat_message",
                "chat_message",
                "t.thread_id IN (SELECT id FROM chat_thread WHERE user_id = ANY(:users))",
                "id,created_at,metadata,role,parent_message_id,thread_id,parts,version,status,llm_total_calls,llm_total_input_tokens,llm_total_output_tokens,llm_total_reasoning_tokens,llm_cache_read_tokens,llm_cache_write_tokens",
                "id",
                "",
                -110);
        this.mapper = mapper;
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection) {
        return super.export(selection).stream()
                .map(row -> conversationRow(mapper, row))
                .toList();
    }

    static JsonNode conversationRow(ObjectMapper mapper, JsonNode stored) {
        var exported = mapper.createObjectNode();
        for (var property : stored.properties()) {
            if (!property.getKey().equals("parts") && !property.getKey().equals("metadata")) {
                exported.set(property.getKey(), property.getValue());
            }
        }
        var text = exported.putArray("parts");
        for (var part : stored.path("parts")) {
            if (part.path("type").asString("").equals("text")
                    && part.path("text").isString()) {
                text.addObject()
                        .put("type", "text")
                        .put("text", part.path("text").asString());
            }
        }
        if (!stored.has("metadata")) return exported;
        var billing = exported.putObject("metadata");
        for (String key : BILLING_FACTS) {
            JsonNode value = stored.path("metadata").path(key);
            if (value.isNumber()) billing.set(key, value);
        }
        return exported;
    }
}
