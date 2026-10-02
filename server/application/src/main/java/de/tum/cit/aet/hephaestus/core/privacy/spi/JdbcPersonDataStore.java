package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared key-only mechanics; modules own explicit predicates, export allowlists and erasure policy. */
public class JdbcPersonDataStore implements PersonDataContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String store;
    private final String table;
    private final String predicate;
    private final List<String> columns;
    private final List<String> keys;
    private final String redaction;
    private final int order;

    public JdbcPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            String store,
            String table,
            String predicate,
            String columns,
            String keys,
            String redaction,
            int order) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.store = store;
        this.table = table;
        this.predicate = predicate;
        this.columns = List.of(columns.split(","));
        this.keys = List.of(keys.split(","));
        this.redaction = redaction;
        this.order = order;
    }

    @Override
    public String store() {
        return store;
    }

    @Override
    public int getOrder() {
        return order;
    }

    private String quotedTable() {
        return "\"" + table + "\"";
    }

    private String keyExpression() {
        return "jsonb_build_object("
                + String.join(
                        ",",
                        keys.stream()
                                .map(k -> "'" + k + "',t.\"" + k + "\"::text")
                                .toList()) + ")";
    }

    public static Map<String, Object> parameters(PersonScope scope, ObjectMapper mapper) {
        return Map.of(
                "derivedJobs",
                new SqlArrayValue("uuid", scope.derivedJobIds().toArray()),
                "users",
                new SqlArrayValue("bigint", scope.userIds().toArray()),
                "conversations",
                new SqlArrayValue("bigint", scope.conversationIds().toArray()),
                "documents",
                new SqlArrayValue("bigint", scope.outlineDocumentIds().toArray()),
                "artifacts",
                new SqlArrayValue("bigint", scope.scmArtifactIds().toArray()),
                "account",
                Objects.requireNonNullElse(scope.accountId(), -1L),
                "identities",
                mapper.writeValueAsString(scope.identities()));
    }

    @Override
    public PersonDataSelection select(PersonScope scope) {
        return new PersonDataSelection(jdbc.query(
                "SELECT " + keyExpression() + "::text FROM " + quotedTable() + " t WHERE " + predicate + " ORDER BY "
                        + keyExpression() + "::text",
                parameters(scope, mapper),
                (rs, row) -> {
                    JsonNode key = mapper.readTree(Objects.requireNonNull(rs.getString(1)));
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String column : keys)
                        values.put(column, key.path(column).asString());
                    return new PersonDataSelection.RowKey(values);
                }));
    }

    private Map<String, Object> selectionParameters(PersonDataSelection selection) {
        return Map.of(
                "keys",
                mapper.writeValueAsString(selection.rows().stream()
                        .map(PersonDataSelection.RowKey::columns)
                        .toList()));
    }

    private String selected() {
        return "EXISTS (SELECT 1 FROM jsonb_populate_recordset(NULL::" + quotedTable()
                + ",CAST(:keys AS jsonb)) selected WHERE "
                + String.join(
                        " AND ",
                        keys.stream()
                                .map(key -> "t.\"" + key + "\"=selected.\"" + key + "\"")
                                .toList())
                + ")";
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection) {
        String projection = "jsonb_build_object("
                + String.join(
                        ",",
                        columns.stream().map(c -> "'" + c + "',t.\"" + c + "\"").toList()) + ")";
        return jdbc.query(
                "SELECT " + projection + "::text FROM " + quotedTable() + " t WHERE " + selected() + " ORDER BY "
                        + keyExpression() + "::text",
                selectionParameters(selection),
                (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1))));
    }

    @Override
    public long erase(PersonDataSelection selection) {
        if (selection.rows().isEmpty()) return 0;
        String command = redaction.isEmpty()
                ? "DELETE FROM " + quotedTable() + " t"
                : "UPDATE " + quotedTable() + " t SET " + redaction;
        return jdbc.update(command + " WHERE " + selected(), selectionParameters(selection));
    }

    protected List<JsonNode> querySelected(String projection, String from, PersonDataSelection selection) {
        if (selection.rows().isEmpty()) return List.of();
        return jdbc.query(
                "SELECT " + projection + " FROM " + from + " WHERE " + selected(),
                selectionParameters(selection),
                (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1))));
    }
}
