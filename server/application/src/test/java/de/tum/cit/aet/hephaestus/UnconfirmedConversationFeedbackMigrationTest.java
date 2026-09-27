package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class UnconfirmedConversationFeedbackMigrationTest {
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("unconfirmed_conversation_feedback_migration");

    @Test
    void shouldMarkOnlyConversationFeedbackWhoseTurnDidNotShowItAsUnconfirmed() throws Exception {
        execute("""
            CREATE TABLE feedback (
                id text PRIMARY KEY, channel varchar(32) NOT NULL, delivery_state varchar(32) NOT NULL,
                delivered_at timestamptz,
                CONSTRAINT chk_feedback_state CHECK (delivery_state IN ('AWAITING_APPROVAL', 'PREPARED',
                    'PARTIALLY_DELIVERED', 'PARTIALLY_FAILED', 'DELIVERED', 'DISCARDED', 'SUPERSEDED', 'SUPPRESSED',
                    'FAILED')));
            CREATE TABLE feedback_observation (feedback_id text NOT NULL, observation_id uuid NOT NULL);
            CREATE TABLE chat_message (id uuid PRIMARY KEY, parts jsonb NOT NULL DEFAULT '[]'::jsonb);
            CREATE TABLE feedback_placement (
                feedback_id text NOT NULL, placement_type varchar(32) NOT NULL, chat_message_id uuid,
                created_at timestamptz NOT NULL DEFAULT '2026-09-01T10:00:00Z');
            """);
        String a = "00000000-0000-0000-0000-00000000000a";
        String b = "00000000-0000-0000-0000-00000000000b";
        execute("INSERT INTO chat_message VALUES "
                + "('10000000-0000-0000-0000-000000000001', '[{\"type\":\"text\",\"text\":\"Hi\"},"
                + "{\"type\":\"data-observation\",\"id\":\"" + a + "\",\"data\":{\"observationId\":\"" + a + "\"}}]'),"
                + "('10000000-0000-0000-0000-000000000002', '[{\"type\":\"data-observation\",\"id\":\"p\","
                + "\"data\":{\"observationId\":\"" + a + "\",\"text\":\"Say why.\"}}]'),"
                + "('10000000-0000-0000-0000-000000000003', '[{\"type\":\"data-observation\",\"id\":\"q\","
                + "\"data\":{\"observationId\":\"" + b + "\",\"text\":\"Say why.\"}}]')");
        execute("""
            INSERT INTO feedback VALUES
                ('linked-only', 'IN_CHAT', 'DELIVERED', '2026-09-01T10:00:00Z'),
                ('shown', 'IN_CHAT', 'DELIVERED', '2026-09-01T10:00:00Z'),
                ('shown-another', 'IN_CHAT', 'DELIVERED', '2026-09-01T10:00:00Z'),
                ('no-turn', 'IN_CHAT', 'DELIVERED', '2026-09-01T10:00:00Z'),
                ('on-the-work', 'IN_CONTEXT', 'DELIVERED', '2026-09-01T10:00:00Z'),
                ('prepared', 'IN_CHAT', 'PREPARED', NULL);
            INSERT INTO feedback_placement VALUES
                ('linked-only', 'CONVERSATION_TURN', '10000000-0000-0000-0000-000000000001'),
                ('shown', 'CONVERSATION_TURN', '10000000-0000-0000-0000-000000000002'),
                ('shown-another', 'CONVERSATION_TURN', '10000000-0000-0000-0000-000000000003');
            """);
        for (String feedback : new String[] {"linked-only", "shown", "shown-another", "no-turn", "prepared"}) {
            execute("INSERT INTO feedback_observation VALUES ('" + feedback + "', '" + a + "')");
        }

        migrate();

        assertThat(states())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "linked-only", "UNCONFIRMED",
                        "shown", "DELIVERED",
                        "shown-another", "UNCONFIRMED",
                        "no-turn", "UNCONFIRMED",
                        "on-the-work", "DELIVERED",
                        "prepared", "PREPARED"));
        assertThat(column("SELECT id, delivered_at IS NOT NULL FROM feedback"))
                .as("an unconfirmed row keeps no delivery time; a delivered one keeps its own")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "linked-only", "f",
                        "shown", "t",
                        "shown-another", "f",
                        "no-turn", "f",
                        "on-the-work", "t",
                        "prepared", "f"));
        assertThat(column("SELECT feedback_id, created_at = '2026-09-01T10:00:00Z' FROM feedback_placement"))
                .as("the placement still records when the turn linked it")
                .containsOnlyKeys("linked-only", "shown", "shown-another")
                .doesNotContainValue("f");
        execute("INSERT INTO feedback VALUES ('later', 'IN_CHAT', 'UNCONFIRMED', NULL)");
        assertThatThrownBy(() -> execute("INSERT INTO feedback VALUES ('junk', 'IN_CHAT', 'LINKED', NULL)"))
                .isInstanceOf(SQLException.class);
    }

    private static Map<String, String> states() throws SQLException {
        return column("SELECT id, delivery_state FROM feedback");
    }

    private static Map<String, String> column(String query) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.put(rows.getString(1), rows.getString(2));
            }
        }
        return values;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate() throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect()));
        try (Liquibase liquibase = new Liquibase(
                "db/changelog/1790499575075_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(new Contexts());
        }
    }
}
