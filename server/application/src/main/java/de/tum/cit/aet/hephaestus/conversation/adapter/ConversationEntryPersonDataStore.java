package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A store of {@code conversation_entry} rows whose export holds the words the app shows for each message
 * ({@code search_text}, {@link ConversationEntry#getSearchText}) as its {@code text}. The stored text alone misses the words
 * Hephaestus put in a link.
 */
class ConversationEntryPersonDataStore extends JdbcPersonDataStore {
    private final String projection;

    ConversationEntryPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            String store,
            String predicate,
            String columns,
            int order) {
        super(jdbc, mapper, store, "conversation_entry", predicate, columns, "id", "", order);
        this.projection = Stream.of(columns.split(","))
                        .map(column -> "'" + column + "'," + value(column))
                        .collect(Collectors.joining(",", "jsonb_build_object(", ")"))
                + "::text";
    }

    private static String value(String column) {
        return column.equals("text") ? "e.search_text" : "e." + column;
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
        // The selection names rows as t; the projection reads them as e.
        return querySelected(projection, "conversation_entry t JOIN conversation_entry e ON e.id = t.id", selection)
                .stream()
                .sorted(Comparator.comparing(row -> row.path("id").asString()))
                .toList();
    }
}
