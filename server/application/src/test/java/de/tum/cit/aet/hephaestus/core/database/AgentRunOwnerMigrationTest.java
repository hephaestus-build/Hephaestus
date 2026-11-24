package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A conversation run stores its owner and the ledger position of the question or digest entry that it answers: the
 * conversation and the entry's seq. The migration fills them from each run's turn, and the database keeps them equal to
 * the turn's when each transaction commits.
 */
@Tag("database")
class AgentRunOwnerMigrationTest {
    private static final String CHANGELOG = "-feat-agent-run-stores-owner.xml";
    private static final long WORKSPACE = 979500;
    private static final long OWNER = 979501;
    private static final long OTHER = 979502;
    private static final long THIRD = 979503;
    private static final String CONVERSATION = "00000000-0000-0000-0000-000000979510";
    private static final String QUESTION_RUN = "00000000-0000-0000-0000-000000979511";
    private static final String DIGEST_RUN = "00000000-0000-0000-0000-000000979512";
    private static final String QUEUED_RUN = "00000000-0000-0000-0000-000000979513";
    private static final String REVIEW_RUN = "00000000-0000-0000-0000-000000979514";
    private static final String QUEUED_TURN = "00000000-0000-0000-0000-000000979523";
    private static final String QUEUED_QUESTION = "00000000-0000-0000-0000-000000979533";

    /** The entry check's condition on the runs: the workspace, the conversation and the seq of the entry. */
    private static final String ENTRY_RUNS = "(r.workspace_id, r.conversation_id, r.subject_seq) = (%s, %s, %s)";

    /** The number of threads of one owner whose runs share one seq in the plan case. */
    private static final int THREADS = 50;

    /**
     * An answered question, a digest and a queued question of one conversation, at positions 2, 3 and 4 after an earlier
     * answer, and a queued review run. A third account has a conversation of its own.
     */
    private static final String ROWS = """
            BEGIN;
            INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
            VALUES (%1$d, 'run-owner', 'Run owner', 'run-owner', 'ORG', 'ACTIVE');
            INSERT INTO account(id, display_name) VALUES (%2$d, 'Owner'), (%3$d, 'Other'), (%11$d, 'Third');
            INSERT INTO conversation(id, workspace_id, account_id, created_at, last_activity_at)
            VALUES ('%4$s', %1$d, %2$d, now(), now()),
                   ('00000000-0000-0000-0000-000000979519', %1$d, %11$d, now(), now());
            INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                available_at, start_within_seconds, stop_reason, created_at, ended_at)
            VALUES ('%5$s', %1$d, 'TURN', 'INTERACTIVE', 'conversation:%4$s', 'ENDED', 0, 1, now(), 180, 'COMPLETED',
                    now(), now()),
                   ('%6$s', %1$d, 'DIGEST', 'BACKGROUND', 'conversation:%4$s', 'ENDED', 0, 1, now(), NULL, 'COMPLETED',
                    now(), now()),
                   ('%7$s', %1$d, 'TURN', 'INTERACTIVE', 'conversation:%4$s', 'QUEUED', 0, 0, now(), 180, NULL, now(),
                    NULL),
                   ('%8$s', %1$d, 'REVIEW', 'BATCH', 'review:run-owner', 'QUEUED', 0, 0, now(), NULL, NULL, now(),
                    NULL);
            INSERT INTO conversation_turn(id, workspace_id, conversation_id, account_id, request_id, status, surface,
                run_id, created_at)
            VALUES ('00000000-0000-0000-0000-000000979521', %1$d, '%4$s', %2$d, 'run-owner-question', 'DONE', 'APP',
                    '%5$s', now()),
                   ('00000000-0000-0000-0000-000000979522', %1$d, '%4$s', %2$d, 'run-owner-digest', 'DONE', 'SYSTEM',
                    '%6$s', now()),
                   ('%9$s', %1$d, '%4$s', %2$d, 'run-owner-queued', 'QUEUED', 'APP', '%7$s', now());
            INSERT INTO conversation_entry(id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                turn_id, payload, created_at)
            VALUES (gen_random_uuid(), %1$d, '%4$s', %2$d, 1, 'ANSWER', 'APP', 'An earlier answer.', NULL, NULL, now()),
                   (gen_random_uuid(), %1$d, '%4$s', %2$d, 2, 'USER', 'APP', 'A question.',
                    '00000000-0000-0000-0000-000000979521', NULL, now()),
                   (gen_random_uuid(), %1$d, '%4$s', %2$d, 3, 'DIGEST', 'SYSTEM', 'Weekly recap',
                    '00000000-0000-0000-0000-000000979522',
                    '{"window":{"weekStart":"2026-10-05","zone":"UTC"},"feedbackIds":[],"changeKeys":[]}', now()),
                   ('%10$s', %1$d, '%4$s', %2$d, 4, 'USER', 'APP', 'A queued question.', '%9$s', NULL, now()),
                   (gen_random_uuid(), %1$d, '00000000-0000-0000-0000-000000979519', %11$d, 1, 'USER', 'APP',
                    'A question of another account.', NULL, NULL, now());
            COMMIT;
            """.formatted(
                    WORKSPACE,
                    OWNER,
                    OTHER,
                    CONVERSATION,
                    QUESTION_RUN,
                    DIGEST_RUN,
                    QUEUED_RUN,
                    REVIEW_RUN,
                    QUEUED_TURN,
                    QUEUED_QUESTION,
                    THIRD);

