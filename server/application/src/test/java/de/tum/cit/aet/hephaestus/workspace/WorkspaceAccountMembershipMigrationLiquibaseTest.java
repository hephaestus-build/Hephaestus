package de.tum.cit.aet.hephaestus.workspace;

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
class WorkspaceAccountMembershipMigrationLiquibaseTest {
    @Test
    void shouldConsolidateOnlyVerifiedOwnersAndPermitOidcAfterMigration() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("account_membership_migration");
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            seedSchema(connection);
            execute(connection, """
                INSERT INTO account VALUES (1, 'ACTIVE'), (2, 'ACTIVE'), (3, 'ACTIVE');
                INSERT INTO workspace VALUES (10), (20);
                INSERT INTO identity_provider VALUES (100, 'GITHUB'), (200, 'GITLAB'), (300, 'OIDC');
                INSERT INTO "user" VALUES (1, 100, 101, 'USER'), (2, 200, 202, 'USER'), (3, 100, 303, 'USER'), (4, 200, 404, 'USER');
                INSERT INTO identity_link VALUES (1, 1, 100, '101', NULL, NULL), (2, 1, 200, '202', NULL, NULL),
                    (3, 2, 100, '303', NULL, now()), (4, 3, 300, '404', NULL, NULL);
                INSERT INTO workspace_membership VALUES (10, 1, 'MEMBER', now()), (10, 2, 'OWNER', now()),
                    (10, 3, 'ADMIN', now()), (20, 4, 'MEMBER', now());
                """);
            update(connection);
        }
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT account_id, workspace_id, role, source FROM workspace_account_membership")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("account_id")).isEqualTo(1);
                assertThat(rows.getLong("workspace_id")).isEqualTo(10);
                assertThat(rows.getString("role")).isEqualTo("OWNER");
                assertThat(rows.getString("source")).isEqualTo("MIGRATED");
                assertThat(rows.next()).isFalse();
            }
            statement.execute("INSERT INTO login_provider VALUES ('OIDC')");
            assertThatThrownBy(() -> statement.execute("INSERT INTO login_provider VALUES ('UNTRUSTED')"))
                    .hasMessageContaining("ck_login_provider_type");
            try (var rows = statement.executeQuery("SELECT count(*) FROM workspace_membership")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(4);
            }
        }
    }

    @Test
    void shouldStopUpgradeWhenNoExistingOwnerHasAVerifiedActiveAccount() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("account_membership_orphan_owner");
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            seedSchema(connection);
            execute(
                    connection,
                    "INSERT INTO workspace VALUES (10); INSERT INTO workspace_membership VALUES (10, 1, 'OWNER', now())");
            assertThatThrownBy(() -> update(connection))
                    .hasStackTraceContaining("Link at least one existing workspace owner");
        }
    }

    private static void seedSchema(Connection connection) throws Exception {
        execute(connection, """
            CREATE TABLE account (id bigint PRIMARY KEY, status text NOT NULL);
            CREATE TABLE workspace (id bigint PRIMARY KEY);
            CREATE TABLE identity_provider (id bigint PRIMARY KEY, type text NOT NULL);
            CREATE TABLE "user" (id bigint PRIMARY KEY, provider_id bigint, native_id bigint, type text);
            CREATE TABLE identity_link (id bigint PRIMARY KEY, account_id bigint, provider_id bigint, subject text, team_id text, disabled_at timestamptz);
            CREATE TABLE workspace_membership (workspace_id bigint, user_id bigint, role text, created_at timestamptz);
            CREATE TABLE login_provider (type text CONSTRAINT ck_login_provider_type CHECK (type IN ('GITHUB', 'GITLAB', 'SLACK', 'OUTLINE')));
            """);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void update(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var liquibase = new Liquibase(
                "db/changelog/1788882358341_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(new Contexts());
        }
    }
}
