package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Legacy pending drafts continue without a model answer; new calls have instance scope and survive erasure. */
@Tag("database")
class DraftReportOutboxMigrationTest {
    private static final String CHANGELOG = "-act-draft-report-outbox.xml";

    @Test
    void shouldRouteLegacyDraftsAndDetachAnOpenCallWhenItsReportIsErased() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("draft_report_outbox");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            jdbc.update("""
                INSERT INTO account(id,display_name,app_role,status,created_at,updated_at,version)
                VALUES (987630,'Migration reporter','USER','ACTIVE',clock_timestamp(),clock_timestamp(),0)
                """);
            UUID report = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO product_feedback(id,account_id,kind,message,submission_minute,created_at)
                VALUES (?,987630,'BUG','Synthetic report',clock_timestamp(),clock_timestamp())
                """, report);
            jdbc.update("""
                INSERT INTO product_feedback_filing(feedback_id,state,allow_public,risk_latch,attempt,input_tokens,output_tokens,file_disclosed,failures,created_at,updated_at,next_attempt_at,
                    claimed_by,lease_until,answer,drafted_at)
                VALUES (?,'DRAFTING',false,false,1,0,0,true,0,clock_timestamp(),clock_timestamp(),clock_timestamp(),
                    'old-owner',clock_timestamp() + interval '5 minutes','obsolete answer',clock_timestamp())
                """, report);
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            assertThat(jdbc.queryForObject(
                            "SELECT state FROM product_feedback_filing WHERE feedback_id=?", String.class, report))
                    .isEqualTo("ANSWERED");
            assertThat(jdbc.queryForObject(
                            "SELECT answer FROM product_feedback_filing WHERE feedback_id=?", String.class, report))
                    .isNull();
            assertThat(jdbc.queryForObject(
                            "SELECT drafted_at FROM product_feedback_filing WHERE feedback_id=?",
                            Timestamp.class,
                            report))
                    .isNull();
            UUID call = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO llm_usage_event(id,job_type,source_type,source_id,source_attempt,model_kind,funding_source,
                    action_id,settle_after,initial_pricing_state,pricing_state,call_state,occurred_at,started_at)
                VALUES (?,'REPORT_DRAFT','PRODUCT_FEEDBACK_DRAFT',?,1,'CHAT','INSTANCE',gen_random_uuid(),clock_timestamp()+interval '2 minutes','UNPRICED','UNPRICED','OPEN',
                    clock_timestamp(),clock_timestamp())
                """, call, report);
            jdbc.update("DELETE FROM product_feedback WHERE id=?", report);
            assertThat(jdbc.queryForObject("SELECT source_id FROM llm_usage_event WHERE id=?", UUID.class, call))
                    .isNotEqualTo(report);
            jdbc.update("UPDATE llm_usage_event SET call_state='ENDED', input_tokens=19 WHERE id=?", call);
            assertThat(jdbc.queryForObject("SELECT input_tokens FROM llm_usage_event WHERE id=?", Long.class, call))
                    .isEqualTo(19);
            assertThatThrownBy(
                            () -> jdbc.update("UPDATE llm_usage_event SET source_type='REVIEW_JOB' WHERE id=?", call))
                    .hasMessageContaining("ck_llm_usage_event_scope");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasMessageContaining("Restore the verified database backup");
        }
    }
}
