package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestDatabaseCleanup;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

@Tag("database")
class PrivacyJournalDatabaseTest {

    @Test
    void theBackupReporterCanWriteOnlyAnObservationAndTheApplicationCanReadIt() throws Exception {
        var databaseName = "privacy_journal_reporter";
        var database = TestDatabaseCleanup.createMigratedDatabase(databaseName);
        PostgreSQLTestContainer.applyDatabaseRoles(databaseName);

        PracticeMigrationFixture.execute(database, """
            BEGIN;
            SET LOCAL ROLE hephaestus_backup_reporter;
            INSERT INTO privacy_journal_backup_horizon (id, observed_at, oldest_backup_at)
            VALUES (1, clock_timestamp(), clock_timestamp() - interval '8 days');
            COMMIT;
            """);

        assertThat(PracticeMigrationFixture.column(database, """
            SELECT (
                has_table_privilege('hephaestus_app', 'privacy_journal_backup_horizon', 'SELECT')
                AND NOT has_table_privilege('hephaestus_app', 'privacy_journal_backup_horizon', 'INSERT,UPDATE,DELETE')
                AND NOT has_table_privilege('hephaestus_backup_reporter', 'privacy_journal_backup_horizon', 'DELETE')
                AND NOT has_table_privilege('hephaestus_backup_reporter', 'privacy_journal', 'SELECT,INSERT,UPDATE,DELETE')
                AND NOT has_table_privilege('hephaestus_backup_reporter', 'account', 'SELECT,INSERT,UPDATE,DELETE')
            )::text
            """)).containsExactly("true");
    }

