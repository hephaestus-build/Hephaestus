package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.TestDatabaseCleanup;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Row-level security will show each conversation, entry and turn only to the owner that a transaction binds. The checks
 * that keep a conversation run equal to its turn stay correct then: a foreign key reads past row-level security, and
 * the commit-time check fails when it cannot see what it checks. The seeded database turns on owner policies for the
 * three tables, and each test writes as the application role.
 */
@Tag("database")
class ConversationRunRowSecurityDatabaseTest {

    private static final String ADA_RUN = "00000000-0000-0000-0000-0000000a0001";
    private static final String ADA_QUEUED_RUN = "00000000-0000-0000-0000-0000000a0002";
    private static final String ADA_QUESTION = "00000000-0000-0000-0000-0000000e0002";
    private static final String BO_RUN = "00000000-0000-0000-0000-0000000b0001";

    /**
     * Ada (11) has an answered question and a queued one in her main conversation, and a conversation without entries in
     * a second workspace. Bo (12, unclaimed) has an answered question in his. Each conversation, entry and turn is
     * visible only under the owner that the transaction binds.
     */
    private static final String SEED = """
            INSERT INTO workspace (id, account_login, account_type, display_name, slug, status)
            VALUES (1, 'first-org', 'ORG', 'First', 'first-org', 'ACTIVE'),
                   (2, 'second-org', 'ORG', 'Second', 'second-org', 'ACTIVE');
            INSERT INTO account (id, display_name, status) VALUES (11, 'Ada', 'ACTIVE'), (12, 'Bo', 'UNCLAIMED');
            INSERT INTO conversation (id, created_at, last_activity_at, account_id, workspace_id)
            VALUES ('00000000-0000-0000-0000-00000000a001', now(), now(), 11, 1),
                   ('00000000-0000-0000-0000-00000000b001', now(), now(), 12, 1),
                   ('00000000-0000-0000-0000-00000000a002', now(), now(), 11, 2);
            BEGIN;
            INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                                   available_at, stop_reason, created_at, ended_at, account_id, conversation_id,
                                   subject_seq)
            VALUES ('00000000-0000-0000-0000-0000000a0001', 1, 'TURN', 'INTERACTIVE',
                    'conversation:00000000-0000-0000-0000-00000000a001', 'ENDED', 0, 1, now(), 'COMPLETED', now(),
                    now(), 11, '00000000-0000-0000-0000-00000000a001', 1),
                   ('00000000-0000-0000-0000-0000000a0002', 1, 'TURN', 'INTERACTIVE',
                    'conversation:00000000-0000-0000-0000-00000000a001', 'QUEUED', 0, 0, now(), NULL, now(), NULL,
                    11, '00000000-0000-0000-0000-00000000a001', 2),
                   ('00000000-0000-0000-0000-0000000b0001', 1, 'TURN', 'INTERACTIVE',
                    'conversation:00000000-0000-0000-0000-00000000b001', 'ENDED', 0, 1, now(), 'COMPLETED', now(),
                    now(), 12, '00000000-0000-0000-0000-00000000b001', 1);
            INSERT INTO conversation_turn (id, conversation_id, created_at, request_id, status, surface, workspace_id,
                                           run_id, account_id)
            VALUES ('00000000-0000-0000-0000-0000000c0001', '00000000-0000-0000-0000-00000000a001', now(), 'ada-1',
                    'DONE', 'APP', 1, '00000000-0000-0000-0000-0000000a0001', 11),
                   ('00000000-0000-0000-0000-0000000c0002', '00000000-0000-0000-0000-00000000a001', now(), 'ada-2',
                    'QUEUED', 'APP', 1, '00000000-0000-0000-0000-0000000a0002', 11),
                   ('00000000-0000-0000-0000-0000000d0001', '00000000-0000-0000-0000-00000000b001', now(), 'bo-1',
                    'DONE', 'APP', 1, '00000000-0000-0000-0000-0000000b0001', 12);
            INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text, workspace_id,
                                            account_id, turn_id)
            VALUES ('00000000-0000-0000-0000-0000000e0001', '00000000-0000-0000-0000-00000000a001', now(), 'USER', 1,
                    'APP', 'Ada, first', 1, 11, '00000000-0000-0000-0000-0000000c0001'),
                   ('00000000-0000-0000-0000-0000000e0002', '00000000-0000-0000-0000-00000000a001', now(), 'USER', 2,
                    'APP', 'Ada, queued', 1, 11, '00000000-0000-0000-0000-0000000c0002'),
                   ('00000000-0000-0000-0000-0000000f0001', '00000000-0000-0000-0000-00000000b001', now(), 'USER', 1,
                    'APP', 'Bo, first', 1, 12, '00000000-0000-0000-0000-0000000d0001');
            COMMIT;
            CREATE POLICY conversation_owner ON conversation TO hephaestus_app
                USING (account_id = public.current_owner_account())
                WITH CHECK (account_id = public.current_owner_account());
            CREATE POLICY conversation_entry_owner ON conversation_entry TO hephaestus_app
                USING (account_id = public.current_owner_account())
                WITH CHECK (account_id = public.current_owner_account());
            CREATE POLICY conversation_turn_owner ON conversation_turn TO hephaestus_app
                USING (account_id = public.current_owner_account())
                WITH CHECK (account_id = public.current_owner_account());
            ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
            ALTER TABLE conversation_entry ENABLE ROW LEVEL SECURITY;
            ALTER TABLE conversation_turn ENABLE ROW LEVEL SECURITY;
            """;

