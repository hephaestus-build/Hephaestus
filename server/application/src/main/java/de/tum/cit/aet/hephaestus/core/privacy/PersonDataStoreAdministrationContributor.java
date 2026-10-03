package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Counts and completion times remain after the acting administrator's exact account reference is erased. */
final class PersonDataStoreAdministrationContributor implements PersonDataContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    PersonDataStoreAdministrationContributor(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String store() {
        return "person_data_store_administration";
    }

    @Override
    public int getOrder() {
        return 950;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        return new PersonDataSelection(jdbc.query(
                """
                SELECT t.id,step.key,step.value->>'administratorAccountId' FROM person_data_request t
                CROSS JOIN LATERAL jsonb_each(t.completed_json::jsonb) step
                WHERE step.value->>'administratorAccountId'=CAST(:account AS text)
                ORDER BY t.id,step.key
                """,
                JdbcPersonDataStore.parameters(person, mapper),
                (rs, row) -> new PersonDataSelection.RowKey(Map.of(
                        "id",
                        Objects.requireNonNull(rs.getString(1)),
                        "store",
                        Objects.requireNonNull(rs.getString(2)),
                        "account",
                        Objects.requireNonNull(rs.getString(3))))));
    }

    private Map<String, Object> parameters(PersonDataSelection.RowKey key) {
        return Map.of(
                "id",
                UUID.fromString(key.columns().get("id")),
                "store",
                key.columns().get("store"),
                "account",
                key.columns().get("account"));
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection) {
        List<JsonNode> rows = new ArrayList<>();
        for (var key : selection.rows())
            rows.addAll(jdbc.query(
                    """
                SELECT jsonb_build_object('requestId',id,'store',:store,
                    'receipt',completed_json::jsonb->CAST(:store AS text))::text
                FROM person_data_request WHERE id=:id
                """, parameters(key), (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1)))));
        return List.copyOf(rows);
    }

    @Override
    public long erase(PersonDataSelection selection) {
        long changed = 0;
        for (var key : selection.rows()) changed += jdbc.update("""
                UPDATE person_data_request
                SET completed_json=jsonb_set(completed_json::jsonb,
                    ARRAY[CAST(:store AS text),'administratorAccountId'],'null'::jsonb)::text,version=version+1
                WHERE id=:id AND completed_json::jsonb->CAST(:store AS text)->>'administratorAccountId'=CAST(:account AS text)
                """, parameters(key));
        return changed;
    }
}
