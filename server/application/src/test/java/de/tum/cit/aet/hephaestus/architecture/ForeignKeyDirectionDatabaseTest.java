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
 * rows through the lower module's port. The rule covers every foreign key that touches a mentoring module.
 */
@Tag("database")
class ForeignKeyDirectionDatabaseTest {
    private static final String FOREIGN_KEYS = """
            SELECT c.conname, src.relname, dst.relname FROM pg_constraint c
            JOIN pg_class src ON src.oid = c.conrelid JOIN pg_class dst ON dst.oid = c.confrelid
            WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
            ORDER BY c.conname
            """;

    @Test
    void shouldPointEveryForeignKeyAlongAModuleEdge() throws SQLException {
        var database = PostgreSQLTestContainer.createMigratedDatabase("foreign_key_direction");
        assertThat(violations(
                        database,
                        TableOwnershipArchTest.tableOwners(),
                        ModulithVerificationTest.declaredDependencies()))
                .as("Store a reference against a module edge as a plain id without a foreign key")
                .isEmpty();
    }

    @Test
    void shouldRefuseAForeignKeyFromPracticesToMentoring() throws SQLException {
        var database = PostgreSQLTestContainer.createDatabase("foreign_key_direction_fixture");
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE thread (id bigint PRIMARY KEY);
                    CREATE TABLE result (id bigint PRIMARY KEY, thread_id bigint CONSTRAINT sfk_result_thread_id
                        REFERENCES thread (id));
                    CREATE TABLE inbox_item (id bigint PRIMARY KEY, result_id bigint CONSTRAINT sfk_inbox_item_result_id
                        REFERENCES result (id));
                    """);
        }
        var owners = Map.of("thread", "mentoring", "result", "practices", "inbox_item", "mentoring");
        var declared = Map.of("mentoring", List.of("practices::spi"), "practices", List.of("core"));
        assertThat(violations(database, owners, declared))
                .containsExactly("sfk_result_thread_id: result (practices) -> thread (mentoring)");
    }

    private static List<String> violations(
            TestDatabase database, Map<String, String> owners, Map<String, List<String>> declared) throws SQLException {
        var violations = new ArrayList<String>();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement();
                var keys = statement.executeQuery(FOREIGN_KEYS)) {
            while (keys.next()) {
                String source = owners.get(keys.getString(2));
                String target = owners.get(keys.getString(3));
                if (source == null || target == null || source.equals(target)) continue;
                boolean declaredEdge = declared.getOrDefault(source, List.of()).stream()
                        .map(ModulithVerificationTest::moduleOf)
                        .anyMatch(target::equals);
                if (!declaredEdge && MentoringModules.touches(source, target))
                    violations.add("%s: %s (%s) -> %s (%s)"
                            .formatted(keys.getString(1), keys.getString(2), source, keys.getString(3), target));
            }
        }
        return violations;
    }
}
