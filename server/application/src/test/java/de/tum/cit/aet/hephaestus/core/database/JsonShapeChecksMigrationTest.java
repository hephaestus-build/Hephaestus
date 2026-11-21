package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The JSON shape checks reach a populated database: a stored JSON type that the server does not write stops the upgrade
 * with its column and count before validation, and the upgrade completes once that row is corrected, also on a rerun
 * and after a rollback.
 */
@Tag("database")
class JsonShapeChecksMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("json_shape_checks");
    private static final String CHANGELOG = "db/changelog/next/20261121151130-db-json-shape-checks.xml";
    /** The query in {@code .migration/json-columns-checked.md}, which psql runs with {@code \gexec}. */
    private static final String GUIDE_QUERY = """
            SELECT format('SELECT %L AS check_name, t.* FROM %s t WHERE NOT %s', conname, conrelid::regclass,
                          regexp_replace(pg_get_constraintdef(oid), '^CHECK (.*) NOT VALID$', '\\1'))
            FROM pg_constraint
            WHERE contype = 'c' AND conname LIKE 'ck\\_%' AND NOT convalidated
            """;

    private static final Pattern ADD =
            Pattern.compile("(?:ALTER TABLE (\\S+) |, )ADD CONSTRAINT ck_\\w+ CHECK \\((\\w+) IS NULL OR");

    @Test
    void shouldStopAtTheCheckWhenAStoredJsonTypeIsWrongAndCompleteOnceTheRowIsCorrected() throws Exception {
        Map<String, List<String>> columns = columnsByTable();
        int checks = columns.values().stream().mapToInt(List::size).sum();
        int changeSets = 2 * columns.size();
        int actionChecks = Objects.requireNonNull(columns.get("agent_action")).size();
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (var table : columns.entrySet()) {
                statement.execute("CREATE TABLE " + table.getKey() + " (id bigint GENERATED ALWAYS AS IDENTITY, "
                        + String.join(
                                ", ",
                                table.getValue().stream().map(c -> c + " jsonb").toList()) + ")");
            }
            statement.execute("INSERT INTO agent_action (payload) VALUES ('{}'), ('\"text\"'), (NULL), ('[1]')");
            connection.commit();

            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();

                // The count stops the upgrade with the column and its number of rows, before validation runs.
                assertThatThrownBy(() -> liquibase.update(contexts, labels))
                        .hasStackTraceContaining("The upgrade stopped before it checks the stored rows of agent_action")
                        .hasStackTraceContaining("agent_action.payload: 2");
                connection.rollback();
                assertThat(allChecks(connection)).isEqualTo(actionChecks);
                assertThat(validChecks(connection)).isZero();
                // The query in this release's migration guide entry lists exactly the rows that the stop counted.
                var listings = new ArrayList<String>();
                try (var rows = statement.executeQuery(GUIDE_QUERY)) {
                    while (rows.next()) listings.add(rows.getString(1));
                }
                // The stop leaves only this table's checks unvalidated.
                assertThat(listings).hasSize(actionChecks);
                var refused = new ArrayList<String>();
                for (String listing : listings) {
                    try (var rows = statement.executeQuery(listing)) {
                        while (rows.next()) refused.add(rows.getString("payload"));
                    }
                }
                assertThat(refused).containsExactlyInAnyOrder("\"text\"", "[1]");
                connection.rollback();
                // The check that the stopped upgrade added refuses a new value of the wrong type already.
                assertThatThrownBy(() -> statement.execute("INSERT INTO agent_action (payload) VALUES ('[2]')"))
                        .hasMessageContaining("ck_agent_action_payload_shape");
                connection.rollback();

                statement.execute("DELETE FROM agent_action WHERE jsonb_typeof(payload) <> 'object'");
                connection.commit();
                liquibase.update(contexts, labels);
                assertThat(validChecks(connection)).isEqualTo(checks);

                liquibase.rollback(changeSets, contexts, labels);
                assertThat(allChecks(connection)).isZero();

                liquibase.update(contexts, labels);
                // Liquibase commits a change set before its history row, so a stopped run applies it again.
                statement.execute("DELETE FROM databasechangelog");
                connection.commit();
                liquibase.update(contexts, labels);
                assertThat(allChecks(connection)).isEqualTo(checks);
                assertThat(validChecks(connection)).isEqualTo(checks);
            }
        }
    }

    /** The tables and columns that the changelog checks, in its order. */
    private static Map<String, List<String>> columnsByTable() throws Exception {
        String xml;
        try (var stream = Objects.requireNonNull(
                JsonShapeChecksMigrationTest.class.getClassLoader().getResourceAsStream(CHANGELOG))) {
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // An add change set's statement adds checks. A validation's rollback drops each check before it adds it.
        var columns = new LinkedHashMap<String, List<String>>();
        for (String line : xml.lines()
                .filter(text -> text.contains(" ADD CONSTRAINT ") && !text.contains("DROP CONSTRAINT"))
                .toList()) {
            String table = null;
            var matcher = ADD.matcher(line);
            while (matcher.find()) {
                if (matcher.group(1) != null) table = matcher.group(1);
                columns.computeIfAbsent(Objects.requireNonNull(table), key -> new ArrayList<>())
                        .add(matcher.group(2));
            }
        }
        assertThat(columns).isNotEmpty();
        return columns;
    }

    private static int allChecks(Connection connection) throws SQLException {
        return count(connection, "SELECT count(*) FROM pg_constraint WHERE contype = 'c' AND conname LIKE 'ck\\_%'");
    }

    private static int validChecks(Connection connection) throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM pg_constraint WHERE contype = 'c' AND conname LIKE 'ck\\_%' AND convalidated");
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var select = connection.prepareStatement(sql);
                var rows = select.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }
}
