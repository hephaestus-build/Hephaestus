package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The conversation facts that a caller reads before it binds one owner, or across every owner. Account 979401 has two
 * queued questions in one conversation. Account 979402 has a running turn and a queued one in its conversation of the
 * first workspace, and a queued question in the second workspace. A queued and a leased run of kind {@code TURN} have no
 * turn, as a run that {@code AgentRuns.enqueue} commits alone has none.
 */
@Tag("database")
class ConversationOwnerLookupDatabaseTest {
    private static final long SECOND = 979402;

    private static final TestDatabase SEEDED = seeded();

    private static TestDatabase seeded() {
        String name =
                "conversation_owner_lookup_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979400, 'owner-lookup', 'Owner lookup', 'owner-lookup', 'ORG', 'ACTIVE'),
                       (979409, 'owner-lookup-2', 'Owner lookup 2', 'owner-lookup-2', 'ORG', 'ACTIVE');
                INSERT INTO account(id, display_name) VALUES (979401, 'First'), (979402, 'Second');
                INSERT INTO conversation(id, workspace_id, account_id, slack_team_id, slack_user_id, slack_channel_id,
                    created_at, last_activity_at)
                VALUES ('00000000-0000-0000-0000-000000979411', 979400, 979401, 'T1', 'U1', 'D1', now(), now()),
                       ('00000000-0000-0000-0000-000000979412', 979400, 979402, 'T1', 'U2', 'D2', now(), now()),
                       ('00000000-0000-0000-0000-000000979413', 979409, 979402, NULL, NULL, NULL, now(), now());
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    leased_until, started_at, available_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000979421', 979400, 'TURN', 'INTERACTIVE',
                        'conversation:00000000-0000-0000-0000-000000979411', 'QUEUED', 0, 0, NULL, NULL, now(), now()),
                       ('00000000-0000-0000-0000-000000979422', 979400, 'TURN', 'INTERACTIVE',
                        'conversation:00000000-0000-0000-0000-000000979411', 'QUEUED', 0, 0, NULL, NULL, now(), now()),
                       ('00000000-0000-0000-0000-000000979423', 979400, 'TURN', 'INTERACTIVE',
                        'conversation:00000000-0000-0000-0000-000000979412', 'LEASED', 0, 1,
                        now() + interval '1 hour', now(), now(), now()),
                       ('00000000-0000-0000-0000-000000979424', 979400, 'TURN', 'INTERACTIVE',
                        'conversation:00000000-0000-0000-0000-000000979412', 'QUEUED', 0, 0, NULL, NULL, now(), now()),
                       ('00000000-0000-0000-0000-000000979425', 979409, 'TURN', 'INTERACTIVE',
                        'conversation:00000000-0000-0000-0000-000000979413', 'QUEUED', 0, 0, NULL, NULL, now(), now()),
                       ('00000000-0000-0000-0000-000000979426', 979400, 'TURN', 'INTERACTIVE', 'unpaired:1', 'QUEUED',
                        0, 0, NULL, NULL, now(), now()),
                       ('00000000-0000-0000-0000-000000979427', 979400, 'TURN', 'INTERACTIVE', 'unpaired:2', 'LEASED',
                        0, 1, now() + interval '1 hour', now(), now(), now());
                INSERT INTO conversation_turn(id, workspace_id, conversation_id, account_id, request_id, status,
                    surface, attempt, run_id, started_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000979431', 979400, '00000000-0000-0000-0000-000000979411',
                        979401, 'first-1', 'QUEUED', 'APP', 0, '00000000-0000-0000-0000-000000979421', NULL,
                        now() - interval '2 minutes'),
                       ('00000000-0000-0000-0000-000000979432', 979400, '00000000-0000-0000-0000-000000979411',
                        979401, 'first-2', 'QUEUED', 'APP', 0, '00000000-0000-0000-0000-000000979422', NULL,
                        now() - interval '1 minute'),
                       ('00000000-0000-0000-0000-000000979433', 979400, '00000000-0000-0000-0000-000000979412',
                        979402, 'second-0', 'RUNNING', 'APP', 1, '00000000-0000-0000-0000-000000979423', now(),
                        now() - interval '3 minutes'),
                       ('00000000-0000-0000-0000-000000979434', 979400, '00000000-0000-0000-0000-000000979412',
                        979402, 'second-1', 'QUEUED', 'APP', 0, '00000000-0000-0000-0000-000000979424', NULL,
                        now() - interval '2 minutes'),
                       ('00000000-0000-0000-0000-000000979435', 979409, '00000000-0000-0000-0000-000000979413',
                        979402, 'second-2', 'QUEUED', 'SLACK', 0, '00000000-0000-0000-0000-000000979425', NULL, now());
                INSERT INTO conversation_entry(id, workspace_id, conversation_id, account_id, seq, kind, surface,
                    text, turn_id, created_at)
                VALUES ('00000000-0000-0000-0000-000000979441', 979400, '00000000-0000-0000-0000-000000979411',
                        979401, 1, 'USER', 'APP', 'First question.', '00000000-0000-0000-0000-000000979431', now()),
                       ('00000000-0000-0000-0000-000000979442', 979400, '00000000-0000-0000-0000-000000979411',
                        979401, 2, 'USER', 'APP', 'Second question.', '00000000-0000-0000-0000-000000979432', now()),
                       ('00000000-0000-0000-0000-000000979443', 979400, '00000000-0000-0000-0000-000000979412',
                        979402, 1, 'USER', 'APP', 'Running question.', '00000000-0000-0000-0000-000000979433', now()),
                       ('00000000-0000-0000-0000-000000979444', 979400, '00000000-0000-0000-0000-000000979412',
                        979402, 2, 'USER', 'APP', 'Waiting question.', '00000000-0000-0000-0000-000000979434', now()),
                       ('00000000-0000-0000-0000-000000979445', 979409, '00000000-0000-0000-0000-000000979413',
                        979402, 1, 'USER', 'APP', 'Other workspace.', '00000000-0000-0000-0000-000000979435', now());
                """);
        return database;
    }

    /** What each lookup returns for the two accounts. */
    private static final String LOOKUPS = """
            SELECT public.conversation_owner(979400, '00000000-0000-0000-0000-000000979411')::text
            UNION ALL SELECT public.conversation_owner(979400, '00000000-0000-0000-0000-000000979412')::text
            UNION ALL SELECT public.conversation_owner(979409, '00000000-0000-0000-0000-000000979411')::text
            UNION ALL SELECT public.conversation_turn_owner(979400, '00000000-0000-0000-0000-000000979432')::text
            UNION ALL SELECT public.conversation_turn_owner(979409, '00000000-0000-0000-0000-000000979435')::text
            UNION ALL SELECT (SELECT id || ':' || account_id FROM public.conversation_slack_main(979400, 'T1', 'U1', 'D1'))
            UNION ALL SELECT (SELECT id || ':' || account_id FROM public.conversation_slack_main(979400, 'T1', 'U2', 'D2'))
            UNION ALL SELECT (SELECT id || ':' || account_id FROM public.conversation_slack_main(979400, 'T1', 'U2', 'D1'))
            UNION ALL SELECT public.conversation_run_question_seq(979400, '00000000-0000-0000-0000-000000979422')::text
            UNION ALL SELECT public.conversation_run_question_seq(979409, '00000000-0000-0000-0000-000000979425')::text
            UNION ALL SELECT count(*)::text FROM public.conversation_entry_place(979400,
                '00000000-0000-0000-0000-000000979442') WHERE account_id = 979401
                AND conversation_id = '00000000-0000-0000-0000-000000979411' AND surface = 'APP'
            UNION ALL SELECT count(*)::text FROM public.conversation_entry_place(979409,
                '00000000-0000-0000-0000-000000979445') WHERE account_id = 979402
                AND conversation_id = '00000000-0000-0000-0000-000000979413'
            UNION ALL (SELECT string_agg(id || ':' || surface || ':' || attempt, ',' ORDER BY id)
                       FROM public.conversation_claimable_turns())
            UNION ALL (SELECT id::text FROM public.conversation_claimable_turns() LIMIT 1)
            UNION ALL (SELECT string_agg(id::text, ',' ORDER BY id) FROM public.conversation_claimable_turn_ids(979400,
                ARRAY['00000000-0000-0000-0000-000000979431', '00000000-0000-0000-0000-000000979432',
                      '00000000-0000-0000-0000-000000979434', '00000000-0000-0000-0000-000000979435']::uuid[]))
            UNION ALL SELECT public.conversation_held_turn_count()::text
            UNION ALL SELECT public.conversation_queued_question_count()::text
            """;

    /** The two runs without a turn count neither as held nor as queued. */
    private static final List<String> EXPECTED = List.of(
            "979401",
            "979402",
            "",
            "979401",
            "979402",
            "00000000-0000-0000-0000-000000979411:979401",
            "00000000-0000-0000-0000-000000979412:979402",
            "",
            "2",
            "1",
            "1",
            "1",
            "00000000-0000-0000-0000-000000979431:APP:0,00000000-0000-0000-0000-000000979435:SLACK:0",
            "00000000-0000-0000-0000-000000979435",
            "00000000-0000-0000-0000-000000979431",
            "1",
            "4");

    @Test
    void shouldReadEachFactForBothAccounts() {
        assertThat(nulled(PracticeMigrationFixture.column(SEEDED, LOOKUPS))).isEqualTo(EXPECTED);
    }

    /**
     * The planner cannot inline a definer function, so each wait read keeps its bound inside the function: the scoped
     * check starts from the turns it names, and the publication stops after its newest hundred turns. Neither reads
     * the backlog of every workspace, which a worker outage can make long.
     */
    @Test
    void shouldReadOnlyTheTurnsThatEachWaitReadIsBoundTo() throws SQLException {
        var backlog = PostgreSQLTestContainer.copyDatabase(
                "conversation_owner_backlog_" + UUID.randomUUID().toString().replace("-", ""), SEEDED);
        PracticeMigrationFixture.execute(PostgreSQLTestContainer.asOwner(backlog), """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979450, 'owner-backlog', 'Owner backlog', 'owner-backlog', 'ORG', 'ACTIVE');
                INSERT INTO account(id, display_name) SELECT 990000 + g, 'Backlog' FROM generate_series(1, 20000) g;
                INSERT INTO conversation(id, workspace_id, account_id, created_at, last_activity_at)
                SELECT md5('c' || g)::uuid, 979450, 990000 + g, now(), now() FROM generate_series(1, 20000) g;
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    available_at, created_at)
                SELECT md5('r' || g)::uuid, 979450, 'TURN', 'INTERACTIVE', 'conversation:' || md5('c' || g)::uuid,
                       'QUEUED', 0, 0, now(), now()
                FROM generate_series(1, 20000) g;
                INSERT INTO conversation_turn(id, workspace_id, conversation_id, account_id, request_id, status,
                    surface, attempt, run_id, created_at)
                SELECT md5('t' || g)::uuid, 979450, md5('c' || g)::uuid, 990000 + g, 'backlog-' || g, 'QUEUED',
                       'APP', 0, md5('r' || g)::uuid, now() - make_interval(secs => g)
                FROM generate_series(1, 20000) g;
                INSERT INTO conversation_entry(id, workspace_id, conversation_id, account_id, seq, kind, surface,
                    text, turn_id, created_at)
                SELECT md5('e' || g)::uuid, 979450, md5('c' || g)::uuid, 990000 + g, 1, 'USER', 'APP', 'Waiting.',
                       md5('t' || g)::uuid, now()
                FROM generate_series(1, 20000) g;
                ANALYZE conversation, conversation_turn, conversation_entry, agent_run;
                """);

        // Each row says whether it is the newest queued turn of the backlog.
        var scoped = turnsRead(backlog, """
                SELECT (id = md5('t1')::uuid)::text
                FROM public.conversation_claimable_turn_ids(979450, ARRAY[md5('t1')::uuid])
                """);
        assertThat(scoped.rows()).containsExactly("true");
        assertThat(scoped.turnsRead()).isLessThan(100);

        var published =
                turnsRead(backlog, "SELECT (id = md5('t1')::uuid)::text FROM public.conversation_claimable_turns()");
        assertThat(published.rows()).hasSize(100).contains("true");
        assertThat(published.turnsRead()).isLessThan(2_000);
    }

    private record Read(List<String> rows, long turnsRead) {}

    /** The rows of {@code sql}, and how many turn rows the transaction read by any scan. */
    private static Read turnsRead(TestDatabase database, String sql) throws SQLException {
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                List<String> rows = new ArrayList<>();
                try (var result = statement.executeQuery(sql)) {
                    while (result.next()) rows.add(result.getString(1));
                }
                try (var result = statement.executeQuery("""
                        SELECT coalesce(seq_tup_read, 0) + coalesce(idx_tup_fetch, 0)
                        FROM pg_stat_xact_user_tables WHERE relid = 'public.conversation_turn'::regclass
                        """)) {
                    return new Read(rows, result.next() ? result.getLong(1) : 0);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "public.conversation_owner(bigint, uuid)",
                "public.conversation_turn_owner(bigint, uuid)",
                "public.conversation_slack_main(bigint, text, text, text)",
                "public.conversation_run_question_seq(bigint, uuid)",
                "public.conversation_claimable_turn_ids(bigint, uuid[])",
                "public.conversation_claimable_turns()",
                "public.conversation_held_turn_count()",
                "public.conversation_queued_question_count()",
                "public.conversation_entry_place(bigint, uuid)"
            })
    void shouldOwnPinAndCloseEachDefinerFunction(String signature) {
        assertThat(PracticeMigrationFixture.column(SEEDED, """
                        SELECT format('%%s %%s %%s %%s public=%%s app=%%s', p.prosecdef, pg_get_userbyid(p.proowner),
                            p.proconfig, p.provolatile,
                            EXISTS (SELECT FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                                    WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE'),
                            has_function_privilege('hephaestus_app', p.oid, 'EXECUTE'))
                        FROM pg_proc p WHERE p.oid = '%s'::regprocedure
                        """.formatted(signature)))
                .containsExactly("t hephaestus_owner {\"search_path=pg_catalog, pg_temp\"} s public=f app=t");
    }

    /**
     * With an owner policy on the three tables, as a later migration adds it, the application role bound to the second
     * account sees none of the first account's rows, and each function still reads them.
     */
    @Test
    void shouldReadAnotherAccountsFactsWhileTheOwnerPolicyHidesItsRows() throws SQLException {
        var database = PostgreSQLTestContainer.copyDatabase(
                "conversation_owner_policy_" + UUID.randomUUID().toString().replace("-", ""), SEEDED);
        var statements = new StringBuilder();
        for (String table : List.of("conversation", "conversation_entry", "conversation_turn")) {
            statements.append("""
                    ALTER TABLE public.%1$s ENABLE ROW LEVEL SECURITY;
                    CREATE POLICY %1$s_owner ON public.%1$s TO hephaestus_app
                        USING (account_id = public.current_owner_account())
                        WITH CHECK (account_id = public.current_owner_account());
                    """.formatted(table));
        }
        PracticeMigrationFixture.execute(PostgreSQLTestContainer.asOwner(database), statements.toString());

        var application = PostgreSQLTestContainer.asApplication(database);
        assertThat(asSecond(application, """
                        SELECT count(*) FROM conversation WHERE account_id = 979401
                        UNION ALL SELECT count(*) FROM conversation_entry WHERE account_id = 979401
                        UNION ALL SELECT count(*) FROM conversation_turn WHERE account_id = 979401
                        UNION ALL SELECT count(*) FROM conversation WHERE account_id = 979402
                        """)).containsExactly("0", "0", "0", "2");
        assertThat(asSecond(application, LOOKUPS)).isEqualTo(EXPECTED);
    }

    /** The first column of each row, read as the application role bound to the second account. */
    private static List<String> asSecond(TestDatabase database, String sql) throws SQLException {
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_catalog.set_config('hephaestus.owner_account', '" + SECOND + "', true)");
                List<String> rows = new ArrayList<>();
                try (var result = statement.executeQuery(sql)) {
                    while (result.next()) {
                        String value = result.getString(1);
                        rows.add(value == null ? "" : value);
                    }
                }
                return rows;
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<String> nulled(List<@Nullable String> values) {
        return values.stream().map(value -> value == null ? "" : value).toList();
    }
}
