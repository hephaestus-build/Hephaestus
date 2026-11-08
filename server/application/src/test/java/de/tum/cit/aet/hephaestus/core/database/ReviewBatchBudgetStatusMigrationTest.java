package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ReviewBatchBudgetStatusMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("review_batch_budget_status");
    private static final String CHANGELOG = "db/changelog/next/20261107202254-fix-scheduled-batch-cap-exhaustion.xml";

    @Test
    void shouldPreserveRowsAndGuardARollbackWhileTheNewStatusIsInUse() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE review_batch (
                        id uuid PRIMARY KEY,
                        status varchar(24) NOT NULL,
                        CONSTRAINT ck_review_batch_status CHECK (status IN
                            ('AWAITING_CONFIRMATION','RUNNING','PAUSED','COMPLETED','CANCELLED'))
                    )
                    """);
            UUID paused = insert(connection, "PAUSED");
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);
                UUID ended = insert(connection, "BUDGET_EXHAUSTED");

                assertThatThrownBy(() -> liquibase.rollback(1, contexts, labels))
                        .hasStackTraceContaining("Restore a verified backup before downgrading");
                database.rollback();
                assertThat(status(connection, ended)).isEqualTo("BUDGET_EXHAUSTED");
                assertThat(status(connection, paused)).isEqualTo("PAUSED");

                try (var cancel =
                        connection.prepareStatement("UPDATE review_batch SET status='CANCELLED' WHERE id=?")) {
                    cancel.setObject(1, ended);
                    cancel.executeUpdate();
                }
                database.commit();
                liquibase.rollback(1, contexts, labels);

                assertThat(status(connection, paused)).isEqualTo("PAUSED");
                assertThat(status(connection, ended)).isEqualTo("CANCELLED");
                assertThatThrownBy(() -> insert(connection, "BUDGET_EXHAUSTED"))
                        .hasMessageContaining("ck_review_batch_status");
            }
        }
    }

    private static UUID insert(Connection connection, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO review_batch(id,status) VALUES(?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, status);
            statement.executeUpdate();
        }
        connection.commit();
        return id;
    }

    private static String status(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT status FROM review_batch WHERE id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
