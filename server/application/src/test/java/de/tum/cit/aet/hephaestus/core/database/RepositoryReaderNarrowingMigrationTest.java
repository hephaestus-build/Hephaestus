package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class RepositoryReaderNarrowingMigrationTest {

    private static final String CHANGELOG = "-scope-repository-reader-narrowing.xml";
    private static final String LEGACY_CALL = "SELECT public.narrow_repository_monitors(979511::bigint)::text";
    private static final String GENERATIONS = """
            SELECT proof_generation::text FROM repository_to_monitor WHERE id IN (979521, 979522) ORDER BY id
            """;

    @Test
    void shouldKeepThePreviousServersGitHubNarrowingCallDuringUpgradeAndRollback() throws Exception {
        String name = "reader_narrowing_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        var app = PostgreSQLTestContainer.asApplication(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979501, 'narrowing-hub', 'Narrowing hub', 'narrowing-hub', 'ORG', 'ACTIVE'),
                       (979502, 'narrowing-lab', 'Narrowing lab', 'narrowing-lab', 'ORG', 'ACTIVE');
                INSERT INTO connection(id, workspace_id, kind, instance_key, state, config)
                VALUES (979501, 979501, 'GITHUB', '979501', 'ACTIVE',
                        '{"type":"GITHUB_APP","installationId":979501,"enabledStreams":[],"serverUrl":"https://github.com"}'),
                       (979502, 979502, 'GITLAB', '979502', 'ACTIVE',
                        '{"type":"GITLAB","serverUrl":"https://gitlab.com","gitlabGroupId":979502}');
                INSERT INTO repository_to_monitor(id, workspace_id, generated_paths, name_with_owner, native_id)
                VALUES (979521, 979501, '[]', 'hub/project', 979511),
                       (979522, 979502, '[]', 'lab/project', 979511);
                """);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(app, LEGACY_CALL)).containsExactly("1");
            assertThat(PracticeMigrationFixture.column(owner, GENERATIONS)).containsExactly("1", "0");

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(app, LEGACY_CALL)).containsExactly("1");
            assertThat(PracticeMigrationFixture.column(owner, GENERATIONS)).containsExactly("2", "0");
        }
    }
}
