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
 * The enum checks reach a populated database: a stored value that the server does not write stops the upgrade at its
 * check, and the upgrade completes once that row is corrected, also on a rerun and after a rollback.
 */
@Tag("database")
class EnumCheckConstraintsMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("enum_check_constraints");
    private static final String CHANGELOG = "db/changelog/next/20261121133358-db-enum-check-constraints.xml";
    private static final Pattern ADD =
            Pattern.compile("(?:ALTER TABLE (\\S+) |, )ADD CONSTRAINT ck_\\w+ CHECK \\((\\w+) IN");

    @Test
    void shouldStopAtTheCheckWhenAStoredValueIsUnknownAndCompleteOnceTheRowIsCorrected() throws Exception {
        Map<String, List<String>> columns = columnsByTable();
        int checks = columns.values().stream().mapToInt(List::size).sum();
        int changeSets = 2 * columns.size();
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (var table : columns.entrySet()) {
                statement.execute("CREATE TABLE " + table.getKey() + " (id bigint GENERATED ALWAYS AS IDENTITY, "
                        + String.join(
                                ", ",
                                table.getValue().stream()
                                        .map(c -> c + " varchar(64)")
                                        .toList()) + ")");
            }
            statement.execute(
                    "INSERT INTO activity_event (event_type) VALUES ('ISSUE_CREATED'), ('DISCUSSION_CREATED')");
            connection.commit();

            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();

                assertThatThrownBy(() -> liquibase.update(contexts, labels))
                        .hasStackTraceContaining("ck_activity_event_event_type");
                connection.rollback();
                // The check that the stopped upgrade added refuses new unknown values already.
                assertThatThrownBy(() -> statement.execute(
                                "INSERT INTO activity_event (event_type) VALUES ('DISCUSSION_CREATED')"))
                        .hasMessageContaining("ck_activity_event_event_type");
                connection.rollback();

                statement.execute("DELETE FROM activity_event WHERE event_type = 'DISCUSSION_CREATED'");
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
                EnumCheckConstraintsMigrationTest.class.getClassLoader().getResourceAsStream(CHANGELOG))) {
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
