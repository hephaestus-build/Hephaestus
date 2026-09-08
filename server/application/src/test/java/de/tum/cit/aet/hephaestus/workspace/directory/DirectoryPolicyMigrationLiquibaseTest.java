package de.tum.cit.aet.hephaestus.workspace.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DirectoryPolicyMigrationLiquibaseTest {
    @Test
    void shouldBackfillExistingProvidersAndEnforceWorkspaceBoundConnectionsAndMembershipProvenance() throws Exception {
        var fixture = PostgreSQLTestContainer.createDatabase("directory_policy_migration");
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            execute(connection, """
                CREATE TABLE workspace (id bigint PRIMARY KEY);
                CREATE TABLE account (id bigint PRIMARY KEY);
                CREATE TABLE identity_provider (id bigint PRIMARY KEY);
                CREATE TABLE login_provider (id bigint PRIMARY KEY);
                CREATE TABLE connection (id bigint PRIMARY KEY, workspace_id bigint NOT NULL,
                    kind text CONSTRAINT ck_connection_kind CHECK (kind IN ('GITHUB','GITLAB','SLACK','OUTLINE')),
                    UNIQUE (id,workspace_id));
                CREATE TABLE workspace_account_membership (id bigint PRIMARY KEY, workspace_id bigint,
                    account_id bigint, source text NOT NULL);
                CREATE TABLE config_audit_event (entity_type text CONSTRAINT ck_config_audit_event_entity_type CHECK (entity_type IN ('WORKSPACE_ROLE','WORKSPACE_ACCESS_POLICY','WORKSPACE_ACCESS_REQUEST')));
                INSERT INTO config_audit_event VALUES ('WORKSPACE_ACCESS_POLICY'), ('WORKSPACE_ACCESS_REQUEST');
                INSERT INTO workspace VALUES (1),(2);
                INSERT INTO account VALUES (10);
                INSERT INTO identity_provider VALUES (100);
                INSERT INTO login_provider VALUES (200);
                INSERT INTO connection VALUES (300,1,'GITHUB'),(301,2,'GITHUB');
                INSERT INTO workspace_account_membership VALUES (400,1,10,'MANUAL');
                """);
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    "db/changelog/1788891422831_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password());
                var statement = connection.createStatement()) {
            try (var rows =
                    statement.executeQuery("SELECT directory_group_ids::text FROM login_provider WHERE id=200")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("[]");
            }
            try (var rows = statement.executeQuery(
                    "SELECT column_default FROM information_schema.columns WHERE table_name='login_provider' AND column_name='directory_group_ids'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNull();
            }
            statement.execute("UPDATE connection SET kind='KEYCLOAK_DIRECTORY' WHERE id=300");
            statement.execute(
                    "INSERT INTO config_audit_event VALUES ('DIRECTORY_POLICY'), ('WORKSPACE_ACCESS_POLICY'), ('WORKSPACE_ACCESS_REQUEST')");
            statement.execute(policyInsert(1, 300));
            assertThatThrownBy(() -> statement.execute(policyInsert(2, 300)))
                    .hasMessageContaining("sfk_directory_policy_connection_workspace");
            assertThatThrownBy(() -> statement.execute(policyInsert(1, 300)))
                    .hasMessageContaining("uq_directory_policy_workspace");
            assertThatThrownBy(() -> statement.execute(
                            "UPDATE workspace_account_membership SET directory_subject='subject' WHERE id=400"))
                    .hasMessageContaining("ck_membership_directory_subject");
            statement.execute(
                    "UPDATE workspace_account_membership SET source='DIRECTORY', directory_subject='subject' WHERE id=400");
            assertThatThrownBy(() -> statement.execute(
                            "UPDATE workspace_account_membership SET directory_subject=NULL WHERE id=400"))
                    .hasMessageContaining("ck_membership_directory_subject");
            statement.execute("DELETE FROM account WHERE id=10");
            try (var rows = statement.executeQuery(
                    "SELECT approved_by_account_id FROM directory_policy WHERE workspace_id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1)).isNull();
            }
        }
    }

    private static String policyInsert(int workspace, int connection) {
        return """
            INSERT INTO directory_policy(workspace_id,connection_id,identity_provider_id,registration_id,issuer,status,health,
                configuration_version,version,draft_group_ids,approved_group_ids,approved_by_account_id)
            VALUES (%d,%d,100,'organization','https://identity.example.com/realms/team','DRAFT','UNVERIFIED',1,0,'[]','[]',10)
            """.formatted(workspace, connection);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
