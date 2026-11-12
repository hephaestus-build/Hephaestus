package de.tum.cit.aet.hephaestus.core.privacy.spi;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Volatile inbox rows: keep preview keys for export, but erase current matches of the same frozen identity scope too. */
public class LiveJdbcPersonDataStore extends JdbcPersonDataStore {
    public LiveJdbcPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            String store,
            String table,
            String predicate,
            String columns,
            String keys,
            String redaction,
            int order) {
        super(jdbc, mapper, store, table, predicate, columns, keys, redaction, order);
    }

    @Override
    public boolean selectionMustStayUnchanged() {
        return false;
    }

    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        return eraseIncludingCurrentMatches(selection, frozenScope);
    }
}
