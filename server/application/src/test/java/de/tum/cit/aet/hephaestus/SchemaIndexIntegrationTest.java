package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Index rules for the migrated schema. An index that another index makes redundant only adds write cost, and a foreign
 * key without an index makes each delete or update of its parent row scan the child table.
 */
class SchemaIndexIntegrationTest extends BaseIntegrationTest {

    /**
     * An index whose key columns start another index of the same table, with the same access method, expressions and
     * predicate. A unique index is redundant only when another unique index has exactly its columns, because a unique
     * index on fewer columns enforces a stronger rule. A primary key always stays.
     */
    private static final String REDUNDANT_INDEXES = """
            WITH ix AS (
              SELECT i.indexrelid, i.indrelid, c.relname AS idx, i.indkey::text AS cols, i.indclass::text AS opc,
                     i.indisunique AS uniq, i.indisprimary AS pk, c.relam AS am,
                     pg_get_expr(i.indpred, i.indrelid) AS pred, pg_get_expr(i.indexprs, i.indrelid) AS exprs
              FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_class t ON t.oid = i.indrelid
              WHERE t.relnamespace = 'public'::regnamespace AND NOT t.relispartition)
            SELECT DISTINCT a.idx || ' (covered by ' || b.idx || ')'
            FROM ix a JOIN ix b ON a.indrelid = b.indrelid AND a.indexrelid <> b.indexrelid AND a.am = b.am
              AND a.exprs IS NOT DISTINCT FROM b.exprs AND a.pred IS NOT DISTINCT FROM b.pred
              AND (b.cols = a.cols OR b.cols LIKE a.cols || ' %') AND (b.opc = a.opc OR b.opc LIKE a.opc || ' %')
            WHERE NOT a.pk
              AND NOT (a.uniq AND a.cols <> b.cols)
              AND NOT (a.uniq AND NOT b.uniq)
              AND NOT (a.cols = b.cols AND a.uniq = b.uniq AND NOT b.pk AND a.indexrelid < b.indexrelid)
            ORDER BY 1
            """;

    /**
     * Foreign keys of parent tables that no index leads with, in any column order. A partition's foreign key is a
     * clone of its parent's, so only the parent's constraint counts.
     */
    private static final String UNINDEXED_FOREIGN_KEYS = """
            SELECT c.conrelid::regclass::text || '.' || c.conname
            FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
            WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace AND c.conparentid = 0
              AND NOT t.relispartition
              AND NOT EXISTS (
                SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                  AND (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] @> c.conkey
                  AND (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] <@ c.conkey)
            ORDER BY 1
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldHaveNoIndexThatAnotherIndexMakesRedundant() {
        assertThat(query(REDUNDANT_INDEXES))
                .as("Each of these indexes starts another index on the same table, so it only adds write cost. Drop it"
                        + " in a next/ change set")
                .isEmpty();
    }

    @Test
    void shouldIndexEveryForeignKeyOutsideTheShrinkingList() throws IOException {
        Set<String> unindexed = Set.copyOf(query(UNINDEXED_FOREIGN_KEYS));
        Set<String> listed = allowlist();

        assertThat(unindexed)
                .filteredOn(key -> !listed.contains(key))
                .as("A new foreign key needs an index whose leading columns are the key, so that a delete or update of"
                        + " the parent row does not scan the child table. Add the index in the same change set")
                .isEmpty();
        assertThat(listed)
                .filteredOn(key -> !unindexed.contains(key))
                .as("These foreign keys now have an index, or no longer exist. Remove them from"
                        + " src/test/resources/db/unindexed-foreign-keys.txt")
                .isEmpty();
    }

    private List<String> query(String sql) {
        return jdbc.queryForList(sql, String.class).stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private static Set<String> allowlist() throws IOException {
        String text = new ClassPathResource("db/unindexed-foreign-keys.txt").getContentAsString(StandardCharsets.UTF_8);
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
