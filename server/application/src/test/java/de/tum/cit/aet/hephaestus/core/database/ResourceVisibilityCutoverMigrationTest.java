package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The visibility cutover: a monitored repository that its provider confirmed public in the last 48 hours is visible to
 * the whole workspace, and every other resource starts unproven, so mentoring waits for its readers.
 */
@Tag("database")
class ResourceVisibilityCutoverMigrationTest {

    private static final String CHANGELOG = "-feat-resource-visibility-class.xml";

    @Test
    void shouldStartOnlyARecentlyConfirmedPublicRepositoryVisibleToTheWorkspaceAndRollBack() throws Exception {
        String name = "resource_visibility_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979301, 'visibility-cutover', 'Visibility cutover', 'visibility-cutover', 'ORG', 'ACTIVE');
                INSERT INTO identity_provider(id, type, server_url)
                VALUES (979301, 'GITHUB', 'https://visibility.example.test'),
                       (979302, 'GITHUB', 'https://elsewhere.example.test');
                INSERT INTO connection(id, workspace_id, kind, instance_key, state, config)
                VALUES (979301, 979301, 'GITHUB', '979301', 'ACTIVE',
                        '{"type":"GITHUB_APP","installationId":979301,"enabledStreams":[],"serverUrl":"https://visibility.example.test"}');
                INSERT INTO repository(id, native_id, provider_id, name, name_with_owner, is_archived, is_disabled,
                                       is_private, visibility)
                VALUES (979311, 979311, 979301, 'open', 'acme/open', false, false, false, 'PUBLIC'),
                       (979312, 979312, 979301, 'closed', 'acme/closed', false, false, true, 'PRIVATE'),
                       (979313, 979313, 979301, 'unconfirmed', 'acme/unconfirmed', false, false, false, 'PUBLIC'),
                       (979314, 979314, 979301, 'stale', 'acme/stale', false, false, false, 'PUBLIC'),
                       (979315, 979315, 979301, 'gone', 'acme/gone', false, false, false, 'PUBLIC'),
                       (979316, 979316, 979301, 'twin', 'acme/twin', false, false, true, 'PRIVATE'),
                       (979317, 979316, 979302, 'twin', 'acme/twin', false, false, false, 'PUBLIC');
                INSERT INTO repository_to_monitor(id, workspace_id, generated_paths, name_with_owner, native_id,
                                                  repository_visibility_confirmed_at, unavailable_since)
                VALUES (979321, 979301, '[]', 'acme/open', 979311, now(), NULL),
                       (979322, 979301, '[]', 'acme/closed', 979312, now(), NULL),
                       (979323, 979301, '[]', 'acme/unconfirmed', 979313, NULL, NULL),
                       (979324, 979301, '[]', 'acme/stale', 979314, now() - interval '49 hours', NULL),
                       (979325, 979301, '[]', 'acme/gone', 979315, now(), now()),
                       (979326, 979301, '[]', 'acme/twin', 979316, now(), NULL);
                """);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT name_with_owner || ' ' || visibility_class FROM repository_to_monitor
                            WHERE workspace_id = 979301 ORDER BY id
                            """))
                    .as("only the available repository confirmed public in the last 48 hours on its own host")
                    .containsExactly(
                            "acme/open WORKSPACE",
                            "acme/closed UNPROVEN",
                            "acme/unconfirmed UNPROVEN",
                            "acme/stale UNPROVEN",
                            "acme/gone UNPROVEN",
                            "acme/twin UNPROVEN");
            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT table_name || ' ' || column_default FROM information_schema.columns
                            WHERE column_name = 'visibility_class' AND table_schema = 'public' ORDER BY table_name
                            """))
                    .containsExactly(
                            "outline_collection 'UNPROVEN'::character varying",
                            "repository_to_monitor 'UNPROVEN'::character varying",
                            "slack_monitored_channel 'UNPROVEN'::character varying");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            owner, "UPDATE repository_to_monitor SET visibility_class = 'EVERYONE' WHERE id = 979321"))
                    .hasStackTraceContaining("ck_repository_to_monitor_visibility_class");

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT count(*)::text FROM information_schema.columns WHERE table_schema = 'public'
                            AND column_name IN ('visibility_class', 'readers_synced_at')
                            """)).containsExactly("0");
            assertThat(PracticeMigrationFixture.column(
                            owner, "SELECT count(*)::text FROM repository_to_monitor WHERE workspace_id = 979301"))
                    .containsExactly("6");
        }
    }
}
