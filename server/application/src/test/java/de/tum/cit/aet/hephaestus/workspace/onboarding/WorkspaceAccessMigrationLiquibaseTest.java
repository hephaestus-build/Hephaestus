package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@Tag("database")
class WorkspaceAccessMigrationLiquibaseTest {
    @Test
    void requestProvenanceCannotCrossWorkspaceOrAccountBoundaries() {
        var database = PostgreSQLTestContainer.createMigratedDatabase("workspace_access_constraints");
        try (var source =
                new SingleConnectionDataSource(database.jdbcUrl(), database.username(), database.password(), true)) {
            var jdbc = new JdbcTemplate(source);
            var seed = new SchemaRowSeeder(jdbc);
            seed.insert("account", Map.of("id", 101L));
            seed.insert("account", Map.of("id", 102L));
            seed.insert("workspace", Map.of("id", 201L, "account_type", "ORG", "status", "ACTIVE"));
            seed.insert("workspace", Map.of("id", 202L, "account_type", "ORG", "status", "ACTIVE"));
            seed.insert(
                    "workspace_access_request",
                    Map.of("id", 301L, "workspace_id", 201L, "account_id", 101L, "status", "APPROVED"));
            seed.insert(
                    "workspace_account_membership",
                    Map.of(
                            "id",
                            401L,
                            "workspace_id",
                            201L,
                            "account_id",
                            101L,
                            "role",
                            "MEMBER",
                            "source",
                            "REQUEST",
                            "access_request_id",
                            301L));
            assertThatThrownBy(() ->
                            jdbc.update("UPDATE workspace_account_membership SET workspace_id = 202 WHERE id = 401"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("sfk_workspace_account_membership_request");
            assertThatThrownBy(() ->
                            jdbc.update("UPDATE workspace_account_membership SET account_id = 102 WHERE id = 401"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("sfk_workspace_account_membership_request");
            assertThatThrownBy(() -> seed.insert(
                            "workspace_access_notification",
                            Map.of(
                                    "id",
                                    501L,
                                    "workspace_id",
                                    202L,
                                    "request_id",
                                    301L,
                                    "kind",
                                    "SUBMITTED",
                                    "state",
                                    "PENDING")))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("sfk_workspace_access_notification_scope");
            assertThatThrownBy(() -> seed.insert(
                            "workspace_access_request",
                            Map.of(
                                    "id",
                                    302L,
                                    "workspace_id",
                                    201L,
                                    "account_id",
                                    102L,
                                    "status",
                                    "SUBMITTED",
                                    "supersedes_request_id",
                                    301L)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("sfk_workspace_access_request_previous");
            assertThat(jdbc.queryForObject(
                            "SELECT account_id FROM workspace_account_membership WHERE id = 401", Long.class))
                    .isEqualTo(101L);
        }
    }
}
