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
 * The readers migration adds the reader table, the reason for a repository whose readers no listing names, and the
 * triggers that remove a person whom GitHub removed. Its rollback fails closed: a repository that recorded the new
 * reason waits for its readers again, and the narrowing on a deleted repository is the earlier one.
 */
@Tag("database")
class GitHubRepositoryReadersMigrationTest {

    private static final String CHANGELOG = "-feat-github-repository-readers.xml";

    @Test
    void shouldStoreListedReadersRefuseAMismatchedProofAndRollBackClosed() throws Exception {
        String name = "github_readers_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979501, 'readers-course', 'Readers course', 'readers-course', 'ORG', 'ACTIVE');
                INSERT INTO identity_provider(id, type, server_url)
                VALUES (979501, 'GITHUB', 'https://readers-course.example.test');
                INSERT INTO repository(id, native_id, provider_id, name, name_with_owner, is_archived, is_disabled,
                                       is_private, visibility)
                VALUES (979511, 979511, 979501, 'closed', 'course/closed', false, false, true, 'PRIVATE'),
                       (979512, 979512, 979501, 'internal', 'course/internal', false, false, true, 'INTERNAL');
                INSERT INTO repository_to_monitor(id, workspace_id, generated_paths, name_with_owner, native_id,
                                                  visibility_class, proof_state, proof_reason, readers_synced_at)
                VALUES (979521, 979501, '[]', 'course/closed', 979511, 'RESTRICTED', 'PROVEN', 'READERS_LISTED',
                        now()),
                       (979522, 979501, '[]', 'course/internal', 979512, 'UNPROVEN', 'PENDING', 'AWAITING_READERS',
                        NULL);
                """);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT name_with_owner || ' ' || (proof_changed_at IS NOT NULL)::text
                            FROM repository_to_monitor WHERE id IN (979521, 979522) ORDER BY id
                            """))
                    .as("every existing proof gets the time it began to wait or hold")
                    .containsExactly("course/closed true", "course/internal true");
            PracticeMigrationFixture.execute(owner, """
                    INSERT INTO resource_reader(workspace_id, repository_to_monitor_id, reader_native_id)
                    VALUES (979501, 979521, 1001), (979501, 979521, 1002);
                    UPDATE repository_to_monitor
                    SET visibility_class = 'UNPROVEN', proof_state = 'FAILED', proof_reason = 'READERS_NOT_LISTABLE'
                    WHERE id = 979522;
                    """);
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                            UPDATE repository_to_monitor
                            SET visibility_class = 'RESTRICTED', proof_state = 'FAILED'
                            WHERE id = 979522
                            """))
                    .as("a repository whose readers no listing names is never restricted to a reader set")
                    .hasStackTraceContaining("ck_repository_to_monitor_readers_proof");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                            INSERT INTO resource_reader(workspace_id, repository_to_monitor_id, reader_native_id)
                            VALUES (979599, 979521, 1003)
                            """))
                    .as("a reader belongs to the workspace of its monitor")
                    .hasStackTraceContaining("fk_resource_reader_repository_to_monitor");

            PracticeMigrationFixture.execute(owner, "DELETE FROM repository WHERE id = 979511");
            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT visibility_class || ' ' || proof_state || ' ' || proof_reason || ' '
                                   || (SELECT count(*) FROM resource_reader WHERE repository_to_monitor_id = 979521)
                            FROM repository_to_monitor WHERE id = 979521
                            """))
                    .as("a deleted repository drops its monitors' readers and proof")
                    .containsExactly("UNPROVEN PENDING AWAITING_READERS 0");

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT visibility_class || ' ' || proof_state || ' ' || proof_reason
                            FROM repository_to_monitor WHERE id = 979522
                            """))
                    .as("the rollback fails closed: the repository waits for its readers again")
                    .containsExactly("UNPROVEN PENDING AWAITING_READERS");
            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT (to_regclass('public.resource_reader') IS NULL)::text || ' '
                                   || (SELECT count(*) FROM information_schema.columns
                                       WHERE table_schema = 'public' AND column_name = 'proof_changed_at')
                                   || ' ' || (SELECT count(*) FROM pg_proc
                                              WHERE proname IN ('forget_readers_of_repositories',
                                                                'forget_repository_readers'))
                            """)).containsExactly("true 0 0");
            assertThat(PracticeMigrationFixture.column(owner, """
                            SELECT p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                            WHERE t.tgname = 'repository_gone'
                            """))
                    .as("a deleted repository narrows its monitors as before")
                    .containsExactly("narrow_monitors_of_repository");
        }
    }
}
