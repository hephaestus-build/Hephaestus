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
    void shouldPreserveExistingAssignmentsAndEnforceDeclaredFactsAndChoices() throws Exception {
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
                assertThat(count).isEqualTo(16);
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
                // A model that exists on main lands with no facts declared: it serves the undeclared slot only.
                assertThat(
                                scalar(
                                        connection,
                                        "SELECT operated_by IS NULL AND kept_after_reply IS NULL AND data_handling_note IS NULL FROM llm_model WHERE id = 990105"))
                        .isEqualTo("t");
                assertThat(scalar(
                                connection, "SELECT data_handling_tier FROM workspace_agent_binding WHERE id = 990106"))
                        .isEqualTo("UNDECLARED");
                // Before configuration is used, the additive upgrade has a safe, tested rollback.
                liquibase.rollback("before-member-onboarding", contexts, labels);
                assertThat(scalar(
                                connection,
                                "SELECT instance_model_id::text FROM workspace_agent_binding WHERE id = 990106"))
                        .isEqualTo("990105");
                liquibase.update(count, contexts, labels);
                execute(connection, """
     UPDATE llm_model SET operated_by = 'PROVIDER', kept_after_reply = 'NONE', data_handling_note = 'EU region, DPA renews 2027-03' WHERE id = 990105;
     INSERT INTO workspace_llm_connection (id, workspace_id, slug, display_name, base_url, api_protocol, created_at)
     VALUES (990107, 990101, 'own-models', 'Own models', 'https://own.example.invalid', 'openai-completions', now());
     INSERT INTO workspace_llm_model (id, workspace_id, connection_id, slug, display_name, upstream_model_id, operated_by, kept_after_reply, created_at)
     VALUES (990108, 990101, 990107, 'own', 'Own', 'own-model', 'OWN_ORGANISATION', 'NONE', now());
     INSERT INTO workspace_agent_binding (workspace_id, purpose, instance_model_id, data_handling_tier)
     VALUES (990101, 'PRACTICE_REVIEW', 990105, 'IN_HOUSE'), (990101, 'PRACTICE_REVIEW', 990105, 'PROVIDER_NOT_KEPT'), (990101, 'PRACTICE_REVIEW', 990105, 'PROVIDER_KEPT');
     INSERT INTO workspace_member_onboarding (workspace_id, account_id, ai_choice, updated_at)
     VALUES (990101, 990103, 'NO_AI', now()), (990102, 990103, 'IN_HOUSE_ONLY', now());
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
                assertThatThrownBy(() ->
                                execute(connection, "UPDATE llm_model SET operated_by = 'AUTOMATIC' WHERE id = 990105"))
                        .hasMessageContaining("ck_llm_model_operated_by");
                connection.rollback();
                assertThatThrownBy(() -> execute(
                                connection, "UPDATE llm_model SET kept_after_reply = 'FOREVER' WHERE id = 990105"))
                        .hasMessageContaining("ck_llm_model_kept_after_reply");
                connection.rollback();
                assertThatThrownBy(() -> execute(
                                connection,
                                "UPDATE workspace_llm_model SET operated_by = 'AUTOMATIC' WHERE id = 990108"))
                        .hasMessageContaining("ck_workspace_llm_model_operated_by");
                connection.rollback();
                assertThatThrownBy(() -> execute(
                                connection,
                                "UPDATE workspace_llm_model SET kept_after_reply = 'FOREVER' WHERE id = 990108"))
                        .hasMessageContaining("ck_workspace_llm_model_kept_after_reply");
                connection.rollback();
                assertThatThrownBy(
                                () -> execute(
                                        connection,
                                        "UPDATE workspace_agent_binding SET data_handling_tier = 'AUTOMATIC' WHERE id = 990106"))
                        .hasMessageContaining("ck_agent_binding_data_handling_tier");
                connection.rollback();
                assertThatThrownBy(
                                () -> execute(
                                        connection,
                                        "INSERT INTO workspace_onboarding_settings (workspace_id, enabled, ai_choice_required, required_connection_ids) VALUES (990101, true, false, '[]')"))
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
