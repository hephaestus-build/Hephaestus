package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class WorkspacePurgedMigrationTest {
    private static final long FIRST = 979300;

    @Test
    void shouldRefuseEveryStatusChangeOfAPurgedWorkspaceAndSupportRollback() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "workspace_purged_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", "-rootfix-workspace-purged-final.xml");
        for (var before : WorkspaceStatus.values()) {
            for (var after : WorkspaceStatus.values()) insert(database, id(before, after), before);
        }
        long erased = FIRST + 99;
        insert(database, erased, WorkspaceStatus.PURGED);
        migrate(database, false);

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            for (var before : WorkspaceStatus.values()) {
                for (var after : WorkspaceStatus.values()) {
                    String sql = update(id(before, after), after);
                    boolean refused = before == WorkspaceStatus.PURGED && after != WorkspaceStatus.PURGED;
                    if (refused) {
                        assertThatExceptionOfType(SQLException.class)
                                .isThrownBy(() -> statement.executeUpdate(sql))
                                .satisfies(failure ->
                                        assertThat(failure.getSQLState()).isEqualTo("55000"));
                    } else {
                        assertThat(statement.executeUpdate(sql)).isEqualTo(1);
                    }
                    assertThat(status(database, id(before, after))).containsExactly((refused ? before : after).name());
                }
            }
        }

        // Erasure still anonymizes a purged workspace: only its status is final.
        PracticeMigrationFixture.execute(
                database,
                "UPDATE workspace SET slug='erased-%1$d',display_name='Erased workspace' WHERE id=%1$d"
                        .formatted(erased));
        migrate(database, true);
        PracticeMigrationFixture.execute(database, update(erased, WorkspaceStatus.ACTIVE));
        PracticeMigrationFixture.execute(database, update(erased, WorkspaceStatus.PURGED));
        migrate(database, false);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeUpdate(update(erased, WorkspaceStatus.ACTIVE)))
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("55000"));
        }
    }

    private static long id(WorkspaceStatus before, WorkspaceStatus after) {
        var statuses = List.of(WorkspaceStatus.values());
        return FIRST + 10L * statuses.indexOf(before) + statuses.indexOf(after);
    }

    private static List<@Nullable String> status(TestDatabase database, long id) {
        return PracticeMigrationFixture.column(database, "SELECT status FROM workspace WHERE id=" + id);
    }

    private static void insert(TestDatabase database, long id, WorkspaceStatus status) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','%2$s');
                """.formatted(id, status.name()));
    }

    private static String update(long id, WorkspaceStatus status) {
        return "UPDATE workspace SET status='%s' WHERE id=%d".formatted(status.name(), id);
    }

    private static void migrate(TestDatabase database, boolean rollback) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            if (rollback) liquibase.rollback(1, new Contexts("prod"), new LabelExpression());
            else liquibase.update(1, new Contexts("prod"), new LabelExpression());
        }
    }
}
