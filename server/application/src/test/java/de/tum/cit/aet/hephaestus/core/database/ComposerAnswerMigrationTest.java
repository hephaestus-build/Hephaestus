package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The kept composer answer clears with its claim of a compose run only, and a rollback removes it with its trigger. */
@Tag("database")
class ComposerAnswerMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("composer_answer");
    private static final String CHANGELOG = "db/changelog/next/20261116103004-fix-composer-replays-recorded-answer.xml";

    @Test
    void shouldClearOnlyTheEndedComposeClaimsAnswersAndRollBackToTheDigestOnlyTranscript() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE agent_run (
                        id uuid PRIMARY KEY, workspace_id bigint NOT NULL, kind varchar(32) NOT NULL,
                        state varchar(16) NOT NULL, lease_epoch integer NOT NULL
                    );
                    CREATE TABLE composer_transcript_part (
                        id uuid PRIMARY KEY, workspace_id bigint NOT NULL, run_id uuid NOT NULL,
                        lease_epoch integer NOT NULL, kind varchar(16) NOT NULL, digest varchar(128) NOT NULL
                    );
                    """);
            connection.commit();
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);

                UUID compose = run(connection, "REVIEW_COMPOSE");
                UUID review = run(connection, "REVIEW");
                UUID earlier = served(connection, compose, 1);
                UUID current = served(connection, compose, 2);
                UUID other = served(connection, review, 2);
                assertThatThrownBy(() -> part(connection, compose, 2, "REPLY", true))
                        .hasMessageContaining("ck_composer_transcript_part_answer");
                connection.rollback();

                update(connection, "UPDATE agent_run SET state = 'QUEUED' WHERE id IN (?, ?)", compose, review);

                assertThat(hasAnswer(connection, current)).as("the ended claim").isFalse();
                assertThat(hasAnswer(connection, earlier)).as("another epoch").isTrue();
                assertThat(hasAnswer(connection, other))
                        .as("a run that does not compose")
                        .isTrue();

                liquibase.rollback(1, contexts, labels);

                update(
                        connection,
                        "UPDATE agent_run SET state = 'LEASED', lease_epoch = 3 WHERE id IN (?, ?)",
                        compose,
                        review);
                assertThat(count(
                                connection,
                                "SELECT count(*) FROM information_schema.columns"
                                        + " WHERE table_name = 'composer_transcript_part' AND column_name = 'answer'"))
                        .isZero();
                assertThat(count(connection, "SELECT count(*) FROM composer_transcript_part"))
                        .as("the digests stay")
                        .isEqualTo(3);
            }
        }
    }

    private static UUID run(Connection connection, String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var insert = connection.prepareStatement(
                "INSERT INTO agent_run (id, workspace_id, kind, state, lease_epoch) VALUES (?, 1, ?, 'LEASED', 2)")) {
            insert.setObject(1, id);
            insert.setString(2, kind);
            insert.executeUpdate();
        }
        connection.commit();
        return id;
    }

    private static UUID served(Connection connection, UUID run, int epoch) throws SQLException {
        UUID id = part(connection, run, epoch, "SERVED", true);
        connection.commit();
        return id;
    }

    private static UUID part(Connection connection, UUID run, int epoch, String kind, boolean answer)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (var insert = connection.prepareStatement("INSERT INTO composer_transcript_part"
                + " (id, workspace_id, run_id, lease_epoch, kind, digest, answer) VALUES (?, 1, ?, ?, ?, 'd', ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, run);
            insert.setInt(3, epoch);
            insert.setString(4, kind);
            insert.setBytes(5, answer ? new byte[] {'{', '}'} : null);
            insert.executeUpdate();
        }
        return id;
    }

    private static void update(Connection connection, String sql, UUID first, UUID second) throws SQLException {
        try (var update = connection.prepareStatement(sql)) {
            update.setObject(1, first);
            update.setObject(2, second);
            update.executeUpdate();
        }
        connection.commit();
    }

    private static boolean hasAnswer(Connection connection, UUID part) throws SQLException {
        try (var select =
                connection.prepareStatement("SELECT answer IS NOT NULL FROM composer_transcript_part WHERE id = ?")) {
            select.setObject(1, part);
            try (var rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getBoolean(1);
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var select = connection.createStatement();
                var rows = select.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