    @Test
    void shouldGiveEachConversationRunItsOwnerAndPositionKeepThemHonestReplayAndRollBack() throws Exception {
        var owner = database("agent_run_owner_");
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, ROWS);

        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));

        assertThat(owners(owner))
                .as("each conversation run, also an ended one, takes its turn's owner and its stimulus's position")
                .containsExactly(
                        QUESTION_RUN + " " + OWNER + " " + CONVERSATION + " 2",
                        DIGEST_RUN + " " + OWNER + " " + CONVERSATION + " 3",
                        QUEUED_RUN + " " + OWNER + " " + CONVERSATION + " 4",
                        REVIEW_RUN + " - - -");
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT pg_get_indexdef(i) FROM unnest(ARRAY['public.idx_agent_run_account'::regclass,
                            'public.idx_agent_run_stimulus'::regclass]) AS i
                        """))
                .as("the entity's index declarations cannot carry the predicates, which the drift check ignores")
                .containsExactly(
                        "CREATE INDEX idx_agent_run_account ON public.agent_run USING btree (account_id)"
                                + " WHERE (account_id IS NOT NULL)",
                        "CREATE INDEX idx_agent_run_stimulus ON public.agent_run USING btree (workspace_id,"
                                + " conversation_id, subject_seq) WHERE (conversation_id IS NOT NULL)");
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conrelid = 'public.conversation_entry'::regclass AND contype = 'u' ORDER BY conname
                        """))
                .as("one key holds an entry's position, with the conversation first for the reads in seq order")
                .containsExactly(
                        "uk_conversation_entry_conversation_slack_ts UNIQUE (conversation_id, slack_ts)",
                        "uk_conversation_entry_position UNIQUE (conversation_id, seq, workspace_id)",
                        "uk_conversation_entry_tenant UNIQUE (id, workspace_id)");
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'uk_conversation_turn_run'
                        """))
                .as("one index keeps one turn per run and serves the turn's foreign key with the run's owner")
                .containsExactly("UNIQUE (run_id, workspace_id, account_id)");

        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch)
                        VALUES (gen_random_uuid(), %d, 'TURN', 'INTERACTIVE', 'conversation:x', 'QUEUED', 0, 0)
                        """.formatted(WORKSPACE)))
                .as("a conversation run without an owner")
                .hasStackTraceContaining("ck_agent_run_owner");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                            account_id, conversation_id, subject_seq)
                        VALUES (gen_random_uuid(), %d, 'REVIEW', 'BATCH', 'review:x', 'QUEUED', 0, 0, %d, '%s', 1)
                        """.formatted(WORKSPACE, OWNER, CONVERSATION)))
                .as("a review run with an owner and a position")
                .hasStackTraceContaining("ck_agent_run_owner");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                            account_id)
                        VALUES (gen_random_uuid(), %d, 'TURN', 'INTERACTIVE', 'conversation:x', 'QUEUED', 0, 0, %d)
                        """.formatted(WORKSPACE, OWNER)))
                .as("a conversation run without a position")
                .hasStackTraceContaining("ck_agent_run_owner");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                            account_id, subject_seq)
                        VALUES (gen_random_uuid(), %d, 'TURN', 'INTERACTIVE', 'conversation:x', 'QUEUED', 0, 0, %d, 2)
                        """.formatted(WORKSPACE, OWNER)))
                .as("a conversation run with a seq but without its conversation")
                .hasStackTraceContaining("ck_agent_run_owner");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        owner, """
                        INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                            account_id, conversation_id, subject_seq)
                        VALUES (gen_random_uuid(), %d, 'TURN', 'INTERACTIVE', 'conversation:%s', 'QUEUED', 0, 0, %d,
                            '%s', 4)
                        """.formatted(WORKSPACE, CONVERSATION, OWNER, CONVERSATION)))
                .as("a conversation run queued without its turn, at the position of an existing question")
                .hasStackTraceContaining("has no turn that this transaction can see");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        owner, "UPDATE agent_run SET account_id = %d WHERE id = '%s'".formatted(OTHER, QUEUED_RUN)))
                .as("a run that moves to another owner without its turn")
                .hasStackTraceContaining("sfk_conversation_turn_run");
        for (String gone : List.of(
                "UPDATE agent_run SET subject_seq = 9 WHERE id = '%s'".formatted(QUEUED_RUN),
                "UPDATE conversation_entry SET seq = 9 WHERE id = '%s'".formatted(QUEUED_QUESTION),
                "DELETE FROM conversation_entry WHERE id = '%s'".formatted(QUEUED_QUESTION))) {
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, gone))
                    .as("a run whose position names no entry when the transaction commits: %s", gone)
                    .hasStackTraceContaining("sfk_agent_run_stimulus");
        }
        for (String other : List.of(
                "UPDATE agent_run SET subject_seq = 2 WHERE id = '%s'".formatted(QUEUED_RUN),
                "UPDATE conversation_entry SET kind = 'ANSWER' WHERE id = '%s'".formatted(QUEUED_QUESTION),
                """
                BEGIN;
                UPDATE conversation_entry SET seq = 9 WHERE id = '%1$s';
                UPDATE conversation_entry SET seq = 4 WHERE conversation_id = '%2$s' AND seq = 1;
                COMMIT;
                """.formatted(QUEUED_QUESTION, CONVERSATION),
                """
                BEGIN;
                UPDATE conversation_entry SET turn_id = NULL WHERE id = '%1$s';
                UPDATE conversation_entry SET turn_id = '%2$s', seq = 4
                WHERE conversation_id = '00000000-0000-0000-0000-000000979519';
                COMMIT;
                """.formatted(QUEUED_QUESTION, QUEUED_TURN))) {
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, other))
                    .as("a run whose position names an entry that is not its turn's question: %s", other)
                    .hasStackTraceContaining(
                            "the entry at a conversation run's position is the question or digest of its turn");
        }
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        UPDATE agent_run SET conversation_id = '00000000-0000-0000-0000-000000979519', subject_seq = 1
                        WHERE id = '%s'
                        """.formatted(QUEUED_RUN)))
                .as("a run that names an entry of another conversation than its turn's")
                .hasStackTraceContaining("a conversation run answers an entry of its turn's conversation");
        for (String change : List.of(
                "UPDATE agent_run SET stop_reason = 'FAILED' WHERE id = '%s'".formatted(QUESTION_RUN),
                "UPDATE agent_run SET account_id = account_id WHERE id = '%s'".formatted(QUESTION_RUN),
                "UPDATE agent_run SET conversation_id = conversation_id WHERE id = '%s'".formatted(QUESTION_RUN),
                "UPDATE agent_run SET account_id = %d, stop_reason = 'FAILED' WHERE id = '%s'"
                        .formatted(OTHER, QUESTION_RUN))) {
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, change))
                    .as("an ended run changes nothing but its owner and position: %s", change)
                    .hasStackTraceContaining("an ended run is final");
        }
        PracticeMigrationFixture.execute(owner, """
                BEGIN;
                UPDATE conversation SET account_id = %1$d WHERE id = '%2$s';
                UPDATE agent_run SET account_id = %1$d WHERE account_id = %3$d;
                COMMIT;
                """.formatted(OTHER, CONVERSATION, OWNER));
        assertThat(owners(owner))
                .as("the runs, also the ended ones, move to another owner with their turns")
                .containsExactly(
                        QUESTION_RUN + " " + OTHER + " " + CONVERSATION + " 2",
                        DIGEST_RUN + " " + OTHER + " " + CONVERSATION + " 3",
                        QUEUED_RUN + " " + OTHER + " " + CONVERSATION + " 4",
                        REVIEW_RUN + " - - -");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        owner,
                        "UPDATE conversation SET account_id = %d WHERE id = '%s'".formatted(OWNER, CONVERSATION)))
                .as("a turn that moves to another owner without its run")
                .hasStackTraceContaining("sfk_conversation_turn_run");

        // A run that committed the change but stopped before recording it marks the change run when it starts again.
        PracticeMigrationFixture.execute(
                owner, "DELETE FROM databasechangelog WHERE filename LIKE '%" + CHANGELOG + "'");
        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT id || ' ' || exectype FROM databasechangelog WHERE filename LIKE '%%%s' ORDER BY id
                        """.formatted(CHANGELOG)))
                .as("the function and trigger changes run again, as they are idempotent")
                .containsExactly("1795541881079-1 MARK_RAN", "1795541881079-2 EXECUTED", "1795541881079-3 EXECUTED");

        liquibase(owner, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'agent_run' AND column_name IN ('account_id', 'conversation_id', 'subject_seq')
                """)).containsExactly("0");
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname IN ('uk_conversation_entry_position', 'uk_conversation_entry_conversation_seq')
                """))
                .as("the rollback puts back the key of the entries without the workspace")
                .containsExactly("uk_conversation_entry_conversation_seq UNIQUE (conversation_id, seq)");
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'uk_conversation_turn_run'
                """))
                .as("the rollback puts back the key of the turns without the owner")
                .containsExactly("UNIQUE (run_id, workspace_id)");
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT count(*) FROM pg_proc WHERE proname IN ('enforce_conversation_turn_run', 'person_row_sweep',
                    'guard_agent_run') AND prosrc LIKE '%subject_seq%'
                """))
                .as("the functions read no column that the rollback dropped")
                .containsExactly("0");
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'sfk_conversation_turn_run'
                """))
                .as("the turn names its run as before")
                .containsExactly("FOREIGN KEY (run_id, workspace_id) REFERENCES agent_run(id, workspace_id)");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        owner, "UPDATE agent_run SET stop_reason = 'FAILED' WHERE id = '%s'".formatted(QUESTION_RUN)))
                .hasStackTraceContaining("an ended run is final");
        assertThat(PracticeMigrationFixture.column(
                        owner, "SELECT count(*) FROM agent_run WHERE workspace_id = %d".formatted(WORKSPACE)))
                .as("the rollback keeps every run")
                .containsExactly("4");

        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));
        assertThat(owners(owner))
                .as("the migration runs again after its rollback")
                .containsExactly(
                        QUESTION_RUN + " " + OTHER + " " + CONVERSATION + " 2",
                        DIGEST_RUN + " " + OTHER + " " + CONVERSATION + " 3",
                        QUEUED_RUN + " " + OTHER + " " + CONVERSATION + " 4",
                        REVIEW_RUN + " - - -");

        PracticeMigrationFixture.execute(owner, "DELETE FROM conversation WHERE id = '%s'".formatted(CONVERSATION));
        assertThat(owners(owner))
                .as("the erasure of a conversation deletes the runs of its turns, also the ended ones")
                .containsExactly(REVIEW_RUN + " - - -");
    }

    /**
     * The change set committed, and then one of its objects lost its definition before the change set was recorded: an
     * index of that name on another table, a hash index, the position index without the workspace, the guard of the
     * release before, a guard that runs as its owner, a guard whose trigger does not fire, a stimulus key that matches
     * in full and so refuses each review run, or a turn key on the run without its owner. The replay does not mark the change set run.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "otherTable",
                "hashIndex",
                "positionIndexWithoutWorkspace",
                "priorGuard",
                "definerGuard",
                "disabledTrigger",
                "matchFullStimulusKey",
                "turnRunKeyWithoutOwner"
            })
    void shouldRunAgainWhenAnObjectOfTheChangeSetIsNotItsDefinition(String drift) throws Exception {
        var owner = database("agent_run_owner_replay_");
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        String priorGuard = Objects.requireNonNull(PracticeMigrationFixture.column(
                        owner, "SELECT pg_get_functiondef('public.guard_agent_run()'::regprocedure)")
                .getFirst());
        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));
        String index = "DROP INDEX public.idx_agent_run_account; CREATE INDEX idx_agent_run_account ON public.";
        PracticeMigrationFixture.execute(
                owner,
                switch (drift) {
                    case "otherTable" -> index + "conversation_entry (seq)";
                    case "hashIndex" -> index + "agent_run USING hash (account_id) WHERE account_id IS NOT NULL";
                    case "positionIndexWithoutWorkspace" ->
                        "DROP INDEX public.idx_agent_run_stimulus; CREATE INDEX idx_agent_run_stimulus ON"
                                + " public.agent_run (conversation_id, subject_seq) WHERE conversation_id IS NOT NULL";
                    case "definerGuard" -> "ALTER FUNCTION public.guard_agent_run() SECURITY DEFINER";
                    case "disabledTrigger" -> "ALTER TABLE public.agent_run DISABLE TRIGGER agent_run_transition";
                    case "matchFullStimulusKey" ->
                        "ALTER TABLE public.agent_run DROP CONSTRAINT sfk_agent_run_stimulus; ALTER TABLE"
                                + " public.agent_run ADD CONSTRAINT sfk_agent_run_stimulus FOREIGN KEY (workspace_id,"
                                + " conversation_id, subject_seq) REFERENCES public.conversation_entry (workspace_id,"
                                + " conversation_id, seq) MATCH FULL DEFERRABLE INITIALLY DEFERRED";
                    case "turnRunKeyWithoutOwner" ->
                        "ALTER TABLE public.conversation_turn DROP CONSTRAINT uk_conversation_turn_run; ALTER TABLE"
                                + " public.conversation_turn ADD CONSTRAINT uk_conversation_turn_run UNIQUE (run_id,"
                                + " workspace_id)";
                    default -> priorGuard;
                });
        PracticeMigrationFixture.execute(
                owner, "DELETE FROM databasechangelog WHERE filename LIKE '%" + CHANGELOG + "'");

        assertThatThrownBy(() -> liquibase(
                        owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG)))
                .as("the change set runs and fails loudly instead of being marked run: %s", drift)
                .hasStackTraceContaining("already exists");
        assertThat(PracticeMigrationFixture.column(
                        owner,
                        "SELECT count(*) FROM databasechangelog WHERE filename LIKE '%%%s'".formatted(CHANGELOG)))
                .containsExactly("0");
    }

    /**
     * When a question or digest entry changes or goes, the entry check reads the runs at the entry's exact position: its
     * workspace, its conversation and its seq. Each thread numbers its entries from 1, so the threads of one person put
     * their runs at the same seq. The check still reads only the one run at the position, not each run of the person at
     * that seq, so an erasure or a purge of many threads does not do work in proportion to their square.
     */
    @Test
    void shouldFindTheRunOfAnEntryAtItsExactPositionAlsoWhenManyThreadsShareItsSeq() throws Exception {
        var owner = database("agent_run_owner_plan_");
        PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT prosrc FROM pg_proc WHERE oid = 'public.enforce_conversation_turn_run()'::regprocedure
                        """).getFirst())
                .as("the statement that this case plans is the entry check of the function")
                .contains(ENTRY_RUNS.formatted("OLD.workspace_id", "OLD.conversation_id", "OLD.seq"));
        PracticeMigrationFixture.execute(owner, """
                BEGIN;
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (%1$d, 'run-owner', 'Run owner', 'run-owner', 'ORG', 'ACTIVE');
                INSERT INTO account(id, display_name) VALUES (%2$d, 'Owner');
                INSERT INTO conversation(id, workspace_id, account_id, created_at, last_activity_at)
                VALUES ('%3$s', %1$d, %2$d, now(), now());
                CREATE TEMP TABLE thread ON COMMIT DROP AS
                SELECT n, ('00000000-0000-0000-0001-' || lpad(n::text, 12, '0'))::uuid AS conversation,
                       ('00000000-0000-0000-0002-' || lpad(n::text, 12, '0'))::uuid AS run,
                       ('00000000-0000-0000-0003-' || lpad(n::text, 12, '0'))::uuid AS turn,
                       ('00000000-0000-0000-0004-' || lpad(n::text, 12, '0'))::uuid AS parent
                FROM generate_series(1, %4$d) n;
                INSERT INTO conversation_entry(id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                    created_at)
                SELECT parent, %1$d, '%3$s', %2$d, n, 'ANSWER', 'APP', 'An answer that opens a thread.', now()
                FROM thread;
                INSERT INTO conversation(id, workspace_id, account_id, parent_conversation_id, parent_entry_id,
                    created_at, last_activity_at)
                SELECT conversation, %1$d, %2$d, '%3$s', parent, now(), now() FROM thread;
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    available_at, stop_reason, created_at, ended_at, account_id, conversation_id, subject_seq)
                SELECT run, %1$d, 'TURN', 'INTERACTIVE', 'conversation:' || conversation, 'ENDED', 0, 1, now(),
                    'COMPLETED', now(), now(), %2$d, conversation, 1
                FROM thread;
                INSERT INTO conversation_turn(id, workspace_id, conversation_id, account_id, request_id, status,
                    surface, run_id, created_at)
                SELECT turn, %1$d, conversation, %2$d, 'thread-' || n, 'DONE', 'APP', run, now() FROM thread;
                INSERT INTO conversation_entry(id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                    turn_id, created_at)
                SELECT gen_random_uuid(), %1$d, conversation, %2$d, 1, 'USER', 'APP', 'A question in a thread.', turn,
                    now()
                FROM thread;
                COMMIT;
                """.formatted(WORKSPACE, OWNER, CONVERSATION, THREADS));
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT count(*) FROM agent_run WHERE account_id = %d AND subject_seq = 1
                        """.formatted(OWNER)))
                .as("the threads of one owner put their runs at the same seq")
                .containsExactly(String.valueOf(THREADS));

        JsonNode plan;
        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var statement = connection.createStatement()) {
            // The function plans its statement once with parameters, so the case plans it the same way. The test
            // table has no statistics, so with sequential scans off the planner takes the index that serves it best.
            statement.execute("SET enable_seqscan = off");
            statement.execute("SET plan_cache_mode = force_generic_plan");
            statement.execute("PREPARE entry_runs(bigint, uuid, bigint) AS SELECT array_agg(r.id) FROM"
                    + " public.agent_run r WHERE " + ENTRY_RUNS.formatted("$1", "$2", "$3"));
            try (var result = statement.executeQuery(
                    "EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF, FORMAT JSON) EXECUTE entry_runs(%d, '%s', 1)"
                            .formatted(WORKSPACE, "00000000-0000-0000-0001-000000000007"))) {
                result.next();
                plan = JsonMapper.builder().build().readTree(result.getString(1));
            }
        }

        assertThat(indexScans(plan, new ArrayList<>()))
                .as("the index scans of the plan, with their conditions and the rows that they read: %s", plan)
                .containsExactly("idx_agent_run_stimulus: ((workspace_id = $1) AND (conversation_id = $2) AND"
                        + " (subject_seq = $3)): 1");
    }

    /** Each index scan of a plan, as {@code index: condition: rows}, with the rows that the scan returned. */
    private static List<String> indexScans(JsonNode node, List<String> found) {
        if (node.has("Index Name"))
            found.add(node.path("Index Name").asString() + ": "
                    + node.path("Index Cond").asString() + ": "
                    + Math.round(node.path("Actual Rows").asDouble()));
        for (JsonNode child : node.isArray() ? node : node.path("Plans")) indexScans(child, found);
        if (node.has("Plan")) indexScans(node.path("Plan"), found);
        return found;
    }

    /** The queued question is gone, or it is a question of another conversation. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM conversation_entry WHERE id = '" + QUEUED_QUESTION + "'",
                "UPDATE conversation_entry SET turn_id = NULL WHERE id = '" + QUEUED_QUESTION + "';"
                        + " UPDATE conversation_entry SET turn_id = '" + QUEUED_TURN + "'"
                        + " WHERE conversation_id = '00000000-0000-0000-0000-000000979519'"
            })
    void shouldStopWithoutAChangeWhenAConversationRunOfAnActiveAccountHasNoQuestion(String loss) throws Exception {
        var owner = database("agent_run_owner_missing_");
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, ROWS);
        PracticeMigrationFixture.execute(owner, loss);

        assertThatThrownBy(() -> liquibase(
                        owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG)))
                .hasStackTraceContaining("1 conversation runs have no turn with its question or digest entry");
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'agent_run' AND column_name IN ('account_id', 'conversation_id', 'subject_seq')
                """)).containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        owner,
                        "SELECT count(*) FROM databasechangelog WHERE filename LIKE '%%%s'".formatted(CHANGELOG)))
                .containsExactly("0");
    }

    /**
     * The previous release erased a person's questions before the person's turns, in separate commits. An erasure that
     * stopped between the two left the turns without their questions; the migration deletes them with their runs.
     */
    @Test
    void shouldDeleteTheTurnsThatAStoppedErasureLeftWithoutTheirQuestion() throws Exception {
        var owner = database("agent_run_owner_erasing_");
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, ROWS);
        PracticeMigrationFixture.execute(owner, """
                BEGIN;
                UPDATE account SET status = 'DELETING', deleted_at = now() WHERE id = %d;
                DELETE FROM conversation_entry WHERE account_id = %d AND kind IN ('USER', 'ANSWER', 'DIGEST');
                COMMIT;
                """.formatted(OWNER, OWNER));

        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));

        assertThat(owners(owner))
                .as("the erased person's runs went with their turns")
                .containsExactly(REVIEW_RUN + " - - -");
        assertThat(PracticeMigrationFixture.column(
                        owner, "SELECT count(*) FROM conversation_turn WHERE account_id = %d".formatted(OWNER)))
                .containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        owner, "SELECT count(*) FROM conversation WHERE account_id = %d".formatted(OWNER)))
                .as("the rest of the erasure is left to the erasure")
                .containsExactly("1");
    }

    /**
     * The conversion seed runs on each release an upgrade can start from: the 1.0 baseline (v1.0.0-rc.1), a release
     * before conversation rows recorded their account, a release before this change set, and one that already has it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"baseline", "beforeRowOwner", "before", "after"})
    void shouldGiveTheConversionSeedsRunItsOwnerAndPosition(String release) throws Exception {
        var owner = database("agent_run_owner_seed_");
        switch (release) {
            case "baseline" ->
                liquibase(
                        owner,
                        liquibase -> liquibase.update("1.0.0-baseline", new Contexts("prod"), new LabelExpression()));
            case "beforeRowOwner" ->
                PracticeMigrationFixture.upgradeBefore(owner, "prod", "-conversation-row-owner.xml");
            case "before" -> PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
            default -> PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        }
        PracticeMigrationFixture.execute(owner, seed("seed"));

        PostgreSQLTestContainer.migrateDatabase(owner, "prod");

        PracticeMigrationFixture.execute(owner, seed("expect"));
    }

    private static TestDatabase database(String prefix) {
        String name = prefix + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        return PostgreSQLTestContainer.asOwner(database);
    }

    private static String seed(String part) throws Exception {
        try (var input = Objects.requireNonNull(AgentRunOwnerMigrationTest.class.getResourceAsStream(
                "/db/cutover-seeds/agent-run-owner." + part + ".sql"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Each run of the workspace with its owner, conversation and seq, or {@code -} for none. */
    private static List<@Nullable String> owners(TestDatabase database) {
        return PracticeMigrationFixture.column(database, """
                SELECT id || ' ' || COALESCE(account_id::text, '-') || ' ' || COALESCE(conversation_id::text, '-')
                    || ' ' || COALESCE(subject_seq::text, '-')
                FROM agent_run WHERE workspace_id = %d ORDER BY id
                """.formatted(WORKSPACE));
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
}
