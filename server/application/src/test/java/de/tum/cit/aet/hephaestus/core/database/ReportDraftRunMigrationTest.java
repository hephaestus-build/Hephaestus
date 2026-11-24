package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
class ReportDraftRunMigrationTest {
    private static final String CHANGELOG = "-draft-report-runs.xml";

    @Test
    void shouldCarryForwardOnlyLiveDraftsAndKeepInstanceAccounting() throws Exception {
        String databaseName = "report_draft_runs";
        var database = PostgreSQLTestContainer.createDatabase(databaseName);
        PostgreSQLTestContainer.applyDatabaseRoles(databaseName);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(owner.jdbcUrl(), owner.username(), owner.password()));

        UUID pending = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        UUID call = UUID.randomUUID();
        seed(jdbc, pending, action, 60);
        seed(jdbc, expired, UUID.randomUUID(), -60);
        jdbc.update("""
            INSERT INTO llm_usage_event(id,job_type,source_type,source_id,source_attempt,model_kind,funding_source,
                action_id,settle_after,initial_pricing_state,pricing_state,call_state,occurred_at,started_at)
            VALUES (?,'REPORT_DRAFT','PRODUCT_FEEDBACK_DRAFT',?,1,'CHAT','INSTANCE',?,
                now()+interval '1 minute','UNPRICED','UNPRICED','OPEN',now(),now())
            """, call, pending, action);

        PostgreSQLTestContainer.migrateDatabase(owner, "prod");

        assertThat(jdbc.queryForMap(
                        "SELECT workspace_id, kind, state, concurrency_key FROM agent_run WHERE id=?", action))
                .containsEntry("workspace_id", null)
                .containsEntry("kind", "DRAFT_REPORT")
                .containsEntry("state", "QUEUED")
                .containsEntry("concurrency_key", "draft:" + pending + ":1");
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM product_feedback_filing WHERE feedback_id=?", String.class, expired))
                .isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE kind='DRAFT_REPORT'", Long.class))
                .isZero();
        assertThat(jdbc.queryForMap("SELECT call_state,run_id FROM llm_usage_event WHERE id=?", call))
                .containsEntry("call_state", "SETTLED")
                .containsEntry("run_id", null);

        assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO agent_run(id, kind, band, concurrency_key)
                    VALUES (gen_random_uuid(), 'REVIEW', 'BATCH', 'review:unscoped')
                    """)).hasMessageContaining("ck_agent_run_scope");
        assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO agent_sandbox(id, kind, subject_key, claim_name, shutdown_at)
                    VALUES (gen_random_uuid(), 'CONVERSATION', 'conversation:unscoped', 'unscoped', now()+interval '1 minute')
                    """)).hasMessageContaining("ck_agent_sandbox_scope");

        jdbc.update("DELETE FROM product_feedback WHERE id=?", pending);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE id=?", Long.class, action))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT source_id FROM llm_usage_event WHERE id=?", UUID.class, call))
                .isNotEqualTo(pending);
    }

    private static void seed(JdbcTemplate jdbc, UUID report, UUID action, int remainingSeconds) {
        long account = 998511;
        jdbc.update(
                "INSERT INTO account(id,display_name) VALUES (?,'Migration reporter') ON CONFLICT DO NOTHING", account);
        jdbc.update("""
            INSERT INTO product_feedback(id,account_id,kind,message,submission_minute,created_at)
            VALUES (?,?,'BUG','Synthetic report',now(),now())
            """, report, account);
        jdbc.update("""
            INSERT INTO product_feedback_filing(feedback_id,state,allow_public,risk_latch,attempt,input_tokens,output_tokens,
                file_disclosed,created_at,updated_at,next_attempt_at,drafting_started_at)
            VALUES (?,'DRAFT_QUEUED',false,false,1,0,0,true,now(),now(),now(),now())
            """, report);
        jdbc.update("""
            INSERT INTO agent_action(id,kind,lane_key,idempotency_key,payload,subject_account_id,state,max_attempts,
                next_attempt_at,expires_at,created_at,updated_at)
            VALUES (?,'DRAFT_REPORT',?,?,'{}',?,'ADMITTED',3,now(),now()+make_interval(secs => ?),now(),now())
            """, action, "file_issue:" + report, "draft:" + report + ":1", account, remainingSeconds);
    }
}
