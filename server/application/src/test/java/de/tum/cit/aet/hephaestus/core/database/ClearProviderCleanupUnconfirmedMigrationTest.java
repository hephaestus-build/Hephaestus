package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server cannot read the cleanup mark that releases 1.0.0-rc.3 to rc.5 wrote, so the correction clears exactly
 * that mark, also on a rerun, and moves the row's revision and version.
 */
@Tag("database")
class ClearProviderCleanupUnconfirmedMigrationTest {
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("clear_provider_cleanup_unconfirmed");
    private static final String CHANGELOG =
            "db/changelog/next/20261121112602-fix-clear-provider-cleanup-unconfirmed.xml";

    @Test
    void shouldClearOnlyTheCleanupMarkWhenTheChangelogRunsAgain() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE connection (
                        id bigint PRIMARY KEY, attention_problem varchar(32),
                        attention_revision bigint NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 0
                    );
                    INSERT INTO connection (id, attention_problem, attention_revision, version) VALUES
                        (1, 'PROVIDER_CLEANUP_UNCONFIRMED', 3, 5),
                        (2, 'WEBHOOK_UNAVAILABLE', 3, 5),
                        (3, NULL, 3, 5);
                    """);
            connection.commit();

            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);
                // Liquibase commits a change set before its history row, so a stopped run applies it again.
                statement.execute("DELETE FROM databasechangelog");
                connection.commit();
                liquibase.update(contexts, labels);

                assertThat(row(connection, 1)).isEqualTo(new Row(null, 4, 6));
                assertThat(row(connection, 2)).isEqualTo(new Row("WEBHOOK_UNAVAILABLE", 3, 5));
                assertThat(row(connection, 3)).isEqualTo(new Row(null, 3, 5));

                assertThatThrownBy(() -> liquibase.rollback(1, contexts, labels))
                        .hasStackTraceContaining("Restore the database backup taken before this upgrade");
            }
        }
    }

    private record Row(@Nullable String problem, long revision, long version) {}

    private static Row row(Connection connection, long id) throws SQLException {
        try (var select = connection.prepareStatement(
                "SELECT attention_problem, attention_revision, version FROM connection WHERE id = ?")) {
            select.setLong(1, id);
            try (var rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new Row(rows.getString(1), rows.getLong(2), rows.getLong(3));
            }
        }
    }
}
