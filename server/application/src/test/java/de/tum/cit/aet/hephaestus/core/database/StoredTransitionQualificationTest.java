package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every state change that the server makes on a stored row is legal for the database: no check, trigger or key that a
 * migration adds refuses it. A pull request that adds stored states registers each of its transitions here.
 */
@Tag("database")
class StoredTransitionQualificationTest {

    /** The registered transitions. Each one arranges a row in its source state, then moves it to its target state. */
    static final List<StoredTransition> TRANSITIONS = List.of();

    @Test
    void shouldApplyEveryRegisteredTransitionOnTheMigratedSchema() {
        var database = PostgreSQLTestContainer.createMigratedDatabase("stored_transitions");
        assertThat(refusals(database, TRANSITIONS)).isEmpty();
    }

    @Test
    void shouldReportARegisteredTransitionThatAConstraintRefuses() {
        var database = PostgreSQLTestContainer.createDatabase("stored_transitions_fixture");
        PracticeMigrationFixture.execute(database, """
                CREATE TABLE stored_state (id int PRIMARY KEY, state varchar(10) NOT NULL
                    CONSTRAINT ck_stored_state_state CHECK (state IN ('OPEN', 'CLOSED')))
                """);
        var close = new StoredTransition(
                "stored_state OPEN -> CLOSED",
                "INSERT INTO stored_state VALUES (1, 'OPEN')",
                "UPDATE stored_state SET state = 'CLOSED' WHERE id = 1");
        var archive = new StoredTransition(
                "stored_state CLOSED -> ARCHIVED",
                "INSERT INTO stored_state VALUES (1, 'CLOSED')",
                "UPDATE stored_state SET state = 'ARCHIVED' WHERE id = 1");

        assertThat(refusals(database, List.of(close, archive)))
                .singleElement()
                .asString()
                .startsWith("stored_state CLOSED -> ARCHIVED: ")
                .contains("ck_stored_state_state");
    }

    /**
     * Runs each transition in its own transaction, which it then rolls back. Deferred constraints are checked at once,
     * so a refusal that would come at commit is reported too.
     */
    private static List<String> refusals(TestDatabase database, List<StoredTransition> transitions) {
        var refusals = new ArrayList<String>();
        for (var transition : transitions) {
            try (var connection =
                            DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                    var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
                statement.execute(transition.arrange());
                try {
                    statement.execute(transition.transition());
                } catch (SQLException refused) {
                    refusals.add(transition.name() + ": " + refused.getMessage());
                } finally {
                    connection.rollback();
                }
            } catch (SQLException arrangement) {
                throw new IllegalStateException("Cannot arrange " + transition.name(), arrangement);
            }
        }
        return refusals;
    }

    /**
     * One state change of a stored row.
     *
     * @param arrange SQL that inserts the row, and any row it needs, in the source state
     * @param transition the SQL that the server runs to reach the target state
     */
    record StoredTransition(String name, String arrange, String transition) {}
}
