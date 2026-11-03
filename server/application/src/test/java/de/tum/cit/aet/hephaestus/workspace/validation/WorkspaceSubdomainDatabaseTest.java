package de.tum.cit.aet.hephaestus.workspace.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class WorkspaceSubdomainDatabaseTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("workspace_subdomain");

    @BeforeAll
    static void migrate() {
        PostgreSQLTestContainer.migrateDatabase(DATABASE, "prod");
    }

    @Test
    void shouldRejectReuseWhenWorkspaceWasRenamedOrDeleted() throws Exception {
        try (var connection = connect()) {
            insert(connection, 997100, "permanent-claim");
            execute(connection, "UPDATE workspace SET slug = 'permanent-renamed' WHERE id = 997100");
            execute(connection, "DELETE FROM workspace WHERE id = 997100");
            assertThatThrownBy(() -> insert(connection, 997101, "permanent-claim"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(connection, 997102, "permanent-renamed"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(connection, 997104, "docs")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void shouldAllowOnlyOneClaimWhenConcurrentTransactionsUseTheSameSlug() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> claim(997201, ready, start));
            var second = executor.submit(() -> claim(997202, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private static boolean claim(long id, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (var connection = connect()) {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent claim test did not start.");
            }
            try {
                insert(connection, id, "concurrent-claim");
                return true;
            } catch (SQLException exception) {
                assertThat(exception.getSQLState()).isEqualTo("23505");
                return false;
            }
        }
    }

    private static void insert(Connection connection, long id, String slug) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO workspace(id, slug, account_login, account_type, created_at, display_name, status)
                VALUES (?, ?, 'migration-test', 'ORG', now(), 'Migration test', 'ACTIVE')
                """)) {
            statement.setLong(1, id);
            statement.setString(2, slug);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