    @Test
    void shouldReportAnOlderBackupAfterFailureAndKeepPruningPausedForAnInvalidReport() throws Exception {
        var name = "privacy_journal_backup_script";
        var database = TestDatabaseCleanup.createMigratedDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var postgres = PostgreSQLTestContainer.getInstance();
        String directory = "/tmp/privacy-journal-" + UUID.randomUUID();
        assertThat(PostgreSQLTestContainer.execShell("mkdir -p " + directory, Map.of())
                        .getExitCode())
                .isZero();
        var files = Path.of(System.getProperty("basedir", "."), "../../deploy/chart/hephaestus/files");
        for (String file : new String[] {"backup-publish-horizon.sh", "backup-oldest-point.pl"}) {
            postgres.copyFileToContainer(MountableFile.forHostPath(files.resolve(file)), directory + "/" + file);
        }
        postgres.copyFileToContainer(Transferable.of("""
                    #!/bin/sh
                    for arg do command=$arg; done
                    case "$command" in
                        backup) printf '%s\n' 'Backup ran'; exit "$BACKUP_STATUS";;
                        info) printf '%s\n' "$BACKUP_INVENTORY";;
                        *) exit 2;;
                    esac
                    """, 0755), directory + "/pgbackrest");
        String command = "PATH=" + directory + ":$PATH sh " + directory + "/backup-publish-horizon.sh diff";
        var backupFailed = PostgreSQLTestContainer.execShell(
                command,
                Map.of(
                        "PGUSER",
                        postgres.getUsername(),
                        "PGDATABASE",
                        name,
                        "PGOPTIONS",
                        "--role=hephaestus_backup_reporter",
                        "BACKUP_STATUS",
                        "23",
                        "BACKUP_INVENTORY",
                        backupInventory(1_795_020_000L)));
        assertThat(backupFailed.getExitCode()).as(backupFailed.getStderr()).isEqualTo(23);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT extract(epoch FROM oldest_backup_at)::bigint::text FROM privacy_journal_backup_horizon
            """)).containsExactly("1795020000");

        var future = Map.of(
                "PGUSER",
                postgres.getUsername(),
                "PGDATABASE",
                name,
                "PGOPTIONS",
                "--role=hephaestus_backup_reporter",
                "BACKUP_STATUS",
                "0",
                "BACKUP_INVENTORY",
                backupInventory(4_102_444_800L));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var lock = connection.createStatement()) {
            lock.execute("""
                SELECT pg_advisory_lock(hashtext('hephaestus'), hashtext('privacy-journal-backup-horizon'))
                """);
            var overlappingRun = PostgreSQLTestContainer.execShell(command, future);
            assertThat(overlappingRun.getExitCode()).isZero();
            assertThat(overlappingRun.getStderr()).contains("Cannot publish the PostgreSQL recovery point");
            assertThat(PracticeMigrationFixture.column(database, """
                SELECT extract(epoch FROM oldest_backup_at)::bigint::text FROM privacy_journal_backup_horizon
                """)).containsExactly("1795020000");
        }
        var loginFailed = new HashMap<>(future);
        loginFailed.put("PGUSER", "missing_backup_reporter");
        var withoutLogin = PostgreSQLTestContainer.execShell(command, loginFailed);
        assertThat(withoutLogin.getExitCode()).isZero();
        assertThat(withoutLogin.getStderr()).contains("Cannot publish the PostgreSQL recovery point");

        var badReport = PostgreSQLTestContainer.execShell(command, future);
        assertThat(badReport.getExitCode()).as(badReport.getStderr()).isZero();
        assertThat(badReport.getStderr()).contains("Journal pruning will wait");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT (oldest_backup_at IS NULL)::text FROM privacy_journal_backup_horizon
            """)).containsExactly("true");
    }

    private static String backupInventory(long oldest) {
        return """
            [{"name":"hephaestus","status":{"code":0},"backup":[
                {"timestamp":{"start":%d}},{"timestamp":{"start":%d}}
            ]}]
            """.formatted(oldest + 100, oldest);
    }

    @Test
    void shouldPreserveTheJournalDuringFullAndStagedRollbacks() throws Exception {
        var database = TestDatabaseCleanup.createDatabase("privacy_journal_rollback");
        var changelog = "feat-kr-12-journal-migration.xml";
        PracticeMigrationFixture.upgradeBefore(database, "prod", changelog);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", changelog);
            var fact = """
                INSERT INTO privacy_journal(entry_id, kind, refs)
                VALUES ('91b965c9-791c-438d-8446-cc33c0309afd', 'ACCOUNT_ERASED', '{"accountId":37}')
                """;
            PracticeMigrationFixture.execute(database, fact);
            assertRollbackKeepsJournal(liquibase, changelog);
            liquibase.getDatabase().rollback();

            PracticeMigrationFixture.execute(database, """
                DELETE FROM privacy_journal WHERE entry_id = '91b965c9-791c-438d-8446-cc33c0309afd'
                """);
            liquibase.rollback(1, new Contexts("prod"), new LabelExpression());

            // A staged rollback reaches the table's own protection with an empty journal.
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", changelog))
                    .isInstanceOf(LiquibaseException.class)
                    .hasMessageContaining("Keep the privacy journal. Follow the erasure recovery guide.");
            liquibase.getDatabase().rollback();

            // The protection also holds if a fact arrives after the earlier rollback.
            PracticeMigrationFixture.execute(database, fact);
            assertRollbackKeepsJournal(liquibase, changelog);
        }
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT count(*)::text FROM privacy_journal WHERE entry_id='91b965c9-791c-438d-8446-cc33c0309afd'
            """)).containsExactly("1");
    }

    private static void assertRollbackKeepsJournal(Liquibase liquibase, String changelog) {
        assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", changelog))
                .isInstanceOf(LiquibaseException.class)
                .hasMessageContaining("privacy journal. Follow the erasure recovery guide.");
    }

    @Test
    void shouldRefuseARepeatedEntryId() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");
        var first = """
                INSERT INTO privacy_journal(entry_id, kind, refs)
                VALUES ('d4b196c9-791c-438d-8446-cc33c0309afd', 'ACCOUNT_ERASED', '{"accountId":37}')
                """;
        PracticeMigrationFixture.execute(database, first);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, first))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("duplicate key");
    }
}
