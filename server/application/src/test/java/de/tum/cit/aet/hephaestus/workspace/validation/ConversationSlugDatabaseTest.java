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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ConversationSlugDatabaseTest {

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
