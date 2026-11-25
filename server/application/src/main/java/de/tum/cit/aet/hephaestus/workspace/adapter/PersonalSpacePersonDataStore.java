package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The person's own personal space. Export names it by its slug and creation time; what lives inside it is exported by
 * its own stores. Erasure ends the space with everything in it, as the erasure of the account does.
 */
final class PersonalSpacePersonDataStore implements PersonDataContributor {
    /** After the stores whose rows may live inside the space, and before the account store. */
    static final int ORDER = 990;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PersonalSpaceErasureAdapter spaces;

    PersonalSpacePersonDataStore(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, PersonalSpaceErasureAdapter spaces) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.spaces = spaces;
    }

    @Override
    public String store() {
        return "workspace";
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        if (person.accountId() == null) return new PersonDataSelection(List.of());
        return new PersonDataSelection(jdbc.query(
                "SELECT id FROM workspace WHERE personal_account_id = :account ORDER BY id",
                Map.of("account", person.accountId()),
                (rs, row) -> new PersonDataSelection.RowKey(Map.of("id", Long.toString(rs.getLong(1))))));
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
        if (selection.rows().isEmpty()) return List.of();
        Object[] ids = selection.rows().stream()
                .map(row -> Long.valueOf(row.columns().get("id")))
                .toArray();
        return jdbc.query(
                """
                SELECT json_build_object('slug', slug, 'created_at', created_at)::text
                FROM workspace WHERE id = ANY(:ids) ORDER BY id
                """, Map.of("ids", new SqlArrayValue("bigint", ids)), (rs, row) -> mapper.readTree(rs.getString(1)));
    }

    /** Runs before every store's erasure, the conversation copy store's sandbox deletion included. */
    @Override
    public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {
        selection
                .rows()
                .forEach(row -> spaces.suspend(Long.parseLong(row.columns().get("id"))));
    }

    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        if (selection.rows().isEmpty() || frozenScope.accountId() == null) return 0;
        spaces.eraseAccount(frozenScope.accountId());
        return selection.rows().size();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
