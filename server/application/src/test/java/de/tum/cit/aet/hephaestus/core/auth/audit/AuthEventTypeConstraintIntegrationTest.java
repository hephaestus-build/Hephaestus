package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class AuthEventTypeConstraintIntegrationTest {

    private static final String PARTITION = "auth_event_constraint_test";

    @Test
    void shouldAdmitEveryDeclaredEventTypeAndRejectAnUnknownOneAfterMigration() throws SQLException {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("auth_event_type_constraint");
        // The prod context carries the append-only trigger, which the dev context does not.
        PostgreSQLTestContainer.migrateDatabase(database, "dev,prod");

        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            try (var statement = connection.createStatement()) {
                // auth_event is RANGE-partitioned and pg_partman only pre-creates partitions around
                // "now", so an insert needs a partition covering the fixed date this test writes into.
                statement.executeUpdate("CREATE TABLE " + PARTITION + " PARTITION OF auth_event "
                        + "FOR VALUES FROM ('2000-01-01') TO ('2000-02-01')");
            }

            try (var insert = connection.prepareStatement("INSERT INTO auth_event (occurred_at, event_type, result)"
                    + " VALUES ('2000-01-15', ?, 'SUCCESS')")) {
                for (AuthEvent.EventType type : AuthEvent.EventType.values()) {
                    insert.setString(1, type.name());
                    assertThat(insert.executeUpdate()).as("insert %s", type).isEqualTo(1);
                }
                insert.setString(1, "NOT_AN_EVENT_TYPE");
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_auth_event_event_type")
                        .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState())
                                .as("check_violation")
                                .isEqualTo("23514"));
            }

            // Redacting PII must not authorize changes to the recorded elevation status.
            try (var update = connection.createStatement()) {
                assertThatThrownBy(() -> update.executeUpdate(
                                "UPDATE auth_event SET elevated_via_instance_admin = true, ip_inet = NULL,"
                                        + " user_agent = NULL, details = NULL"
                                        + " WHERE event_type = 'WORKSPACE_ELEVATION' AND occurred_at = '2000-01-15'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }

            try (var statement = connection.createStatement();
                    var elevated = statement.executeQuery(
                            "SELECT elevated_via_instance_admin FROM auth_event WHERE event_type ="
                                    + " 'WORKSPACE_ELEVATION' AND occurred_at = '2000-01-15'")) {
                assertThat(elevated.next()).isTrue();
                assertThat(elevated.getBoolean(1))
                        .as("column defaults to false rather than NULL")
                        .isFalse();
            }
        }
    }
}
