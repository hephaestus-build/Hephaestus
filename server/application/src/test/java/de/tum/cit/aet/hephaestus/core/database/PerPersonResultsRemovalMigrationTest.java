package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0125 decision 2: per-person results for admins are removed. Each workspace that had them on records one system
 * change. The column stays for one release, so a server of the previous release still reads its workspaces during the
 * rolling upgrade (#5365 drops it).
 */
@Tag("database")
class PerPersonResultsRemovalMigrationTest {
    private static final String CHANGELOG = "20261127141851-feat-private-data-operator-model.xml";
    private static final long NAMED = 979_410;
    private static final long UNNAMED = 979_411;

    @Test
    void shouldRecordTheRemovalWhileThePreviousServerCanStillReadWorkspaces() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "per_person_removal_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        insert(database, NAMED, true);
        insert(database, UNNAMED, false);

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(audit(database, NAMED))
                    .containsExactly(
                            "SYSTEM WORKSPACE_VISIBILITY DELETED {perPersonResultsForAdmins} {\"perPersonResultsForAdmins\":true} null");
            assertThat(audit(database, UNNAMED)).isEmpty();
            // The previous server maps the column and reads it with every workspace.
            assertThat(enabled(database, NAMED)).containsExactly("true");
            assertThat(enabled(database, UNNAMED)).containsExactly("false");

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);

            assertThat(enabled(database, NAMED)).containsExactly("true");
            // The config audit trail is append-only: the row that recorded the removal stays.
            assertThat(audit(database, NAMED)).hasSize(1);
        }
    }

    private static void insert(TestDatabase database, long id, boolean perPersonResults) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status,per_person_results_for_admins)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE',%2$s);
                """.formatted(id, perPersonResults));
    }

    private static List<String> audit(TestDatabase database, long workspaceId) {
        return PracticeMigrationFixture.column(
                        database,
                        "SELECT actor_kind || ' ' || entity_type || ' ' || action || ' ' || changed_keys::text || ' '"
                                + " || COALESCE(old_value, 'null') || ' ' || COALESCE(new_value, 'null')"
                                + " FROM config_audit_event WHERE workspace_id = " + workspaceId
                                + " AND entity_type = 'WORKSPACE_VISIBILITY'")
                .stream()
                .map(String::valueOf)
                .toList();
    }

    private static List<@Nullable String> enabled(TestDatabase database, long workspaceId) {
        return PracticeMigrationFixture.column(
                database, "SELECT per_person_results_for_admins::text FROM workspace WHERE id = " + workspaceId);
    }
}
