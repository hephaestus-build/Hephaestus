package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A capture refusal that releases up to 1.0.0-rc.8 stored in lower case gets the one spelling, also on a rerun. */
@Tag("database")
class ReadinessRefusedSpellingMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("readiness_refused_spelling");
    private static final String CHANGELOG = "db/changelog/next/20261117155800-legacy-readiness-refused-spelling.xml";

    @Test
    void shouldRewriteOnlyTheLowerCaseCaptureRefusalWhenTheChangelogRunsAgain() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE review_practice (
                        review_id uuid NOT NULL, practice_id bigint NOT NULL, reason varchar(48),
                        PRIMARY KEY (review_id, practice_id)
                    );
                    CREATE TABLE review_item (
                        review_id uuid NOT NULL, practice_id bigint NOT NULL, item_id bigint NOT NULL,
                        reason varchar(128), PRIMARY KEY (review_id, practice_id, item_id)
                    );
                    """);
            connection.commit();
            UUID legacy = seed(connection, "readiness_refused");
            UUID current = seed(connection, "READINESS_REFUSED");
            UUID role = seed(connection, "ROLE_UNAVAILABLE");
            UUID refusal = seed(connection, "source_contract_unsupported");
            UUID open = seed(connection, null);

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

                for (var table : new String[] {"review_practice", "review_item"}) {
                    var query = "SELECT reason FROM " + table + " WHERE review_id = ?";
                    assertThat(reason(connection, query, legacy)).as(table).isEqualTo("READINESS_REFUSED");
                    assertThat(reason(connection, query, current)).as(table).isEqualTo("READINESS_REFUSED");
                    assertThat(reason(connection, query, role)).as(table).isEqualTo("ROLE_UNAVAILABLE");
                    assertThat(reason(connection, query, refusal)).as(table).isEqualTo("source_contract_unsupported");
                    assertThat(reason(connection, query, open)).as(table).isNull();
                }

                assertThatThrownBy(() -> liquibase.rollback(1, contexts, labels))
                        .hasStackTraceContaining("Restore the verified pre-upgrade database backup");
            }
        }
    }

    private static UUID seed(Connection connection, @Nullable String reason) throws SQLException {
        UUID review = UUID.randomUUID();
        try (var practice = connection.prepareStatement("INSERT INTO review_practice VALUES (?, 7, ?)");
                var item = connection.prepareStatement("INSERT INTO review_item VALUES (?, 7, 11, ?)")) {
            for (var insert : new PreparedStatement[] {practice, item}) {
                insert.setObject(1, review);
                insert.setString(2, reason);
                insert.executeUpdate();
            }
        }
        connection.commit();
        return review;
    }

    private static @Nullable String reason(Connection connection, String query, UUID review) throws SQLException {
        try (var select = connection.prepareStatement(query)) {
            select.setObject(1, review);
            try (var rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
