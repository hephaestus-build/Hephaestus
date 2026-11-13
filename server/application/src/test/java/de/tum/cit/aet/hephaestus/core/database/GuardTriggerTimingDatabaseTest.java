package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A guard that only validates an UPDATE runs AFTER the row write. With any BEFORE UPDATE row trigger on a table, even
 * one limited to other columns, PostgreSQL locks the row FOR UPDATE whenever an UPDATE names a key column, changed or
 * not, and that lock blocks every foreign-key write that references the row until commit. A BEFORE UPDATE trigger stays
 * only where it changes the row or orders locks, or where the stronger lock blocks no write; each one names why.
 */
@Tag("database")
class GuardTriggerTimingDatabaseTest {
    private static final String CHANGELOG = "-rootfix-after-guard-triggers.xml";

    private static final Map<String, String> BEFORE_UPDATE_TRIGGERS = Map.of(
            "workspace.trg_reserve_workspace_slug",
                    "fires for a changed slug only, and a changed key column takes the stronger lock anyway",
            "problem.problem_parent_touch", "locks the parent result before the problem row, parent first",
            "llm_model.llm_model_own_call_pool", "gives the model its own call pool by setting NEW.call_pool_id",
            "workspace_llm_model.workspace_llm_model_own_call_pool",
                    "gives the model its own call pool by setting NEW.call_pool_id",
            "auth_event.trg_auth_event_block_mutation", "no foreign key references the table",
            "config_audit_event.trg_config_audit_event_block_mutation", "no foreign key references the table");

    /** BEFORE (2) ROW (1) triggers that fire on UPDATE (16), on parent tables; a partition repeats its parent's. */
    private static final String BEFORE_UPDATE_ROW_TRIGGERS = """
            SELECT c.relname || '.' || t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
            WHERE NOT t.tgisinternal AND t.tgparentid = 0 AND c.relnamespace = 'public'::regnamespace
              AND (t.tgtype & 1) = 1 AND (t.tgtype & 2) = 2 AND (t.tgtype & 16) = 16
            ORDER BY 1
            """;

    @Test
    void shouldRunEveryGuardThatOnlyValidatesAnUpdateAfterTheRowWrite() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");

        assertThat(PracticeMigrationFixture.column(database, BEFORE_UPDATE_ROW_TRIGGERS))
                .as("BEFORE UPDATE row triggers; a guard that only validates belongs AFTER the write")
                .containsExactlyInAnyOrderElementsOf(BEFORE_UPDATE_TRIGGERS.keySet());
    }

    /** The reason for the rule: the foreign-key check of a referencing write takes FOR KEY SHARE on the row. */
    @Test
    void shouldLetAForeignKeyCheckPassAnUpdateThatNamesTheKeyOnlyWithoutABeforeUpdateTrigger() throws Exception {
        var database = freshDatabase();
        PracticeMigrationFixture.execute(database, """
                CREATE TABLE parent (id int PRIMARY KEY, note text, slug text);
                INSERT INTO parent VALUES (1, 'a');
                CREATE FUNCTION pass() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                CREATE TRIGGER guard AFTER UPDATE ON parent FOR EACH ROW EXECUTE FUNCTION pass();
                """);
        assertThat(keyShareWhileTheKeyIsNamed(database)).isTrue();

        PracticeMigrationFixture.execute(database, """
                DROP TRIGGER guard ON parent;
                CREATE TRIGGER guard BEFORE UPDATE OF slug ON parent FOR EACH ROW EXECUTE FUNCTION pass();
                """);
        assertThat(keyShareWhileTheKeyIsNamed(database)).isFalse();
    }

    @Test
    void shouldRestoreTheSchemaExactlyWhenRolledBack() throws Exception {
        var database = freshDatabase();
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var before = fingerprint(database);

        liquibase(database, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));
        liquibase(database, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));

        var after = fingerprint(database);
        assertThat(after).as(SchemaFingerprint.difference(before, after)).isEqualTo(before);
    }

    private static boolean keyShareWhileTheKeyIsNamed(TestDatabase database) throws SQLException {
        try (var writer = connect(database);
                var reader = connect(database)) {
            writer.setAutoCommit(false);
            reader.setAutoCommit(false);
            try (var update = writer.createStatement();
                    var keyShare = reader.createStatement()) {
                update.execute("UPDATE parent SET note = 'b', id = id WHERE id = 1");
                keyShare.execute("SELECT 1 FROM parent WHERE id = 1 FOR KEY SHARE NOWAIT");
                return true;
            } catch (SQLException locked) {
                if (!"55P03".equals(locked.getSQLState())) throw locked;
                return false;
            } finally {
                reader.rollback();
                writer.rollback();
            }
        }
    }

    private static TestDatabase freshDatabase() throws Exception {
        return PostgreSQLTestContainer.createDatabase(
                "guard_trigger_timing_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static List<String> fingerprint(TestDatabase database) throws SQLException {
        try (var connection = connect(database)) {
            return SchemaFingerprint.of(connection);
        }
    }

    private interface Migration {
        void run(Liquibase liquibase) throws Exception;
    }

    private static void liquibase(TestDatabase database, Migration migration) throws Exception {
        try (var connection = connect(database);
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            migration.run(liquibase);
        }
    }
}
