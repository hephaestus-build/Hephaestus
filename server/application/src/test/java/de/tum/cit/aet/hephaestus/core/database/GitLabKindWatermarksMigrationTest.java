package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Earlier releases moved a GitLab monitor's issue or merge request watermark past a pass that stopped at the page
 * cap, so every GitLab monitor starts again from its project's watermark, also on a rerun; GitHub monitors stay.
 */
@Tag("database")
class GitLabKindWatermarksMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("gitlab_kind_watermarks");
    private static final String CHANGELOG =
            "db/changelog/next/20261121123743-fix-gitlab-sync-continues-capped-window.xml";
    private static final Instant PROJECT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AHEAD = Instant.parse("2026-11-20T00:00:00Z");

    @Test
    void shouldStartEveryGitLabMonitorFromItsProjectWatermarkWhenTheChangelogRunsAgain() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE identity_provider (id bigint PRIMARY KEY, type varchar(10), server_url varchar(512));
                    CREATE TABLE connection (workspace_id bigint, kind varchar(48), config jsonb);
                    CREATE TABLE repository (
                        id bigint PRIMARY KEY, native_id bigint, name_with_owner varchar(255), provider_id bigint,
                        last_sync_at timestamptz);
                    CREATE TABLE repository_to_monitor (
                        id bigint PRIMARY KEY, workspace_id bigint, native_id bigint, name_with_owner varchar(255),
                        issues_synced_at timestamptz, pull_requests_synced_at timestamptz);
                    INSERT INTO identity_provider VALUES
                        (1, 'GITLAB', 'https://gitlab.example.test'), (2, 'GITHUB', 'https://github.com');
                    INSERT INTO connection VALUES
                        (10, 'GITLAB', '{"serverUrl": "https://gitlab.example.test"}'),
                        (20, 'GITHUB', '{"serverUrl": "https://github.com"}');
                    """);
            repository(connection, 100, 11, "group/synced", 1, PROJECT);
            repository(connection, 200, 22, "group/never-synced", 1, null);
            repository(connection, 300, 33, "owner/github", 2, PROJECT);
            monitor(connection, 1000, 10, 11, "group/synced");
            monitor(connection, 2000, 10, 22, "group/never-synced");
            monitor(connection, 3000, 20, 33, "owner/github");
            connection.commit();

            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);
                // Liquibase commits a change set before its history row, so a stopped run applies it again.
                statement.execute("DELETE FROM databasechangelog");
                connection.commit();
                liquibase.update(contexts, labels);

                assertThat(watermarks(connection, 1000)).containsExactly(PROJECT, PROJECT);
                assertThat(watermarks(connection, 2000)).containsExactly(null, null);
                assertThat(watermarks(connection, 3000)).containsExactly(AHEAD, AHEAD);

                assertThatThrownBy(() -> liquibase.rollback(1, contexts, labels))
                        .hasStackTraceContaining("Restore the database backup taken before this upgrade");
            }
        }
    }

    private static void repository(
            Connection connection, long id, long nativeId, String name, long provider, @Nullable Instant lastSyncAt)
            throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO repository VALUES (?, ?, ?, ?, ?)")) {
            insert.setLong(1, id);
            insert.setLong(2, nativeId);
            insert.setString(3, name);
            insert.setLong(4, provider);
            insert.setTimestamp(5, lastSyncAt == null ? null : Timestamp.from(lastSyncAt));
            insert.executeUpdate();
        }
    }

    /** A monitor whose kind watermarks are ahead of its project's, as an earlier capped pass left them. */
    private static void monitor(Connection connection, long id, long workspaceId, long nativeId, String name)
            throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO repository_to_monitor VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, id);
            insert.setLong(2, workspaceId);
            insert.setLong(3, nativeId);
            insert.setString(4, name);
            insert.setTimestamp(5, Timestamp.from(AHEAD));
            insert.setTimestamp(6, Timestamp.from(AHEAD));
            insert.executeUpdate();
        }
    }

    private static @Nullable Instant[] watermarks(Connection connection, long monitorId) throws SQLException {
        try (var select = connection.prepareStatement(
                "SELECT issues_synced_at, pull_requests_synced_at FROM repository_to_monitor WHERE id = ?")) {
            select.setLong(1, monitorId);
            try (var rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Timestamp issues = rows.getTimestamp(1);
                Timestamp mergeRequests = rows.getTimestamp(2);
                return new @Nullable Instant[] {
                    issues == null ? null : issues.toInstant(), mergeRequests == null ? null : mergeRequests.toInstant()
                };
            }
        }
    }
}
