package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every state change that the server makes on a stored row is legal for the database: no check, trigger or key that a
 * migration adds refuses it. A pull request that adds stored states registers each of its transitions here, and each one must change the row it
 * arranged into the declared target state.
 */
@Tag("database")
class StoredTransitionQualificationTest {

    /**
     * The registered transitions. Each one arranges a row in its source state, moves it to its target state, and
     * proves that the row reached it.
     */
    static final List<StoredTransition> TRANSITIONS = List.of(
            new StoredTransition(
                    "workspace authority_generation 0 -> 1 when a membership ends (ADR 0134)",
                    """
                    INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                        VALUES(997101,'w997101','W997101','w997101','ORG','ACTIVE');
                    INSERT INTO account(id,display_name) VALUES(997101,'Leaving member');
                    INSERT INTO membership(workspace_id,account_id,role) VALUES(997101,997101,'MEMBER')""",
                    "DELETE FROM membership WHERE workspace_id = 997101 AND account_id = 997101",
                    "SELECT authority_generation = 1 FROM workspace WHERE id = 997101"),
            new StoredTransition("agent_run LEASED -> ENDED AUTHORITY_CHANGED (ADR 0134)", """
                    INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                        VALUES(997102,'w997102','W997102','w997102','ORG','ACTIVE');
                    INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,
                        leased_until,available_at,created_at,started_at)
                        VALUES('00000000-0000-4000-8000-000000997102',997102,'TURN','INTERACTIVE',
                            'conversation:997102','LEASED',0,1,now() + interval '1 minute',now(),now(),now())""", """
                    UPDATE agent_run SET state = 'ENDED', leased_until = NULL, stop_reason = 'AUTHORITY_CHANGED',
                        ended_at = clock_timestamp()
                    WHERE id = '00000000-0000-4000-8000-000000997102' AND lease_epoch = 1 AND state = 'LEASED'""", """
                    SELECT state = 'ENDED' AND stop_reason = 'AUTHORITY_CHANGED'
                    FROM agent_run WHERE id = '00000000-0000-4000-8000-000000997102'"""),
            new StoredTransition("review -> CANCELLED AUTHORITY_CHANGED (ADR 0134)", """
                    INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                        VALUES(997103,'w997103','W997103','w997103','ORG','ACTIVE');
                    INSERT INTO item(id,workspace_id,kind,source_id) VALUES(997103,997103,'test.item',997103);
                    INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,ordered_at)
                        VALUES('00000000-0000-4000-8000-000000997103',997103,997103,'ITEM','test:1','REQUEST',now())""", """
                    UPDATE review SET status = 'CANCELLED', stop_reason = 'AUTHORITY_CHANGED', completed_at = now()
                    WHERE id = '00000000-0000-4000-8000-000000997103'""", """
                    SELECT status = 'CANCELLED' AND stop_reason = 'AUTHORITY_CHANGED'
                    FROM review WHERE id = '00000000-0000-4000-8000-000000997103'"""),
            actionEnd(997104, false, "CANCELLED"),
            actionEnd(997105, true, "UNCERTAIN"));

    /** An outbox action that the send boundary refused, before or after a write of it started (ADR 0134). */
    private static StoredTransition actionEnd(int id, boolean writeStarted, String state) {
        String action = "00000000-0000-4000-8000-000000%06d".formatted(id);
        return new StoredTransition(
                "agent_action EXECUTING%s -> %s AUTHORITY_CHANGED (ADR 0134)"
                        .formatted(writeStarted ? " after a write started" : "", state),
                """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                    VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,attempts,
                    max_attempts,next_attempt_at,lease_epoch,leased_until,write_started_at,created_at,updated_at)
                    VALUES('%2$s',%1$d,'SLACK_NOTICE','lane:%1$d','key:%1$d','EXECUTING','{}',1,3,now(),1,
                        now() + interval '1 minute',%3$s,now(),now())""".formatted(id, action, writeStarted ? "now()" : "NULL"),
                """
                UPDATE agent_action SET state = '%2$s', stop_reason = 'AUTHORITY_CHANGED', ended_at = clock_timestamp(),
                    payload = NULL, leased_until = NULL, updated_at = clock_timestamp()
                WHERE id = '%1$s' AND lease_epoch = 1 AND state = 'EXECUTING'""".formatted(action, state),
                "SELECT state = '%2$s' AND stop_reason = 'AUTHORITY_CHANGED' FROM agent_action WHERE id = '%1$s'"
                        .formatted(action, state));
    }

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
                "UPDATE stored_state SET state = 'CLOSED' WHERE id = 1",
                "SELECT state = 'CLOSED' FROM stored_state WHERE id = 1");
        var archive = new StoredTransition(
                "stored_state CLOSED -> ARCHIVED",
                "INSERT INTO stored_state VALUES (1, 'CLOSED')",
                "UPDATE stored_state SET state = 'ARCHIVED' WHERE id = 1",
                "SELECT state = 'ARCHIVED' FROM stored_state WHERE id = 1");

        assertThat(refusals(database, List.of(close, archive)))
                .singleElement()
                .asString()
                .startsWith("stored_state CLOSED -> ARCHIVED: ")
                .contains("ck_stored_state_state");
    }

    @Test
    void shouldReportARegisteredTransitionThatChangesNoRowOrMissesItsTargetState() {
        var database = PostgreSQLTestContainer.createDatabase("stored_transitions_unchanged");
        PracticeMigrationFixture.execute(database, """
                CREATE TABLE stored_state (id int PRIMARY KEY, state varchar(10) NOT NULL)
                """);
        var otherRow = new StoredTransition(
                "stored_state OPEN -> CLOSED on another row",
                "INSERT INTO stored_state VALUES (1, 'OPEN')",
                "UPDATE stored_state SET state = 'CLOSED' WHERE id = 2",
                "SELECT state = 'CLOSED' FROM stored_state WHERE id = 1");
        var wrongTarget = new StoredTransition(
                "stored_state OPEN -> CLOSED that writes OPEN",
                "INSERT INTO stored_state VALUES (1, 'OPEN')",
                "UPDATE stored_state SET state = 'OPEN' WHERE id = 1",
                "SELECT state = 'CLOSED' FROM stored_state WHERE id = 1");

        assertThat(refusals(database, List.of(otherRow, wrongTarget)))
                .containsExactly(
                        "stored_state OPEN -> CLOSED on another row: changed no row",
                        "stored_state OPEN -> CLOSED that writes OPEN: the arranged row did not reach the target state");
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
                    if (statement.executeUpdate(transition.transition()) == 0)
                        refusals.add(transition.name() + ": changed no row");
                    else if (!reached(statement, transition))
                        refusals.add(transition.name() + ": the arranged row did not reach the target state");
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

    private static boolean reached(Statement statement, StoredTransition transition) throws SQLException {
        try (var result = statement.executeQuery(transition.reached())) {
            return result.next() && result.getBoolean(1) && !result.next();
        }
    }

    /**
     * One state change of a stored row.
     *
     * @param arrange SQL that inserts the row, and any row it needs, in the source state
     * @param transition the update that the server runs to reach the target state
     * @param reached a query of one boolean row: whether the arranged row is in the target state
     */
    record StoredTransition(String name, String arrange, String transition, String reached) {}
}
