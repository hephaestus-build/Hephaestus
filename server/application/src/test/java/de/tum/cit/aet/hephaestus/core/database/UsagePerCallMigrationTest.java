package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Tag("database")
class UsagePerCallMigrationTest {
    private static final String CHANGELOG = "20261112044128-run-usage-per-call.xml";
    private static final long WORKSPACE = 978001;
    private static final long PRACTICE = 978004;
    private static final long SECOND_PRACTICE = 978006;
    private static final String REVIEW = "00000000-0000-0000-0000-000000978011";
    private static final String RUNNING_JOB = "00000000-0000-0000-0000-000000979041";
    private static final String BILLED_JOB = "00000000-0000-0000-0000-000000979042";
    private static final String TURN = "00000000-0000-0000-0000-000000979051";

    @Test
    void shouldMoveTheUnprojectedMeasurementsToCallRowsAndDropTheAttemptTables() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "usage_per_call_migration_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        // A second practice whose scripts called the same model kind: one row for each practice.
        PracticeMigrationFixture.execute(
                database, """
                INSERT INTO practice(id,workspace_id,slug,kind,current_version_id,feedback_mode)
                VALUES(%1$d,%2$d,'second-practice','scm.pull_request',%3$d,'AUTOMATIC');
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT %3$d,workspace_id,%1$d,1,definition FROM practice_version WHERE practice_id=%4$d;
                """.formatted(SECOND_PRACTICE, WORKSPACE, SECOND_PRACTICE + 1, PRACTICE));
        job(database, RUNNING_JOB, "COMPLETED");
        job(database, BILLED_JOB, "COMPLETED");
        PracticeMigrationFixture.execute(
                database, """
                -- The unbilled attempt: two chat calls with usage, one practice script chat call without it, three
                -- decision calls. The previous release had not written any of it to the ledger.
                INSERT INTO review_job_attempt_usage(job_id,attempt,workspace_id,proxy_calls,proxy_input_tokens,
                    proxy_output_tokens,proxy_reasoning_tokens)
                VALUES ('%1$s',0,%3$d,2,1000,100,10);
                INSERT INTO review_job_attempt_charge(job_id,attempt,workspace_id,model_kind,model,funding_source,
                    pricing_state,applied_price_id,applied_per_1m_input_usd,applied_per_1m_output_usd)
                VALUES ('%1$s',0,%3$d,'CHAT','chat-model','INSTANCE','PRICED',7,2,8),
                       ('%1$s',0,%3$d,'DECISION','decision-model','WORKSPACE','PRICED',NULL,3,3),
                       ('%1$s',0,%3$d,'RERANKING','free-rerank','INSTANCE','NO_CHARGE',NULL,NULL,NULL);
                INSERT INTO review_job_script_usage(job_id,attempt,model_kind,practice_id,workspace_id,calls,
                    reported_calls,input_tokens,output_tokens)
                VALUES ('%1$s',0,'DECISION',%4$d,%3$d,3,3,300,30),
                       ('%1$s',0,'DECISION',%6$d,%3$d,1,1,50,5),
                       ('%1$s',0,'CHAT',%4$d,%3$d,2,1,40,4);
                INSERT INTO review_job_script_usage(job_id,attempt,model_kind,practice_id,workspace_id,calls,reported_calls,search_units)
                VALUES ('%1$s',0,'RERANKING',%4$d,%3$d,1,1,7);
                -- The billed attempt: its ledger rows exist already and stay as they are.
                INSERT INTO review_job_attempt_usage(job_id,attempt,workspace_id,proxy_calls,proxy_input_tokens)
                VALUES ('%2$s',0,%3$d,1,77);
                INSERT INTO llm_usage_event(id,workspace_id,job_type,source_type,source_id,source_attempt,review_id,
                    model,input_tokens,total_calls,cost_usd,occurred_at,pricing_state,usage_provenance,
                    measurement_verification)
                VALUES ('00000000-0000-0000-0000-000000979061',%3$d,'PRACTICE_REVIEW','REVIEW_JOB','%2$s',0,
                        '%5$s','chat-model',77,4,0.5,'2026-08-01T12:00:00Z','PRICED','PROXY','VERIFIED'),
                       ('00000000-0000-0000-0000-000000979062',%3$d,'PRACTICE_REVIEW','PRACTICE_SCRIPT_EMBEDDING',
                        '%2$s',0,'%5$s','embedding-model',9,2,0.1,'2026-08-01T12:00:00Z','PRICED','PROXY','VERIFIED');
                """.formatted(RUNNING_JOB, BILLED_JOB, WORKSPACE, PRACTICE, REVIEW, SECOND_PRACTICE));
        turnWithUnprojectedUsage(database);

