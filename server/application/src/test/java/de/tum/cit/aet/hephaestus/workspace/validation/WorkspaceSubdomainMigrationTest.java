package de.tum.cit.aet.hephaestus.workspace.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class WorkspaceSubdomainMigrationTest {
    private static final String CHANGELOG = "1791582260680_changelog.xml";
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("workspace_subdomain_migration");
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();

    @BeforeAll
    static void migrateExistingAddresses() throws Exception {
        try (var connection = connect();
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(before, CONTEXTS, LABELS);
            execute(connection, """
                    INSERT INTO workspace(id, slug, account_login, account_type, created_at, display_name, is_publicly_viewable, status)
                    VALUES (997001, 'docs', 'docs', 'ORG', now(), 'Docs', false, 'ACTIVE'),
                           (997002, 'team-', 'team', 'ORG', now(), 'Team', false, 'ACTIVE'),
                           (997003, 'ls1intum', 'ls1intum', 'ORG', now(), 'LS1', false, 'ACTIVE'),
                           (997004, 'workspace-997001', 'collision', 'ORG', now(), 'Collision', false, 'ACTIVE'),
                           (997005, 'xn--old', 'old', 'ORG', now(), 'Old', false, 'ACTIVE'),
                           (997006, 'Mixed_Case', 'mixed', 'ORG', now(), 'Mixed', false, 'ACTIVE'),
                           (997007, 'a--b', 'consecutive', 'ORG', now(), 'Consecutive', false, 'ACTIVE'),
                           (997008, 'a', 'short-one', 'ORG', now(), 'Short', false, 'ACTIVE'),
                           (997009, 'ab', 'short-two', 'ORG', now(), 'Short', false, 'ACTIVE'),
                           (997010, repeat('a', 52), 'long', 'ORG', now(), 'Long', false, 'ACTIVE'),
                           (997011, repeat('a', 51), 'boundary', 'ORG', now(), 'Boundary', false, 'ACTIVE');
                    INSERT INTO workspace_slug_history(workspace_id, old_slug, new_slug, changed_at, redirect_expires_at)
                    VALUES (997003, 'historic-team', 'ls1intum', now(), now() - interval '1 day'),
                           (997003, 'workspace-997002', 'historic-team', now(), now() - interval '2 days');
                    """);
            connection.commit();
            liquibase.update(
                    (int) pending.stream()
                            .filter(c -> c.getFilePath().endsWith(CHANGELOG))
                            .count(),
                    CONTEXTS,
                    LABELS);
        }
    }

    @Test
    void shouldPreserveValidLabelsAndRedirectInvalidLabelsWhenMigrationRuns() throws Exception {
        try (var connection = connect()) {
            assertThat(strings(connection, "SELECT slug FROM workspace WHERE id BETWEEN 997001 AND 997011 ORDER BY id"))
                    .containsExactly(
                            "workspace-997001-1",
                            "workspace-997002-1",
                            "ls1intum",
                            "workspace-997001",
                            "workspace-997005",
                            "workspace-997006",
                            "workspace-997007",
                            "workspace-997008",
                            "workspace-997009",
                            "workspace-997010",
                            "a".repeat(51));
            assertThat(strings(
                            connection,
                            "SELECT old_slug FROM workspace_slug_history WHERE workspace_id BETWEEN 997001 AND 997011"))
                    .containsExactlyInAnyOrder(
                            "Mixed_Case",
                            "docs",
                            "historic-team",
                            "team-",
                            "workspace-997002",
                            "xn--old",
                            "a--b",
                            "a",
                            "ab",
                            "a".repeat(52));
            assertThat(
                            strings(
                                    connection,
                                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'workspace_slug_history' AND column_name = 'redirect_expires_at'"))
                    .isEmpty();
        }
    }

    @Test
    void shouldKeepDatabasePolicyEqualToJavaPolicyForReservedAndAdversarialLabels() throws Exception {
        List<String> labels = new ArrayList<>(WorkspaceSlugValidator.reservedLabels());
        labels.addAll(List.of(
                "a",
                "1",
                "team",
                "a--b",
                "ab--cd",
                "xn--name",
                "team-",
                "-team",
                "pr1",
                "prx",
                "UPPER",
                "tëam",
                "with.dot",
                "with_under",
                "a".repeat(51),
                "a".repeat(52),
                "a".repeat(64),
                ""));
        try (var connection = connect();
                var query = connection.prepareStatement("SELECT workspace_slug_assignable(?)")) {
            for (String label : labels) {
                query.setString(1, label);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).as(label).isEqualTo(WorkspaceSlugValidator.isAssignable(label));
                }
            }
        }
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
            assertThatThrownBy(() -> insert(connection, 997103, "historic-team"))
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
                INSERT INTO workspace(id, slug, account_login, account_type, created_at, display_name, is_publicly_viewable, status)
                VALUES (?, ?, 'migration-test', 'ORG', now(), 'Migration test', false, 'ACTIVE')
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

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
