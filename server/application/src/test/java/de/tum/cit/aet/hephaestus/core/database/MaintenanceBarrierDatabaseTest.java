package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The database refuses every new lease while a migration runs, whatever statement takes it (ADR 0096). A table holds
 * leases by convention: a column says who holds a row or until when ({@code lease}, {@code heartbeat}), names the
 * holder's generation ({@code _epoch}), or moves the next attempt out while one runs ({@code next_attempt_at}). Each such
 * table has an enabled row trigger on {@code refuse_lease_during_migration()} for a new lease by UPDATE, and also for
 * INSERT when a row can be inserted already leased ({@code leased_until}, {@code heartbeat_at}). A table that matches
 * the convention but whose rows nobody leases is listed below with its reason.
 */
@Tag("database")
class MaintenanceBarrierDatabaseTest {

    /** Tables that match the convention without a new lease of their own, and why. */
    static final Map<String, String> NOT_LEASED = Map.of(
            "composer_transcript_part",
            "lease_epoch names the epoch of the run that wrote it",
            "conversation",
            "scope_epoch counts history removals; agent_run leases conversation work",
            "llm_usage_event",
            "lease_epoch names the epoch of the run whose model call it counts",
            "mentor_request",
            "its _epoch columns are authority and reader epochs, not a lease; R2's mentor_attempt holds the lease",
            "review_job",
            "scan_call_lease serializes the model calls of a running review, which finishes");

    static final String CONVENTION =
            "(a.attname ~ '(lease|heartbeat)' OR a.attname ~ '_epoch$' OR a.attname = 'next_attempt_at')";

    /** An enabled row trigger of the barrier on the table for the event bit: 4 is INSERT, 16 is UPDATE. */
    static String guard(int event) {
        return "EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid WHERE t.tgrelid = c.oid"
                + " AND p.proname = 'refuse_lease_during_migration' AND t.tgenabled <> 'D' AND (t.tgtype & 1) = 1"
                + " AND (t.tgtype & " + event + ") = " + event + ")";
    }

    /** The lease tables that lack a trigger they need. */
    static final String UNGUARDED = "SELECT c.relname FROM pg_class c"
            + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p') AND NOT c.relispartition"
            + " AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND NOT a.attisdropped"
            + " AND a.attnum > 0 AND " + CONVENTION + ")"
            + " AND (NOT " + guard(16)
            + " OR (NOT " + guard(4)
            + " AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND NOT a.attisdropped"
            + " AND a.attname IN ('leased_until', 'heartbeat_at'))))"
            + " ORDER BY 1";

    /** The tables that the barrier's triggers guard. */
    static final String GUARDED = """
            SELECT DISTINCT c.relname FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
            JOIN pg_class c ON c.oid = t.tgrelid
            WHERE p.proname = 'refuse_lease_during_migration' AND t.tgparentid = 0
            ORDER BY 1
            """;

    @Test
    void shouldGuardEveryTableThatHoldsLeases() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");

        assertThat(PracticeMigrationFixture.column(database, UNGUARDED))
                .as("a lease table whose new leases the barrier does not refuse")
                .containsExactlyInAnyOrderElementsOf(NOT_LEASED.keySet());
        assertThat(PracticeMigrationFixture.column(database, GUARDED))
                .contains(
                        "agent_action",
                        "agent_run",
                        "product_feedback_filing",
                        "provider_event",
                        "provider_resource_repair",
                        "sync_job");
    }

    /** The check finds a lease table by its columns, not by a list of names, so a new one cannot slip past. */
    @Test
    void shouldReportANewLeaseTableThatHasNoBarrierTrigger() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");

        assertThat(unguardedWith(database, "CREATE TABLE barrier_probe (id int PRIMARY KEY, leased_until timestamptz)"))
                .contains("barrier_probe");
        assertThat(unguardedWith(
                        database, "CREATE TABLE barrier_probe (id int PRIMARY KEY, lease_expires_at timestamptz)"))
                .as("a lease of another column shape")
                .contains("barrier_probe");
        assertThat(unguardedWith(database, """
                        CREATE TABLE barrier_probe (id int PRIMARY KEY, lease_epoch int);
                        CREATE TRIGGER barrier_probe_update AFTER UPDATE ON barrier_probe FOR EACH ROW
                            EXECUTE FUNCTION refuse_lease_during_migration();
                        ALTER TABLE barrier_probe DISABLE TRIGGER barrier_probe_update;
                        """))
                .as("a disabled trigger guards nothing")
                .contains("barrier_probe");
        assertThat(unguardedWith(database, """
                        CREATE TABLE barrier_probe (id int PRIMARY KEY, heartbeat_at timestamptz);
                        CREATE TRIGGER barrier_probe_update AFTER UPDATE ON barrier_probe FOR EACH ROW
                            EXECUTE FUNCTION refuse_lease_during_migration();
                        """))
                .as("a table that guards only its updates still takes a leased INSERT")
                .contains("barrier_probe");
    }

    /** Runs {@code ddl} and the check in one transaction, and rolls both back. */
    private static List<String> unguardedWith(TestDatabase database, String ddl) throws Exception {
        try (var session = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            session.setAutoCommit(false);
            try (var statement = session.createStatement()) {
                statement.execute(ddl);
                var found = new ArrayList<String>();
                try (var rows = statement.executeQuery(UNGUARDED)) {
                    while (rows.next()) found.add(rows.getString(1));
                }
                return found;
            } finally {
                session.rollback();
            }
        }
    }
}