    private static final TestDatabase SEEDED = seed();

    private static TestDatabase seed() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_conversation_run_rls");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_conversation_run_rls");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            RowSweepAdmissionDatabaseFixture.installKey(connection);
            jdbc(connection).execute(SEED);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed the conversation run database", e);
        }
        return database;
    }

    @Test
    void shouldRefuseARunThatMovesToAnotherOwnerAlthoughNoOwnerIsBound() throws SQLException {
        var database = copy("run_rls_owner");

        assertThatThrownBy(() -> asApplication(
                        database, null, "UPDATE agent_run SET account_id = 12 WHERE id = '" + ADA_RUN + "'"))
                .as("the foreign key reads Ada's turn although the transaction cannot")
                .hasStackTraceContaining("sfk_conversation_turn_run");
    }

    /**
     * A claim, and a move of the ledger position to another entry, of a run whose turn the transaction cannot see. The
     * entry exists, so the foreign key holds, and only the check can refuse the move.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE agent_run SET subject_seq = 2 WHERE id = '" + ADA_RUN + "'",
                "UPDATE agent_run SET state = 'LEASED', lease_epoch = 1, started_at = now(),"
                        + " leased_until = now() + interval '1 minute' WHERE id = '" + ADA_QUEUED_RUN + "'"
            })
    void shouldFailRatherThanPassWhenTheCheckCannotSeeTheTurn(String change) throws SQLException {
        var database = copy("run_rls_blind");

        assertThatThrownBy(() -> asApplication(database, null, change))
                .as("no owner bound")
                .hasStackTraceContaining("has no turn that this transaction can see");
        assertThatThrownBy(() -> asApplication(database, 12L, change))
                .as("another owner bound")
                .hasStackTraceContaining("has no turn that this transaction can see");
    }

    @Test
    void shouldCheckTheOwnersRunsWhenTheOwnerIsBound() throws SQLException {
        var database = copy("run_rls_bound");

        assertThatThrownBy(() -> asApplication(
                        database, 11L, "UPDATE agent_run SET subject_seq = 2 WHERE id = '" + ADA_RUN + "'"))
                .as("a run that names the question of another turn")
                .hasStackTraceContaining(
                        "the entry at a conversation run's position is the question or digest of its turn");
        assertThatThrownBy(() -> asApplication(
                        database, 11L, "UPDATE agent_run SET subject_seq = 9 WHERE id = '" + ADA_RUN + "'"))
                .as("a run that names no entry")
                .hasStackTraceContaining("sfk_agent_run_stimulus");
        assertThatThrownBy(() -> asApplication(
                        database, 11L, "UPDATE conversation_entry SET seq = 9 WHERE id = '" + ADA_QUESTION + "'"))
                .as("a change on the entry side only")
                .hasStackTraceContaining("sfk_agent_run_stimulus");
        assertThatThrownBy(() -> asApplication(
                        database, 11L, "DELETE FROM conversation_entry WHERE id = '" + ADA_QUESTION + "'"))
                .hasStackTraceContaining("sfk_agent_run_stimulus");
        assertThatThrownBy(() -> asApplication(
                        database, 11L, "DELETE FROM conversation_entry WHERE id = '" + ADA_QUESTION + "'", """
                        INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text,
                                                        workspace_id, account_id)
                        VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000a001', now(), 'ANSWER', 2, 'APP',
                                'Another entry at the position of the question', 1, 11)
                        """))
                .as("another entry that takes the question's position satisfies the foreign key, not the check")
                .hasStackTraceContaining(
                        "the entry at a conversation run's position is the question or digest of its turn");
        assertThatThrownBy(() -> asApplication(database, 11L, """
                        INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt,
                                               lease_epoch, account_id, conversation_id, subject_seq)
                        VALUES (gen_random_uuid(), 1, 'TURN', 'INTERACTIVE',
                                'conversation:00000000-0000-0000-0000-00000000a001', 'QUEUED', 0, 0, 11,
                                '00000000-0000-0000-0000-00000000a001', 2)
                        """))
                .as("a run queued without its turn, at the position of an existing question")
                .hasStackTraceContaining("has no turn that this transaction can see");
        assertThatThrownBy(() -> asApplication(
                        database,
                        11L,
                        "UPDATE agent_run SET state = 'LEASED', lease_epoch = 1, started_at = now(),"
                                + " leased_until = now() + interval '1 minute' WHERE id = '" + ADA_QUEUED_RUN + "'"))
                .as("a claim that does not start the turn")
                .hasStackTraceContaining("a turn that is QUEUED needs a run that agrees");
    }

    /**
     * A question moves to a conversation of its owner in another workspace and leaves its turn in one update. Its run
     * still names the question's old position, in the old workspace, so the commit fails.
     */
    @Test
    void shouldRefuseAQuestionThatMovesToAnotherWorkspaceWithoutItsRun() throws SQLException {
        var database = copy("run_rls_workspace");

        assertThatThrownBy(() -> asApplication(database, 11L, """
                        UPDATE conversation_entry
                        SET workspace_id = 2, conversation_id = '00000000-0000-0000-0000-00000000a002', turn_id = NULL
                        WHERE id = '%s'
                        """.formatted(ADA_QUESTION)))
                .hasStackTraceContaining("sfk_agent_run_stimulus");
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(connection)
                            .queryForObject(
                                    "SELECT workspace_id || ':' || conversation_id FROM conversation_entry WHERE id = ?",
                                    String.class,
                                    UUID.fromString(ADA_QUESTION)))
                    .as("the question stays at its run's position")
                    .isEqualTo("1:00000000-0000-0000-0000-00000000a001");
        }
    }

    @Test
    void shouldCommitASubmissionAndAClaimOfTheBoundOwner() throws SQLException {
        var database = copy("run_rls_valid");

        asApplication(
                database,
                11L,
                "UPDATE agent_run SET state = 'LEASED', lease_epoch = 1, started_at = now(),"
                        + " leased_until = now() + interval '1 minute' WHERE id = '" + ADA_QUEUED_RUN + "'",
                "UPDATE conversation_turn SET status = 'RUNNING', attempt = 1, started_at = now()" + " WHERE run_id = '"
                        + ADA_QUEUED_RUN + "'");
        asApplication(database, 11L, """
                INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                                       account_id, conversation_id, subject_seq)
                VALUES ('00000000-0000-0000-0000-0000000a0003', 1, 'TURN', 'INTERACTIVE',
                        'conversation:00000000-0000-0000-0000-00000000a001', 'QUEUED', 0, 0, 11,
                        '00000000-0000-0000-0000-00000000a001', 3)
                """, """
                INSERT INTO conversation_turn (id, conversation_id, created_at, request_id, status, surface,
                                               workspace_id, run_id, account_id)
                VALUES ('00000000-0000-0000-0000-0000000c0003', '00000000-0000-0000-0000-00000000a001', now(),
                        'ada-3', 'QUEUED', 'APP', 1, '00000000-0000-0000-0000-0000000a0003', 11)
                """, """
                INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text,
                                                workspace_id, account_id, turn_id)
                VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000a001', now(), 'USER', 3, 'APP',
                        'Ada, third', 1, 11, '00000000-0000-0000-0000-0000000c0003')
                """);

        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(connection)
                            .queryForList(
                                    "SELECT state FROM agent_run WHERE account_id = 11 ORDER BY id", String.class))
                    .containsExactly("ENDED", "LEASED", "QUEUED");
        }
    }

    @Test
    void shouldLetTheAbsorptionMoveTheRunsOfEveryOwnerAlthoughNoOwnerIsBound() throws Exception {
        var database = copy("run_rls_absorb");

        byte[] key;
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            key = RowSweepAdmissionDatabaseFixture.installKey(connection);
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(connection, key, RowSweepRule.ACCOUNT_ABSORB, 12, 11L);
            jdbc(connection)
                    .queryForObject("SELECT public.person_row_sweep('ACCOUNT_ABSORB', 12, 11, NULL)", Integer.class);
            connection.commit();
        }

        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(connection)
                            .queryForObject(
                                    "SELECT account_id || ':' || conversation_id || ':' || subject_seq FROM agent_run"
                                            + " WHERE id = ?",
                                    String.class,
                                    UUID.fromString(BO_RUN)))
                    .as("the check that the sweep's writes queue runs as the sweep's owner, which sees every row")
                    .isEqualTo("11:00000000-0000-0000-0000-00000000a001:3");
        }
    }

    /** Runs the statements in one transaction as the application role, under {@code owner} when it is bound. */
    private static void asApplication(TestDatabase database, @Nullable Long owner, String... statements)
            throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            connection.setAutoCommit(false);
            var app = jdbc(connection);
            assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("hephaestus_app");
            if (owner != null)
                app.queryForObject(
                        "SELECT pg_catalog.set_config('hephaestus.owner_account', ?::text, true)", String.class, owner);
            for (String statement : List.of(statements)) app.execute(statement);
            connection.commit();
        }
    }

    private static TestDatabase copy(String name) {
        return TestDatabaseCleanup.copyDatabase(
                name + "_" + UUID.randomUUID().toString().replace("-", ""), SEEDED);
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
