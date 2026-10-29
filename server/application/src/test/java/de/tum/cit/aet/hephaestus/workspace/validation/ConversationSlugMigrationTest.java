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
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ConversationSlugMigrationTest {
    private static final String CHANGELOG = "1793266450525_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();

    @Test
    void shouldStopForAnExistingWorkspaceAndRestoreThePreviousPolicyOnRollback() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("conversation_slug_upgrade");
        try (var connection = connect(database);
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
            execute(database, """
                    INSERT INTO workspace(id, slug, account_login, account_type, created_at, display_name, status)
                    VALUES (998001, 'conversation', 'slug-policy', 'ORG', now(), 'Slug policy', 'ACTIVE')
                    """);
            assertThatThrownBy(() -> liquibase.update(1, CONTEXTS, LABELS))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
            assertThat(assignable(database, "conversation")).isTrue();
            execute(database, "UPDATE workspace SET slug = 'renamed-conversation' WHERE id = 998001");
            liquibase.update(1, CONTEXTS, LABELS);
            assertThat(assignable(database, "conversation")).isFalse();
            assertThat(assignable(database, "mentor")).isFalse();
            assertThat(assignable(database, "team-example")).isTrue();
            liquibase.rollback(1, CONTEXTS, LABELS);
            assertThat(assignable(database, "conversation")).isTrue();
            assertThat(assignable(database, "mentor")).isFalse();
            liquibase.update(1, CONTEXTS, LABELS);
            assertThat(assignable(database, "conversation")).isFalse();
            try (var check = connect(database);
                    var query = check.createStatement();
                    var result = query.executeQuery("SELECT slug FROM workspace WHERE id = 998001")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("renamed-conversation");
            }
        }
    }

    @Test
    void shouldRejectConversationOnBothInsertAndUpdateAfterTheFullChain() throws Exception {
        var database = PostgreSQLTestContainer.createMigratedDatabase("conversation_slug_writes");
        assertThatThrownBy(() -> execute(database, """
                INSERT INTO workspace(id, slug, account_login, account_type, created_at, display_name, status)
                VALUES (998002, 'conversation', 'slug-policy', 'ORG', now(), 'Slug policy', 'ACTIVE')
                """))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        execute(database, """
                INSERT INTO workspace(id, slug, account_login, account_type, created_at, display_name, status)
                VALUES (998003, 'conversation-policy-test', 'slug-policy', 'ORG', now(), 'Slug policy', 'ACTIVE')
                """);
        assertThatThrownBy(() -> execute(database, "UPDATE workspace SET slug = 'conversation' WHERE id = 998003"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
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
        try (var connection = connect(PostgreSQLTestContainer.createMigratedDatabase("workspace_slug_policy"));
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

    private static boolean assignable(TestDatabase database, String label) throws SQLException {
        try (var connection = connect(database);
                var query = connection.prepareStatement("SELECT workspace_slug_assignable(?)")) {
            query.setString(1, label);
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private static void execute(TestDatabase database, String sql) throws SQLException {
        try (var connection = connect(database);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
