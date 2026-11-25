package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.ReadyExportFence;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The export fence's two guards in the database: the application cannot call the rule that expires an account's ready
 * exports outside the triggers of its sources, and no session inserts or publishes a ready export without the owner's
 * export fence.
 */
@Tag("database")
class AccountExportFenceDatabaseTest {

    private static final long OWNER = 979401;

    private static final TestDatabase INSTALLED = install();

    private static TestDatabase install() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_export_fence");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_export_fence");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            jdbc(connection).update("INSERT INTO account(id, display_name) VALUES (?, 'Reader')", OWNER);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed the export fence database", e);
        }
        return database;
    }

    @Test
    void shouldRefuseADirectCallOfTheRuleThatExpiresExports() throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(INSTALLED))) {
            assertThatThrownBy(() -> jdbc(connection)
                            .queryForObject(
                                    "SELECT public.person_row_sweep('EXPORT_SOURCES_CHANGED', ?, NULL, NULL)",
                                    Integer.class,
                                    OWNER))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("runs only from the triggers of its sources");
        }
    }

    @Test
    void shouldInsertAReadyExportOnlyUnderItsOwnersFence() throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
            var owner = jdbc(connection);
            assertThatThrownBy(() -> owner.update(
                            "INSERT INTO account_export(account_id, status, expires_at, payload)"
                                    + " VALUES (?, 'READY', now() + interval '1 day', '\\x01')",
                            OWNER))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("published only under its owner's export fence");

            Long fenced = owner.queryForObject(
                    ReadyExportFence.CTE
                            + "INSERT INTO account_export(account_id, status, expires_at, payload)"
                            + " SELECT ?, 'READY', now() + interval '1 day', '\\x01' FROM fence RETURNING id",
                    Long.class,
                    OWNER,
                    OWNER);
            assertThat(owner.queryForObject("SELECT status FROM account_export WHERE id = ?", String.class, fenced))
                    .isEqualTo("READY");
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
