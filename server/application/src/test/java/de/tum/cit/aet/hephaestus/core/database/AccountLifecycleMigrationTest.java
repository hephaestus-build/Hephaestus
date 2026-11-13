package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** An account whose deletion has started never comes back, also when a writer uses SQL. */
@Tag("database")
class AccountLifecycleMigrationTest {
    private static final String CHANGELOG = "-rootfix-account-lifecycle.xml";
    private static final long FIRST = 979500;

    @Test
    void shouldKeepADeletionMovingForwardWhenUpgradingAndSupportRollback() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "account_lifecycle_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        for (var before : Status.values()) {
            for (var after : Status.values()) insert(database, id(before, after), before);
        }
        liquibase(database, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            for (var before : Status.values()) {
                for (var after : Status.values()) {
                    long id = id(before, after);
                    if (refused(before, after)) {
                        assertThatExceptionOfType(SQLException.class)
                                .isThrownBy(() -> statement.executeUpdate(update(id, after)))
                                .satisfies(failure ->
                                        assertThat(failure.getSQLState()).isEqualTo("55000"));
                    } else {
                        assertThat(statement.executeUpdate(update(id, after))).isEqualTo(1);
                    }
                    assertThat(status(database, id)).containsExactly((refused(before, after) ? before : after).name());
                }
            }
            // The purge still anonymizes a deleted account: only its status is final.
            assertThat(statement.executeUpdate("UPDATE account SET display_name='Deleted account' WHERE id="
                            + id(Status.DELETED, Status.DELETED)))
                    .isEqualTo(1);
        }

        liquibase(database, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));
        PracticeMigrationFixture.execute(database, update(id(Status.DELETED, Status.ACTIVE), Status.ACTIVE));
        assertThat(status(database, id(Status.DELETED, Status.ACTIVE))).containsExactly(Status.ACTIVE.name());
    }

    private static boolean refused(Status before, Status after) {
        return (before == Status.DELETED && after != Status.DELETED)
                || (before == Status.DELETING
                        && !Set.of(Status.DELETING, Status.DELETED).contains(after));
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

    private static long id(Status before, Status after) {
        var statuses = List.of(Status.values());
        return FIRST + 10L * statuses.indexOf(before) + statuses.indexOf(after);
    }

    private static List<@Nullable String> status(TestDatabase database, long id) {
        return PracticeMigrationFixture.column(database, "SELECT status FROM account WHERE id=" + id);
    }

    private static void insert(TestDatabase database, long id, Status status) {
        PracticeMigrationFixture.execute(
                database,
                "INSERT INTO account(id,display_name,status) OVERRIDING SYSTEM VALUE VALUES(%d,'Account %d','%s')"
                        .formatted(id, id, status.name()));
    }

    private static String update(long id, Status status) {
        return "UPDATE account SET status='%s' WHERE id=%d".formatted(status.name(), id);
    }
}
