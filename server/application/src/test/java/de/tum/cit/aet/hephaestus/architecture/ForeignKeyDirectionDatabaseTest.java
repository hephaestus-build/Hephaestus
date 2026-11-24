package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A foreign key follows a module edge: the module of the referencing table declares the module of the referenced
 * table (ADR 0138). A reference against the edge is a plain id with no foreign key, and the higher module writes both
 * rows through the lower module's port. Every table has an owner module, so a table without an entity is registered in
 * {@link TableOwners#NATIVE_TABLES}. An edge that touches a mentoring module has no exception. Main's earlier
 * keys against an edge are listed one by one in {@code legacy-foreign-keys.txt}.
 */
@Tag("database")
class ForeignKeyDirectionDatabaseTest {
    private static final String FOREIGN_KEYS = """
            SELECT c.conname, src.relname, dst.relname FROM pg_constraint c
            JOIN pg_class src ON src.oid = c.conrelid JOIN pg_class dst ON dst.oid = c.confrelid
            WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace AND NOT src.relispartition
            ORDER BY c.conname
            """;

    private static final String TABLES = """
            SELECT c.relname FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace
              AND c.relkind IN ('r', 'p') AND NOT c.relispartition
              AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')
            ORDER BY 1
            """;

    @Test
    void shouldGiveEveryTableAnOwnerAndPointEveryForeignKeyAlongAModuleEdge() throws SQLException {
        var database = PostgreSQLTestContainer.createMigratedDatabase("foreign_key_direction");
        var owners = TableOwners.byTable(ModulithVerificationTest.applicationModules());
        var keys = keys(database, owners, ModulithVerificationTest.declaredDependencies());
        assertThat(keys.refused())
                .as("Register an owner for each table, and store a reference against a mentoring edge as a plain id")
                .isEmpty();
        LegacyBoundaryBaseline.assertMatches("legacy-foreign-keys", keys.legacy());
    }

    @Test
    void shouldRefuseAForeignKeyFromPracticesToMentoringAndATableWithoutAnOwner() throws SQLException {
        var database = PostgreSQLTestContainer.createDatabase("foreign_key_direction_fixture");
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE thread (id bigint PRIMARY KEY);
                    CREATE TABLE review (id bigint PRIMARY KEY);
                    CREATE TABLE result (id bigint PRIMARY KEY, thread_id bigint CONSTRAINT sfk_result_thread_id
                        REFERENCES thread (id));
                    CREATE TABLE inbox_item (id bigint PRIMARY KEY, result_id bigint CONSTRAINT sfk_inbox_item_result_id
                        REFERENCES result (id));
                    CREATE TABLE native_inbox (id bigint PRIMARY KEY, review_id bigint CONSTRAINT sfk_native_inbox_review
                        REFERENCES review (id));
                    CREATE TABLE agent_run (id bigint PRIMARY KEY, review_id bigint CONSTRAINT sfk_agent_run_review
                        REFERENCES review (id));
                    """);
        }
        var owners = Map.of(
                "thread",
                "mentoring",
                "result",
                "practices",
                "inbox_item",
                "mentoring",
                "review",
                "practices",
                "agent_run",
                "agent");
        var declared =
                Map.of("mentoring", List.of("practices::spi"), "practices", List.of("core"), "agent", List.of("core"));
        var keys = keys(database, owners, declared);
        assertThat(keys.refused())
                .containsExactlyInAnyOrder(
                        "sfk_result_thread_id: result (practices) -> thread (mentoring)",
                        "native_inbox: a table without an owner module");
        assertThat(keys.legacy()).containsExactly("sfk_agent_run_review: agent_run (agent) -> review (practices)");
    }

    private record Keys(List<String> refused, List<String> legacy) {}

    private static Keys keys(TestDatabase database, Map<String, String> owners, Map<String, List<String>> declared)
            throws SQLException {
        var refused = new ArrayList<String>();
        var legacy = new ArrayList<String>();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            try (var tables = statement.executeQuery(TABLES)) {
                while (tables.next()) {
                    if (!owners.containsKey(tables.getString(1)))
                        refused.add(tables.getString(1) + ": a table without an owner module");
                }
            }
            try (var keys = statement.executeQuery(FOREIGN_KEYS)) {
                while (keys.next()) {
                    String source = owners.get(keys.getString(2));
                    String target = owners.get(keys.getString(3));
                    if (source == null || target == null || source.equals(target)) continue;
                    boolean declaredEdge = declared.getOrDefault(source, List.of()).stream()
                            .map(ModulithVerificationTest::moduleOf)
                            .anyMatch(target::equals);
                    if (declaredEdge) continue;
                    String key = "%s: %s (%s) -> %s (%s)"
                            .formatted(keys.getString(1), keys.getString(2), source, keys.getString(3), target);
                    (MentoringModules.touches(source, target) ? refused : legacy).add(key);
                }
            }
        }
        return new Keys(refused, legacy);
    }
}
