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
 * The proof cutover lets every monitored repository wait for a new proof, also one that the earlier cutover made
 * visible to the whole workspace: that class came from the shared mirror row, which another workspace's read may have
 * written. The database accepts only a class, proof state and reason that belong together.
 */
@Tag("database")
class ResourceReadersProofMigrationTest {

    private static final String CHANGELOG = "-feat-resource-readers.xml";

    @Test
    void shouldLetEveryRepositoryWaitForANewProofAndRollBackClosed() throws Exception {
        String name = "resource_readers_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979401, 'readers-hub', 'Readers hub', 'readers-hub', 'ORG', 'ACTIVE'),
                       (979402, 'readers-lab', 'Readers lab', 'readers-lab', 'ORG', 'ACTIVE');
                INSERT INTO identity_provider(id, type, server_url)
                VALUES (979401, 'GITHUB', 'https://readers-hub.example.test'),
                       (979402, 'GITLAB', 'https://readers-lab.example.test');
                INSERT INTO connection(id, workspace_id, kind, instance_key, state, config)
                VALUES (979401, 979401, 'GITHUB', '979401', 'ACTIVE',
                        '{"type":"GITHUB_APP","installationId":979401,"enabledStreams":[],"serverUrl":"https://readers-hub.example.test"}'),
                       (979402, 979402, 'GITLAB', '979402', 'ACTIVE',
                        '{"type":"GITLAB","serverUrl":"https://readers-lab.example.test","gitlabGroupId":979402}');
                INSERT INTO repository(id, native_id, provider_id, name, name_with_owner, is_archived, is_disabled,
                                       is_private, visibility)
                VALUES (979411, 979411, 979401, 'open', 'hub/open', false, false, false, 'PUBLIC'),
                       (979412, 979412, 979401, 'gone', 'hub/gone', false, false, false, 'PUBLIC'),
                       (979413, 979413, 979401, 'closed', 'hub/closed', false, false, true, 'PRIVATE'),
                       (979414, 979414, 979402, 'project', 'lab/project', false, false, false, 'PUBLIC');
                INSERT INTO repository_to_monitor(id, workspace_id, generated_paths, name_with_owner, native_id,
                                                  repository_visibility_confirmed_at, unavailable_since,
                                                  visibility_class)
                VALUES (979421, 979401, '[]', 'hub/open', 979411, '2026-11-24T06:00:00Z'::timestamptz, NULL, 'WORKSPACE'),
                       (979422, 979401, '[]', 'hub/gone', 979412, now(), now(), 'WORKSPACE'),
                       (979423, 979401, '[]', 'hub/closed', 979413, now(), NULL, 'WORKSPACE'),
                       (979424, 979402, '[]', 'lab/project', 979414, now(), NULL, 'WORKSPACE');
                UPDATE repository_to_monitor SET repository_visibility_confirmed_at = now() - interval '1 hour'
                WHERE id = 979421;
                """);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT name_with_owner || ' ' || visibility_class || ' ' || proof_state || ' ' || proof_reason
                                   || ' ' || CASE WHEN readers_synced_at IS NULL THEN 'unproven'
                                                  WHEN readers_synced_at = repository_visibility_confirmed_at
                                                  THEN 'proven-at-confirmation' ELSE 'other' END
                                   || ' ' || proof_generation
                            FROM repository_to_monitor WHERE id BETWEEN 979421 AND 979424 ORDER BY id
                            """))
                    .containsExactly(
                            "hub/open UNPROVEN PENDING AWAITING_READERS unproven 0",
                            "hub/gone UNPROVEN PENDING AWAITING_READERS unproven 0",
                            "hub/closed UNPROVEN PENDING AWAITING_READERS unproven 0",
                            "lab/project UNPROVEN PENDING AWAITING_READERS unproven 0");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            owner, "UPDATE repository_to_monitor SET visibility_class = 'WORKSPACE' WHERE id = 979423"))
                    .as("only a public confirmation makes a repository visible to the whole workspace")
                    .hasStackTraceContaining("ck_repository_to_monitor_readers_proof");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            owner,
                            "UPDATE repository_to_monitor SET proof_state = 'PROVEN', proof_reason = 'RATE_LIMITED'"
                                    + " WHERE id = 979423"))
                    .as("a proof state that its reason does not imply is refused")
                    .hasStackTraceContaining("ck_repository_to_monitor_readers_proof");

            assertThat(PracticeMigrationFixture.column(
                            owner,
                            "SELECT count(*)::text FROM pg_proc WHERE proname = 'narrow_monitors_of_repository'"))
                    .containsExactly("1");

            // A sync proved a repository public between the upgrade and the rollback.
            PracticeMigrationFixture.execute(owner, """
                    UPDATE repository_to_monitor SET visibility_class = 'WORKSPACE', proof_state = 'PROVEN',
                        proof_reason = 'PUBLIC_ON_PROVIDER', readers_synced_at = now(), proof_generation = 1
                    WHERE id = 979421
                    """);

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT count(*)::text FROM information_schema.columns WHERE table_schema = 'public'
                            AND column_name IN ('proof_state', 'proof_reason', 'proof_generation')
                            """)).containsExactly("0");
            assertThat(PracticeMigrationFixture.column(
                            owner,
                            "SELECT count(*)::text FROM pg_proc WHERE proname = 'narrow_monitors_of_repository'"))
                    .containsExactly("0");
            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT name_with_owner || ' ' || visibility_class || ' '
                                   || coalesce(readers_synced_at::text, 'unproven')
                            FROM repository_to_monitor WHERE id BETWEEN 979421 AND 979424 ORDER BY id
                            """))
                    .as("the rollback fails closed: every repository waits for a new proof")
                    .containsExactly(
                            "hub/open UNPROVEN unproven",
                            "hub/gone UNPROVEN unproven",
                            "hub/closed UNPROVEN unproven",
                            "lab/project UNPROVEN unproven");
        }
    }
}
