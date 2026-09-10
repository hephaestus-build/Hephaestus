package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.*;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
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
class WorkspaceOnboardingMigrationTest {
    private static final String CHANGELOG = "1789038452155_changelog.xml";

    @Test
    void shouldPreserveExistingAssignmentsAndEnforceWorkspaceAccountPreferences() throws Exception {
        var fixture = PostgreSQLTestContainer.createDatabase("member_onboarding_upgrade");
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                var pending = liquibase.listUnrunChangeSets(contexts, labels);
                int before = IntStream.range(0, pending.size())
                        .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                        .findFirst()
                        .orElseThrow();
                int count = (int) pending.stream()
                        .filter(change -> change.getFilePath().endsWith(CHANGELOG))
                        .count();
                assertThat(count).isEqualTo(12);
                liquibase.update(before, contexts, labels);
                liquibase.tag("before-member-onboarding");
                execute(connection, """
     INSERT INTO workspace (id, account_login, account_type, display_name, is_publicly_viewable, slug, status)
     VALUES (990101, 'onboarding-one', 'ORG', 'One', false, 'onboarding-one', 'ACTIVE'),
            (990102, 'onboarding-two', 'ORG', 'Two', false, 'onboarding-two', 'ACTIVE');
     INSERT INTO account (id, display_name) VALUES (990103, 'Member');
     INSERT INTO llm_connection (id, slug, display_name, base_url, api_protocol, created_at)
     VALUES (990104, 'onboarding-models', 'Models', 'https://models.example.invalid', 'openai-completions', now());
     INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, created_at)
     VALUES (990105, 990104, 'test', 'Test', 'test-model', now());
     INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id)
     VALUES (990106, 990101, 'PRACTICE_REVIEW', 990105);
     """);
                connection.commit();
                liquibase.update(count, contexts, labels);
                assertThat(scalar(connection, "SELECT processing_location FROM llm_model WHERE id = 990105"))
                        .isEqualTo("UNCLASSIFIED");
                assertThat(scalar(
                                connection,
                                "SELECT processing_location FROM workspace_agent_binding WHERE id = 990106"))
                        .isEqualTo("UNCLASSIFIED");
                // Before configuration is used, the additive upgrade has a safe, tested rollback.
                liquibase.rollback("before-member-onboarding", contexts, labels);
                assertThat(scalar(
                                connection,
                                "SELECT instance_model_id::text FROM workspace_agent_binding WHERE id = 990106"))
                        .isEqualTo("990105");
                liquibase.update(count, contexts, labels);
                execute(connection, """
     INSERT INTO workspace_agent_binding (workspace_id, purpose, instance_model_id, processing_location)
     VALUES (990101, 'PRACTICE_REVIEW', 990105, 'ON_PREMISES'), (990101, 'PRACTICE_REVIEW', 990105, 'PRIVATE_CLOUD');
     INSERT INTO workspace_member_onboarding (workspace_id, account_id, ai_choice, updated_at)
     VALUES (990101, 990103, 'NO_AI', now()), (990102, 990103, 'ON_PREMISES', now());
     """);
                connection.commit();
                assertThat(
                                scalar(
                                        connection,
                                        "SELECT ai_choice FROM workspace_member_onboarding WHERE workspace_id = 990101 AND account_id = 990103"))
                        .isEqualTo("NO_AI");
                assertThatThrownBy(
                                () -> execute(
                                        connection,
                                        "INSERT INTO workspace_member_onboarding (workspace_id, account_id, updated_at) VALUES (990101, 990103, now())"))
                        .hasMessageContaining("ux_member_onboarding_workspace_account");
                connection.rollback();
                assertThatThrownBy(
                                () -> execute(
                                        connection,
                                        "INSERT INTO workspace_member_onboarding (workspace_id, account_id, updated_at) VALUES (990101, 999999999, now())"))
                        .hasMessageContaining("sfk_member_onboarding_account");
                connection.rollback();
                assertThatThrownBy(
                                () -> execute(
                                        connection,
                                        "UPDATE workspace_member_onboarding SET ai_choice = 'AUTOMATIC' WHERE workspace_id = 990101 AND account_id = 990103"))
                        .hasMessageContaining("ck_member_onboarding_ai_choice");
                connection.rollback();
                assertThatThrownBy(() -> execute(
                                connection, "UPDATE llm_model SET processing_location = 'AUTOMATIC' WHERE id = 990105"))
                        .hasMessageContaining("ck_llm_model_processing_location");
                connection.rollback();
                assertThatThrownBy(
                                () -> execute(
                                        connection,
                                        "INSERT INTO workspace_onboarding_settings (workspace_id, enabled, ai_choice_required, required_connection_ids, welcome_markdown) VALUES (990101, true, false, '[]', '')"))
                        .hasMessageContaining("ck_onboarding_requires_choice");
                connection.rollback();
                assertThatThrownBy(() -> liquibase.rollback("before-member-onboarding", contexts, labels))
                        .hasStackTraceContaining("instead of discarding AI choices");
                assertThat(
                                scalar(
                                        connection,
                                        "SELECT ai_choice FROM workspace_member_onboarding WHERE workspace_id = 990101 AND account_id = 990103"))
                        .isEqualTo("NO_AI");
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return java.util.Objects.requireNonNull(rows.getString(1));
        }
    }
}