        migrate(database);

        assertThat(ledger(database, RUNNING_JOB))
                .containsExactlyInAnyOrder(
                        "REVIEW_JOB/CHAT/-/chat-model/1000/100/10/3/1/UNVERIFIABLE/UNPRICED/INSTANCE/7/ENDED/-/"
                                + REVIEW,
                        "PRACTICE_SCRIPT_DECISION/DECISION/" + PRACTICE
                                + "/decision-model/300/30/0/3/0/VERIFIED/UNPRICED/WORKSPACE/-/ENDED/-/" + REVIEW,
                        "PRACTICE_SCRIPT_DECISION/DECISION/" + SECOND_PRACTICE
                                + "/decision-model/50/5/0/1/0/VERIFIED/UNPRICED/WORKSPACE/-/ENDED/-/" + REVIEW,
                        "PRACTICE_SCRIPT_RERANKING/RERANKING/" + PRACTICE
                                + "/free-rerank/0/0/0/1/0/VERIFIED/NO_CHARGE/INSTANCE/-/ENDED/-/" + REVIEW);
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT cost_usd::text FROM llm_usage_event WHERE model='free-rerank'"))
                .containsExactly("0.000000");
        assertThat(ledger(database, BILLED_JOB))
                .as("history keeps its rows, without a run, and gains its model kind")
                .containsExactlyInAnyOrder(
                        "REVIEW_JOB/CHAT/-/chat-model/77/0/0/4/0/VERIFIED/PRICED/INSTANCE/-/ENDED/-/" + REVIEW,
                        "PRACTICE_SCRIPT_EMBEDDING/EMBEDDING/-/embedding-model/9/0/0/2/0/VERIFIED/PRICED/INSTANCE/-/ENDED/-/"
                                + REVIEW);
        assertThat(ledger(database, TURN))
                .containsExactly("MENTOR_TURN/CHAT/-/heph-model/5000/50/0/2/0/VERIFIED/UNPRICED/INSTANCE/-/ENDED/-/-");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT applied_per_1m_input_usd::text FROM llm_usage_event WHERE source_id='" + TURN + "'"))
                .as("the frozen rates the repricing pass prices it from")
                .containsExactly("10.00000000");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT bool_and(started_at = occurred_at)::text FROM llm_usage_event WHERE workspace_id="
                                + WORKSPACE))
                .containsExactly("true");
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables
                        WHERE table_name IN ('review_job_attempt_usage', 'review_job_attempt_charge',
                                             'review_job_script_usage', 'mentor_turn_attempt_usage')
                        """)).containsExactly((String) null);
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE (table_name = 'review_job' AND column_name LIKE 'llm\\_%')
                           OR (table_name = 'mentor_turn' AND column_name IN ('input_tokens', 'output_tokens',
                               'cached_tokens', 'cache_write_tokens', 'reasoning_tokens', 'llm_calls', 'cost_usd'))
                        """)).containsExactly("0");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
                        INSERT INTO llm_usage_event(id,workspace_id,job_type,source_type,source_id,source_attempt,
                            model_kind,occurred_at,started_at,call_state)
                        VALUES (gen_random_uuid(),%d,'PRACTICE_REVIEW','REVIEW_JOB',gen_random_uuid(),0,'CHAT',now(),
                                now(),'OPEN')
                        """.formatted(WORKSPACE)))
                .as("an open call always has the live claim that made it")
                .hasMessageContaining("ck_llm_usage_event_open_call");
        assertBoundRollbackAndUpdate(database);
    }

    /** The final schema change can roll back and update without changing the ledger measurements. */
    private static void assertBoundRollbackAndUpdate(TestDatabase database) throws Exception {
        PracticeMigrationFixture.execute(database, """
                UPDATE llm_usage_event SET cost_bound_usd = 0.75
                WHERE id = '00000000-0000-0000-0000-000000979061'
                """);
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT cost_bound_usd::text FROM llm_usage_event
                        WHERE id = '00000000-0000-0000-0000-000000979061'
                        """)).containsExactly("0.750000");
        List<String> before = ledgerWithoutBounds(database);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.rollback(1, new Contexts("prod"), new LabelExpression());
            assertThat(PracticeMigrationFixture.column(database, """
                            SELECT count(*)::text FROM information_schema.columns
                            WHERE table_name = 'llm_usage_event' AND column_name = 'cost_bound_usd'
                            """)).containsExactly("0");
            assertThat(ledgerWithoutBounds(database)).isEqualTo(before);

            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE table_name = 'llm_usage_event' AND column_name = 'cost_bound_usd'
                          AND is_nullable = 'YES' AND numeric_precision = 18 AND numeric_scale = 6
                        """)).containsExactly("1");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT bool_and(cost_bound_usd IS NULL)::text FROM llm_usage_event"))
                .containsExactly("true");
        assertThat(ledgerWithoutBounds(database)).isEqualTo(before);
    }

    private static List<String> ledgerWithoutBounds(TestDatabase database) {
        return PracticeMigrationFixture.column(
                        database, "SELECT (to_jsonb(e) - 'cost_bound_usd')::text FROM llm_usage_event e ORDER BY id")
                .stream()
                .map(String::valueOf)
                .toList();
    }

    private static void job(TestDatabase database, String id, String status) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,lease_epoch,stop_reason,ended_at)
                VALUES ('%1$s',%2$d,'REVIEW','BATCH','review:%1$s','ENDED',1,'COMPLETED',now());
                INSERT INTO review_job
                    (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot,
                     run_id,started_at,execution_started_at,completed_at)
                VALUES ('%1$s','%3$s',%2$d,now(),'%4$s','NONE','00000000000000000000000000000001','{}','{}','%1$s',
                        now(),now(),%5$s)
                """.formatted(
                id, WORKSPACE, REVIEW, status, status.equals("QUEUED") || status.equals("RUNNING") ? "NULL" : "now()"));
    }

    /** A conversation turn whose attempt the previous release measured but had not billed yet. */
    private static void turnWithUnprojectedUsage(TestDatabase database) {
        String conversation = UUID.randomUUID().toString();
        String run = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('%1$s',%2$d,978003,now(),now());
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,lease_epoch,stop_reason,ended_at)
                VALUES ('%3$s',%2$d,'TURN','INTERACTIVE','conversation:%1$s','ENDED',1,'COMPLETED',now());
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,run_id,
                    created_at,queued_at,started_at,finished_at)
                VALUES('%4$s',%2$d,'%1$s','request-%4$s','DONE','APP',1,'%3$s',now(),now(),now(),now());
                INSERT INTO mentor_turn_attempt_usage(turn_id,attempt,workspace_id,model,funding_source,pricing_state,
                    applied_per_1m_input_usd,applied_per_1m_output_usd,calls,input_tokens,output_tokens)
                VALUES('%4$s',1,%2$d,'heph-model','INSTANCE','PRICED',10,20,2,5000,50);
                """.formatted(conversation, WORKSPACE, run, TURN));
    }

    /**
     * source type/model kind/practice/model/input/output/reasoning/calls/unreported/verification/pricing/purse/price
     * id/state/run/review of each ledger row of a source.
     */
    private static List<String> ledger(TestDatabase database, String sourceId) {
        return PracticeMigrationFixture.column(database, """
                        SELECT source_type || '/' || model_kind || '/' || COALESCE(practice_id::text, '-') || '/'
                            || COALESCE(model, '-') || '/' || input_tokens || '/' || output_tokens || '/'
                            || reasoning_tokens || '/' || total_calls || '/' || unreported_calls || '/'
                            || measurement_verification || '/' || pricing_state || '/' || funding_source || '/'
                            || COALESCE(applied_price_id::text, '-') || '/' || call_state || '/'
                            || COALESCE(run_id::text, '-') || '/' || COALESCE(review_id::text, '-')
                        FROM llm_usage_event WHERE source_id = '%s'
                        """.formatted(sourceId)).stream()
                .map(String::valueOf)
                .toList();
    }

    private static void migrate(TestDatabase database) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }
}
