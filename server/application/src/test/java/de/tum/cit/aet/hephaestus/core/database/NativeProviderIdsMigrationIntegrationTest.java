package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class NativeProviderIdsMigrationIntegrationTest {
    private static final String CHANGELOG = "db/changelog/next/20261106111924-fix-require-native-provider-ids.xml";

    @Test
    void shouldBackfillBothProvidersAndStopsWithoutChangingCheckpointsOrReceipts() throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO repository_to_monitor VALUES
                    (11,1,'org/repo',NULL,17,'["dist/**"]'), (12,2,'group/repo',NULL,29,'[]');
                INSERT INTO repository VALUES (1,1,101,'org/repo'), (2,2,202,'group/repo'),
                    (3,3,999,'group/repo');
                INSERT INTO organization VALUES (1,2,42,'group');
                INSERT INTO item VALUES(1,'scm.repository',NULL,1),(2,'scm.repository',NULL,2);
                UPDATE connection SET config = config || '{"projectExclusions":{"nativeIds":[303],
                    "unresolvedPaths":["GROUP/REPO"],
                    "stopReceipts":{"303":"00000000-0000-4000-8000-000000000001"}}}' WHERE id=2;
                """);

        migrate(database);

        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT id || ':' || native_id || ':' || checkpoint || ':' || generated_paths FROM repository_to_monitor ORDER BY id"))
                .containsExactly("11:101:17:[\"dist/**\"]", "12:202:29:[]");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT config->>'gitlabGroupId' FROM connection WHERE id=2"))
                .containsExactly("42");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT value FROM connection, jsonb_array_elements_text(config#>'{projectExclusions,nativeIds}') WHERE id=2 ORDER BY value"))
                .containsExactly("202", "303");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT (config#>>'{projectExclusions,stopReceipts,303}') || ':' || jsonb_exists(config->'projectExclusions', 'unresolvedPaths') FROM connection WHERE id=2"))
                .containsExactly("00000000-0000-4000-8000-000000000001:false");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "INSERT INTO repository_to_monitor VALUES(13,1,'org/missing',NULL,0,'[]')"))
                .hasMessageContaining("native_id");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "UPDATE connection SET config=config-'gitlabGroupId' WHERE id=2"))
                .hasMessageContaining("ck_connection_gitlab_native_ids");
        assertThatThrownBy(
                        () -> PracticeMigrationFixture.execute(
                                database,
                                "UPDATE connection SET config=jsonb_set(config,'{projectExclusions,unresolvedPaths}','[\"group/new\"]') WHERE id=2"))
                .hasMessageContaining("ck_connection_gitlab_native_ids");
        migrate(database);
    }

    @Test
    void shouldKeepEveryRowAndRefuseBeforeWritingWhenOnlyAnotherOriginKnowsTheMonitor() throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO repository_to_monitor VALUES(11,1,'org/known',NULL,17,'[]'),
                    (12,2,'group/missing',NULL,29,'["keep/**"]');
                INSERT INTO repository VALUES(1,1,101,'org/known'),(2,3,202,'group/missing');
                INSERT INTO organization VALUES(1,2,42,'group');
                INSERT INTO item VALUES(1,'scm.repository',NULL,1),(2,'scm.repository',NULL,2);
                """);

        assertRefused(database);

        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT id || ':' || COALESCE(native_id::text,'NULL') || ':' || checkpoint FROM repository_to_monitor ORDER BY id"))
                .containsExactly("11:NULL:17", "12:NULL:29");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT COALESCE(config->>'gitlabGroupId','NULL') FROM connection WHERE id=2"))
                .containsExactly("NULL");
    }

    @Test
    void shouldKeepAnUnresolvedStopAndRefuseEvenWhenItsGroupAndMonitorCanBeResolved() throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO repository_to_monitor VALUES(11,2,'group/known',NULL,17,'[]');
                INSERT INTO repository VALUES(1,2,101,'group/known');
                INSERT INTO organization VALUES(1,2,42,'group');
                INSERT INTO item VALUES(2,'scm.repository',NULL,1);
                UPDATE connection SET config=config || '{"projectExclusions":{"nativeIds":[],
                    "unresolvedPaths":["group/gone"],"stopReceipts":{}}}' WHERE id=2;
                """);

        assertRefused(database);

        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT config#>>'{projectExclusions,unresolvedPaths,0}' FROM connection WHERE id=2"))
                .containsExactly("group/gone");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT COALESCE(native_id::text,'NULL') FROM repository_to_monitor WHERE id=11"))
                .containsExactly("NULL");
    }

    @Test
    void shouldRefuseDuplicateIdentitiesAndAnUnknownGroupWithoutLosingSelections() throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO repository_to_monitor VALUES(11,1,'org/repo',101,17,'[]'),
                    (12,1,'ORG/REPO',NULL,29,'["keep/**"]');
                INSERT INTO repository VALUES(1,1,101,'org/repo');
                INSERT INTO item VALUES(1,'scm.repository',NULL,1);
                """);
        assertRefused(database);
        assertThat(PracticeMigrationFixture.column(database, "SELECT id::text FROM repository_to_monitor ORDER BY id"))
                .containsExactly("11", "12");
        PracticeMigrationFixture.execute(database, "INSERT INTO organization VALUES(1,2,42,'group')");
        assertRefused(database);
    }

    @Test
    void shouldRefuseAnIdentityKnownOnlyToAnotherWorkspaceOnTheSameOrigin() throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO repository_to_monitor VALUES(11,1,'org/repo',NULL,17,'[]');
                INSERT INTO repository VALUES(1,1,101,'org/repo');
                INSERT INTO item VALUES(2,'scm.repository',NULL,1);
                INSERT INTO organization VALUES(1,2,42,'group');
                """);

        assertThatThrownBy(() -> migrate(database))
                .hasMessageContaining("Native provider IDs could not be resolved without data loss")
                .hasMessageContaining("\"row_id\": 11")
                .hasMessageContaining("org/repo")
                .hasMessageContaining("MIGRATION.md#-stop-every-role-before-requiring-native-provider-ids");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT COALESCE(native_id::text,'NULL') FROM repository_to_monitor WHERE id=11"))
                .containsExactly("NULL");
    }

    @Test
    void shouldNameAnUninstalledConnectionsUnresolvedStopWithoutChangingIt() throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                UPDATE connection SET state='UNINSTALLED', config=config ||
                    '{"gitlabGroupId":42,"projectExclusions":{"nativeIds":[],"unresolvedPaths":["group/deleted"]}}'
                    WHERE id=2;
                """);

        assertThatThrownBy(() -> migrate(database))
                .hasMessageContaining("\"kind\": \"stop\"")
                .hasMessageContaining("\"row_id\": 2")
                .hasMessageContaining("group/deleted");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT config#>>'{projectExclusions,unresolvedPaths,0}' FROM connection WHERE id=2"))
                .containsExactly("group/deleted");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"42\"", "0", "-1", "9223372036854775808"})
    void shouldNameAnInvalidGroupIdAndRefuseBeforeBackfillingAnyMonitor(String invalidId) throws Exception {
        var database = beforeCutover();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO repository_to_monitor VALUES(11,1,'org/repo',NULL,17,'[]');
                INSERT INTO repository VALUES(1,1,101,'org/repo');
                INSERT INTO item VALUES(1,'scm.repository',NULL,1);
                """);
        PracticeMigrationFixture.execute(
                database,
                "UPDATE connection SET config=jsonb_set(config,'{gitlabGroupId}','%s'::jsonb) WHERE id=2"
                        .formatted(invalidId));

        assertThatThrownBy(() -> migrate(database))
                .hasMessageContaining("\"kind\": \"group\"")
                .hasMessageContaining("\"row_id\": 2");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT COALESCE(native_id::text,'NULL') FROM repository_to_monitor WHERE id=11"))
                .containsExactly("NULL");
    }

    private static void assertRefused(TestDatabase database) {
        assertThatThrownBy(() -> migrate(database))
                .hasMessageContaining("Native provider IDs could not be resolved without data loss");
    }

    private static TestDatabase beforeCutover() {
        var database = PostgreSQLTestContainer.createDatabase(
                "native_ids_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.execute(database, """
                CREATE TABLE workspace(id bigint PRIMARY KEY, account_login text NOT NULL, organization_id bigint);
                CREATE TABLE identity_provider(id bigint PRIMARY KEY, type text NOT NULL, server_url text NOT NULL);
                CREATE TABLE repository(id bigint PRIMARY KEY, provider_id bigint NOT NULL, native_id bigint NOT NULL, name_with_owner text NOT NULL);
                CREATE TABLE organization(id bigint PRIMARY KEY, provider_id bigint NOT NULL, native_id bigint NOT NULL, login text NOT NULL);
                CREATE TABLE connection(id bigint PRIMARY KEY, workspace_id bigint NOT NULL, kind text NOT NULL, state text NOT NULL, config jsonb NOT NULL);
                CREATE TABLE item(workspace_id bigint NOT NULL, kind text NOT NULL, parent_id bigint, source_id bigint NOT NULL);
                CREATE TABLE repository_to_monitor(id bigint PRIMARY KEY, workspace_id bigint NOT NULL, name_with_owner text NOT NULL,
                    native_id bigint, checkpoint int NOT NULL, generated_paths jsonb NOT NULL, UNIQUE(workspace_id,native_id));
                INSERT INTO workspace VALUES(1,'org',NULL),(2,'group',1);
                INSERT INTO identity_provider VALUES(1,'GITHUB','https://github.com'),(2,'GITLAB','https://gitlab.example.test'),
                    (3,'GITLAB','https://foreign.example.test');
                INSERT INTO connection VALUES(1,1,'GITHUB','ACTIVE','{"type":"GITHUB_PAT","serverUrl":"https://github.com"}'),
                    (2,2,'GITLAB','SUSPENDED','{"type":"GITLAB","serverUrl":"https://gitlab.example.test","gitlabGroupId":null}');
                """);
        return database;
    }

    private static void migrate(TestDatabase database) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }
}
