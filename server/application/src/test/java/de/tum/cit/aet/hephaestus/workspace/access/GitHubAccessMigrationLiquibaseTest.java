package de.tum.cit.aet.hephaestus.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class GitHubAccessMigrationLiquibaseTest {
    @Test
    void shouldEnforceNativeScopeAuthorityTenancyAndConcurrentTransferInPostgres() throws Exception {
        var fixture = PostgreSQLTestContainer.createDatabase("github_access_migration");
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            createParentTables(connection);
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    "db/changelog/1788900580363_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            execute(connection, target(1, 1, 1, 1000, 0));
            assertThatThrownBy(() -> execute(connection, "UPDATE github_access_target SET source='REQUEST' WHERE id=1"))
                    .hasMessageContaining("ck_github_access_target_source");
            execute(connection, """
                UPDATE github_access_target SET source='REQUEST', directory_provider_id=NULL,
                    registration_id=NULL, issuer=NULL WHERE id=1
                """);
            assertThatThrownBy(
                            () -> execute(connection, "UPDATE github_access_target SET request_team_id=99 WHERE id=1"))
                    .hasMessageContaining("ck_github_access_target_source");
            execute(connection, target(2, 1, 2, 1000, 10));
            assertThatThrownBy(() -> execute(connection, """
                UPDATE github_access_target SET source='REQUEST', directory_provider_id=NULL,
                    registration_id=NULL, issuer=NULL WHERE id=2
                """)).hasMessageContaining("ck_github_access_target_source");
            execute(connection, target(3, 1, 3, 1001, 0));
            assertThatThrownBy(() -> execute(connection, target(4, 2, 20, 1000, 20)))
                    .hasMessageContaining("ex_github_access_authority");
            execute(connection, target(5, 1, 5, 2000, 10));
            execute(connection, target(6, 2, 21, 2000, 20));
            assertThatThrownBy(() -> execute(connection, target(7, 2, 22, 2000, 0)))
                    .hasMessageContaining("ex_github_access_authority");
            assertThatThrownBy(() -> execute(connection, target(8, 1, 6, 1000, 0)))
                    .hasMessageContaining("uq_github_access_held_scope");
            assertThatThrownBy(() -> execute(connection, target(9, 2, 7, 3000, 0)))
                    .hasMessageContaining("sfk_github_access_target_connection_workspace");
            execute(
                    connection,
                    "UPDATE github_access_target SET authority_held=false,status='ENDED' WHERE organization_id=1000");
            execute(connection, target(10, 2, 23, 1000, 0));
            execute(connection, "UPDATE connection SET kind='GITHUB_ACCESS' WHERE id=23");
            execute(
                    connection,
                    "INSERT INTO config_audit_event VALUES ('GITHUB_ACCESS_POLICY'),('GITHUB_ACCESS_MEMBERSHIP'),('WORKSPACE_ACCESS_POLICY'),('WORKSPACE_ACCESS_REQUEST'); INSERT INTO auth_event VALUES ('GITHUB_ACCESS_AUTHORIZED')");
            assertThatThrownBy(() -> execute(connection, """
                INSERT INTO github_access_membership(id,workspace_id,target_id,github_user_id,enrolled,managed,manual_exception,revocation_requested,version)
                VALUES (1,1,10,60,true,true,false,false,0)
                """))
                    .hasMessageContaining("sfk_github_access_membership_target_workspace");
            execute(connection, """
                INSERT INTO github_access_membership(id,workspace_id,target_id,github_user_id,enrolled,managed,manual_exception,revocation_requested,version)
                VALUES (1,2,10,60,true,true,false,false,0)
                """);
            assertThatThrownBy(() -> execute(connection, action(1, 1, 3)))
                    .hasMessageContaining("sfk_github_access_action_membership_scope");
            execute(connection, action(1, 2, 10));
            assertThatThrownBy(() -> execute(connection, action(2, 2, 10)))
                    .hasMessageContaining("uq_github_access_unresolved_action");
        }
        try (var first = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password());
                var second = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            first.setAutoCommit(false);
            execute(first, target(11, 1, 8, 4000, 0));
            var waiting = executor.submit(() -> {
                try {
                    execute(second, target(12, 2, 24, 4000, 10));
                    return "incorrectly acquired";
                } catch (Exception conflict) {
                    return conflict.getMessage();
                }
            });
            // The second insert cannot decide until the competing transaction commits.
            assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            first.commit();
            assertThat(waiting.get(10, TimeUnit.SECONDS)).contains("ex_github_access_authority");
        }
    }

    @Test
    void shouldPreserveRecordedActionEvidenceAndInvalidateOldPreviewsWhenAddingRequestEligibility() throws Exception {
        var fixture = PostgreSQLTestContainer.createDatabase("github_access_source_migration");
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            createParentTables(connection);
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(
                    "db/changelog/1788900580363_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(18, new Contexts(), new liquibase.LabelExpression());
                execute(connection, """
                    INSERT INTO github_access_target(id,workspace_id,connection_id,directory_provider_id,registration_id,
                        issuer,status,requested_organization,pending_installation_id,organization_id,scope_id,
                        authority_held,paused,configuration_version,version,draft_group_ids,approved_group_ids,preview)
                    VALUES (1,1,1,100,'organization','https://identity.example.com/realms/team','DRAFT','example-org',
                        500,1000,0,true,false,1,0,'[]','[]','{"directory":{}}');
                    INSERT INTO github_access_membership(id,workspace_id,target_id,github_user_id,enrolled,managed,
                        manual_exception,revocation_requested,version)
                    VALUES (1,1,1,60,true,true,false,false,0);
                    """);
                execute(connection, action(1, 1, 1));
                execute(connection, """
                    UPDATE github_access_action SET directory_configuration_version=7,
                        directory_capture_started_at='2026-09-01T00:00:00Z', directory_source_version='2026-08-31T00:00:00Z'
                    WHERE id=1;
                    """);
                liquibase.update(new Contexts());
                try (var statement = connection.createStatement()) {
                    try (var row =
                            statement.executeQuery("SELECT source, preview FROM github_access_target WHERE id=1")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo("DIRECTORY");
                        assertThat(row.getObject(2)).isNull();
                    }
                    try (var row = statement.executeQuery("""
                            SELECT eligibility_configuration_version, eligibility_captured_at, eligibility_source_version
                            FROM github_access_action WHERE id=1
                            """)) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getLong(1)).isEqualTo(7);
                        assertThat(row.getTimestamp(2).toInstant())
                                .isEqualTo(java.time.Instant.parse("2026-09-01T00:00:00Z"));
                        assertThat(row.getTimestamp(3).toInstant())
                                .isEqualTo(java.time.Instant.parse("2026-08-31T00:00:00Z"));
                    }
                }
            }
        }
    }

    private static void createParentTables(Connection connection) throws Exception {
        execute(connection, """
                CREATE EXTENSION IF NOT EXISTS btree_gist;
                CREATE TABLE workspace (id bigint PRIMARY KEY);
                CREATE TABLE account (id bigint PRIMARY KEY);
                CREATE TABLE identity_provider (id bigint PRIMARY KEY);
                CREATE TABLE connection (id bigint PRIMARY KEY, workspace_id bigint NOT NULL,
                    kind text CONSTRAINT ck_connection_kind CHECK (kind IN ('GITHUB','KEYCLOAK_DIRECTORY')), UNIQUE (id,workspace_id));
                CREATE TABLE config_audit_event (entity_type text CONSTRAINT ck_config_audit_event_entity_type CHECK (entity_type IN ('DIRECTORY_POLICY','WORKSPACE_ACCESS_POLICY','WORKSPACE_ACCESS_REQUEST')));
                INSERT INTO config_audit_event VALUES ('WORKSPACE_ACCESS_POLICY'),('WORKSPACE_ACCESS_REQUEST');
                CREATE TABLE auth_event (event_type text CONSTRAINT ck_auth_event_event_type CHECK (event_type='LOGIN'));
                INSERT INTO workspace VALUES (1),(2);
                INSERT INTO account VALUES (10);
                INSERT INTO identity_provider VALUES (100);
                INSERT INTO connection SELECT n, CASE WHEN n < 20 THEN 1 ELSE 2 END, 'GITHUB' FROM generate_series(1,40) n;
                """);
    }

    private static String target(int id, int workspace, int connection, long organization, long scope) {
        return """
            INSERT INTO github_access_target(source,id,workspace_id,connection_id,directory_provider_id,registration_id,issuer,status,
                requested_organization,pending_installation_id,organization_id,scope_id,authority_held,paused,configuration_version,
                version,draft_group_ids,approved_group_ids)
            VALUES ('DIRECTORY',%d,%d,%d,100,'organization','https://identity.example.com/realms/team','DRAFT','example-org',500,%d,%d,true,false,1,0,'[]','[]')
            """.formatted(id, workspace, connection, organization, scope);
    }

    private static String action(int id, int workspace, int target) {
        return """
            INSERT INTO github_access_action(id,workspace_id,target_id,membership_id,organization_id,scope_id,github_user_id,
                configuration_version,type,status,created_at,attempts)
            VALUES (%d,%d,%d,1,1000,0,60,1,'REVOKE','PENDING',now(),0)
            """.formatted(id, workspace, target);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
