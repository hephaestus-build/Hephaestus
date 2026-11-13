package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest.State;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A completed erasure receipt never reopens, and no request is left in the retired EXPIRED state. */
@Tag("database")
class PersonDataRequestLifecycleMigrationTest {
    private static final String CHANGELOG = "-rootfix-person-data-lifecycle.xml";
    private static final String EXPIRED = "00000000-0000-0000-0000-000000979999";

    @Test
    void shouldKeepACompletedRequestCompleteAndRepairExpiredPreviewsWhenUpgrading() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "person_data_lifecycle_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        for (var before : State.values()) {
            for (var after : State.values()) insert(database, id(before, after), before.name());
        }
        insert(database, EXPIRED, "EXPIRED");

        liquibase(database, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            // An expired preview stays expired: it can no longer be admitted.
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT state || '/' || (expires_at <= CURRENT_TIMESTAMP)::text"
                                    + " FROM person_data_request WHERE id='" + EXPIRED + "'"))
                    .containsExactly("PREVIEW/true");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeUpdate(update(EXPIRED, "EXPIRED")))
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));

            for (var before : State.values()) {
                for (var after : State.values()) {
                    String id = id(before, after);
                    boolean refused = before == State.COMPLETE && after != State.COMPLETE;
                    if (refused) {
                        assertThatExceptionOfType(SQLException.class)
                                .isThrownBy(() -> statement.executeUpdate(update(id, after.name())))
                                .satisfies(failure ->
                                        assertThat(failure.getSQLState()).isEqualTo("55000"));
                    } else {
                        assertThat(statement.executeUpdate(update(id, after.name())))
                                .isEqualTo(1);
                    }
                    assertThat(state(database, id)).containsExactly((refused ? before : after).name());
                }
            }
        }

        liquibase(database, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));
        PracticeMigrationFixture.execute(database, update(id(State.COMPLETE, State.ERASING), "ERASING"));
        PracticeMigrationFixture.execute(database, update(EXPIRED, "EXPIRED"));
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM pg_trigger WHERE tgname='person_data_request_transition'"))
                .containsExactly("0");
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

    private static String id(State before, State after) {
        var states = List.of(State.values());
        return "00000000-0000-0000-0000-0000009799%d%d".formatted(states.indexOf(before), states.indexOf(after));
    }

    private static List<@Nullable String> state(TestDatabase database, String id) {
        return PracticeMigrationFixture.column(database, "SELECT state FROM person_data_request WHERE id='" + id + "'");
    }

    private static void insert(TestDatabase database, String id, String state) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO person_data_request(id,state,counts_json,completed_json,created_at,expires_at,version)
                VALUES('%s','%s','{}','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '2 days',0);
                """.formatted(id, state));
    }

    private static String update(String id, String state) {
        return "UPDATE person_data_request SET state='%s' WHERE id='%s'".formatted(state, id);
    }
}
