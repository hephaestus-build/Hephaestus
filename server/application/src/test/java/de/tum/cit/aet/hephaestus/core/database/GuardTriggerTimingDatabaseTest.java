package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A guard that only validates an UPDATE runs AFTER the row write. With any BEFORE UPDATE row trigger, PostgreSQL locks
 * the row FOR UPDATE whenever an UPDATE names a unique column, and that lock blocks every foreign-key write that
 * references the row until commit. A BEFORE UPDATE trigger is allowed only where it must act before the write; each
 * one names why. INSERT and DELETE guards are not affected and stay BEFORE.
 */
@Tag("database")
class GuardTriggerTimingDatabaseTest {
    private static final String CHANGELOG = "-rootfix-after-guard-triggers.xml";

    private static final Map<String, String> BEFORE_UPDATE_TRIGGERS = Map.of(
            "workspace.trg_reserve_workspace_slug", "reserves the new slug before the row holds it",
            "problem.problem_parent_touch", "touches the parent result so that its own guard sees the change",
            "llm_model.llm_model_own_call_pool", "gives the model its own call pool by setting NEW.call_pool_id",
            "workspace_llm_model.workspace_llm_model_own_call_pool",
                    "gives the model its own call pool by setting NEW.call_pool_id",
            "auth_event.trg_auth_event_block_mutation", "refuses every update of an append-only audit row",
            "config_audit_event.trg_config_audit_event_block_mutation",
                    "refuses every update of an append-only audit row");

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

    @Test
    void shouldRestoreTheBeforeTriggersWhenRolledBack() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "guard_trigger_timing_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var before = PracticeMigrationFixture.column(database, BEFORE_UPDATE_ROW_TRIGGERS);

        liquibase(database, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(database, BEFORE_UPDATE_ROW_TRIGGERS))
                .containsExactlyInAnyOrderElementsOf(BEFORE_UPDATE_TRIGGERS.keySet());

        liquibase(database, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(database, BEFORE_UPDATE_ROW_TRIGGERS))
                .isEqualTo(before);
    }

    private interface Migration {
        void run(Liquibase liquibase) throws Exception;
    }

    private static void liquibase(TestDatabase database, Migration migration) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            migration.run(liquibase);
        }
    }
}
