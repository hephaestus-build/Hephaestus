package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ReviewAttemptUsageDatabaseTest {
    private static final String JOB = "00000000-0000-0000-0000-000000978041";
    private static final String OTHER_JOB = "00000000-0000-0000-0000-000000978042";
    private static final String LEDGER = "00000000-0000-0000-0000-000000978043";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldEnforceTheOwningJobAndWorkspaceOfEveryAttemptCharge(String context) {
        var database = NativePracticeFixture.resultParent(context);
        insertJob(database, JOB);
        assertForeignKey(
                database,
                "review_job_attempt_usage",
                "sfk_review_job_attempt_usage_job",
                "FOREIGN KEY (job_id, workspace_id) REFERENCES review_job(id, workspace_id) ON DELETE CASCADE");
        assertForeignKey(
                database,
                "review_job_attempt_usage",
                "sfk_review_job_attempt_usage_workspace",
                "FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT");
        assertForeignKey(
                database,
                "review_job_attempt_charge",
                "sfk_review_job_attempt_charge_usage",
                "FOREIGN KEY (job_id, attempt, workspace_id) REFERENCES review_job_attempt_usage(job_id, attempt, workspace_id) ON DELETE CASCADE");

        assertForeignKeyViolation(database, usageInsert(JOB, 0, 978002), "sfk_review_job_attempt_usage_job");
        insertUsage(database, JOB, 0, 978001);
        assertForeignKeyViolation(database, usageInsert(OTHER_JOB, 0, 978001), "sfk_review_job_attempt_usage_job");
        assertForeignKeyViolation(database, chargeInsert(JOB, 0, 978002), "sfk_review_job_attempt_charge_usage");
        assertForeignKeyViolation(database, chargeInsert(JOB, 1, 978001), "sfk_review_job_attempt_charge_usage");
        PracticeMigrationFixture.execute(database, chargeInsert(JOB, 0, 978001));

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT workspace_id || ':' || attempt FROM review_job_attempt_usage WHERE job_id='" + JOB
                                + "'"))
                .containsExactly("978001:0");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT workspace_id || ':' || attempt || ':' || model_kind FROM review_job_attempt_charge WHERE job_id='"
                                + JOB + "'"))
                .containsExactly("978001:0:CHAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldCascadeRawAttemptAccountingWhenItsJobIsDeletedAndKeepTheSoftLedger(String context) {
        var database = NativePracticeFixture.resultParent(context);
        insertJob(database, JOB);
        insertJob(database, OTHER_JOB);
        insertUsage(database, JOB, 0, 978001);
        insertUsage(database, JOB, 1, 978001);
        insertUsage(database, OTHER_JOB, 0, 978001);
        PracticeMigrationFixture.execute(
                database,
                chargeInsert(JOB, 0, 978001) + ";" + chargeInsert(JOB, 1, 978001) + ";"
                        + chargeInsert(OTHER_JOB, 0, 978001));
        PracticeMigrationFixture.execute(database, """
                INSERT INTO llm_usage_event
                    (id,workspace_id,review_id,job_type,source_id,source_type,source_attempt,
                     model,input_tokens,output_tokens,total_calls,cost_usd,occurred_at,pricing_state,
                     usage_provenance,measurement_verification)
                VALUES ('00000000-0000-0000-0000-000000978043',978001,
                        '00000000-0000-0000-0000-000000978011','PRACTICE_REVIEW',
                        '00000000-0000-0000-0000-000000978041','REVIEW_JOB',0,
                        'frozen-model',100,20,1,0.125,'2026-10-01T12:00:00Z','PRICED','PROXY','VERIFIED')
                """);
        var ledgerBefore = PracticeMigrationFixture.column(
                database, "SELECT to_jsonb(e)::text FROM llm_usage_event e WHERE id='" + LEDGER + "'");
        assertThat(ledgerBefore).hasSize(1);
        var otherUsage = PracticeMigrationFixture.column(
                database, "SELECT to_jsonb(u)::text FROM review_job_attempt_usage u WHERE job_id='" + OTHER_JOB + "'");
        var otherCharge = PracticeMigrationFixture.column(
                database, "SELECT to_jsonb(c)::text FROM review_job_attempt_charge c WHERE job_id='" + OTHER_JOB + "'");
        assertThat(otherUsage).hasSize(1);
        assertThat(otherCharge).hasSize(1);

        PracticeMigrationFixture.execute(database, "DELETE FROM review_job WHERE id='" + JOB + "'");

        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT count(*)::text FROM review_job WHERE id='" + JOB + "'"))
                .containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT count(*)::text FROM review_job_attempt_usage WHERE job_id='" + JOB + "'"))
                .containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT count(*)::text FROM review_job_attempt_charge WHERE job_id='" + JOB + "'"))
                .containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT to_jsonb(e)::text FROM llm_usage_event e WHERE id='" + LEDGER + "'"))
                .isEqualTo(ledgerBefore);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT to_jsonb(u)::text FROM review_job_attempt_usage u WHERE job_id='" + OTHER_JOB + "'"))
                .isEqualTo(otherUsage);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT to_jsonb(c)::text FROM review_job_attempt_charge c WHERE job_id='" + OTHER_JOB + "'"))
                .isEqualTo(otherCharge);
    }

    private static void insertJob(TestDatabase database, String jobId) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,stop_reason,ended_at)
                VALUES ('%s',978001,'REVIEW','BATCH','review:%s','ENDED','COMPLETED',now());
                INSERT INTO review_job
                    (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot,run_id)
                VALUES ('%s','00000000-0000-0000-0000-000000978011',978001,now(),
                        'COMPLETED','DONE','00000000000000000000000000000001','{}','{}','%s')
                """.formatted(jobId, jobId, jobId, jobId));
    }

    private static void insertUsage(TestDatabase database, String jobId, int attempt, long workspaceId) {
        PracticeMigrationFixture.execute(database, usageInsert(jobId, attempt, workspaceId));
    }

    private static String usageInsert(String jobId, int attempt, long workspaceId) {
        return "INSERT INTO review_job_attempt_usage(job_id,attempt,workspace_id) VALUES ('" + jobId + "'," + attempt
                + "," + workspaceId + ")";
    }

    private static String chargeInsert(String jobId, int attempt, long workspaceId) {
        return "INSERT INTO review_job_attempt_charge(job_id,attempt,workspace_id,model_kind,funding_source,pricing_state) VALUES ('"
                + jobId + "'," + attempt + "," + workspaceId + ",'CHAT','INSTANCE','NO_CHARGE')";
    }

    private static void assertForeignKey(TestDatabase database, String table, String name, String definition) {
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT convalidated::text || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='public."
                                + table + "'::regclass AND contype='f' AND conname='" + name + "'"))
                .containsExactly("true:" + definition);
    }

    private static void assertForeignKeyViolation(TestDatabase database, String sql, String constraint) {
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, sql))
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, failure -> {
                    assertThat(failure.getSQLState()).isEqualTo("23503");
                    assertThat(failure.getMessage()).contains(constraint);
                });
    }
}
