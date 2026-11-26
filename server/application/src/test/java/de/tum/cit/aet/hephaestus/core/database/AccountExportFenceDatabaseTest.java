package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.ReadyExportFence;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
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

    /**
     * Two transactions erase messages of two owners in opposite orders. Each locks the ready exports in id order
     * after its source writes.
     */
    @Test
    void shouldExpireTheExportsOfTwoOwnersFromCrossedTransactionsWithoutADeadlock() throws Exception {
        long first = 979411;
        long second = 979412;
        UUID[] firstMessages = {UUID.randomUUID(), UUID.randomUUID()};
        UUID[] secondMessages = {UUID.randomUUID(), UUID.randomUUID()};
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
            var owner = jdbc(connection);
            owner.update("""
                    INSERT INTO workspace (id, account_login, account_type, display_name, slug, status)
                    VALUES (979410, 'crossed-org', 'ORG', 'Crossed', 'crossed-org', 'ACTIVE')
                    """);
            seedOwner(owner, first, firstMessages);
            seedOwner(owner, second, secondMessages);
        }
        try (Connection one = connect(PostgreSQLTestContainer.asOwner(INSTALLED));
                Connection two = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
            one.setAutoCommit(false);
            two.setAutoCommit(false);
            var oneJdbc = jdbc(one);
            var twoJdbc = jdbc(two);
            Integer twoPid = twoJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);

            erase(oneJdbc, firstMessages[0]);
            var crossed = CompletableFuture.runAsync(() -> {
                erase(twoJdbc, secondMessages[0]);
                erase(twoJdbc, firstMessages[1]);
                try {
                    two.commit();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            try (Connection observer = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(10))
                        .until(() -> crossed.isDone()
                                || Boolean.TRUE.equals(jdbc(observer)
                                        .queryForObject(
                                                "SELECT EXISTS (SELECT FROM pg_locks WHERE pid = ? AND NOT granted)",
                                                Boolean.class,
                                                twoPid)));
            }
            erase(oneJdbc, secondMessages[1]);
            one.commit();
            crossed.get(10, TimeUnit.SECONDS);
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
            assertThat(jdbc(connection)
                            .queryForList(
                                    "SELECT status FROM account_export WHERE account_id IN (?, ?)",
                                    String.class,
                                    first,
                                    second))
                    .hasSize(2)
                    .containsOnly("EXPIRED");
        }
    }

    private static void seedOwner(JdbcTemplate owner, long account, UUID[] messages) {
        owner.update("INSERT INTO account(id, display_name) VALUES (?, 'Crossed owner')", account);
        UUID conversation = UUID.randomUUID();
        owner.update(
                "INSERT INTO conversation (id, created_at, last_activity_at, account_id, workspace_id)"
                        + " VALUES (?, now(), now(), ?, 979410)",
                conversation,
                account);
        for (int seq = 0; seq < messages.length; seq++) {
            owner.update(
                    "INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text,"
                            + " workspace_id, account_id) VALUES (?, ?, now(), 'USER', ?, 'APP', 'A message', 979410, ?)",
                    messages[seq],
                    conversation,
                    seq + 1,
                    account);
        }
        owner.queryForObject(
                ReadyExportFence.CTE
                        + "INSERT INTO account_export(account_id, status, expires_at, payload)"
                        + " SELECT ?, 'READY', now() + interval '1 day', '\\x01' FROM fence RETURNING id",
                Long.class,
                account,
                account);
    }

    private static void erase(JdbcTemplate session, UUID message) {
        session.update("UPDATE conversation_entry SET redacted_at = now(), text = '' WHERE id = ?", message);
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
