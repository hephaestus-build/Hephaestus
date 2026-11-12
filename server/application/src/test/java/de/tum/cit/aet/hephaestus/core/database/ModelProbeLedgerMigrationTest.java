package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
class ModelProbeLedgerMigrationTest {
    private static final String CHANGELOG = "-run-capability-probes-through-proxy.xml";

    @Test
    void shouldReverseTheProbeGuardAndUpdateAgainWithoutDiscardingAccounting() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("model_probe_ledger");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        UUID draft = UUID.randomUUID();
        insertUsage(jdbc, draft, "PRODUCT_FEEDBACK_DRAFT");
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var contexts = new Contexts("prod");
            var labels = new LabelExpression();
            liquibase.update(1, contexts, labels);
            assertThat(jdbc.queryForList("""
                    SELECT convalidated FROM pg_constraint WHERE conrelid='llm_usage_event'::regclass
                      AND conname IN ('ck_llm_usage_event_source_type', 'ck_llm_usage_event_scope',
                        'ck_llm_usage_event_open_call')
                    """, Boolean.class)).containsExactly(false, false, false);
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            assertThat(jdbc.queryForList("""
                    SELECT convalidated FROM pg_constraint WHERE conrelid='llm_usage_event'::regclass
                      AND conname IN ('ck_llm_usage_event_source_type', 'ck_llm_usage_event_scope',
                        'ck_llm_usage_event_open_call')
                    """, Boolean.class)).containsExactly(true, true, true);
            assertThat(jdbc.queryForObject("""
                    SELECT indisvalid AND indisready FROM pg_index
                    WHERE indexrelid='idx_llm_usage_runless_open'::regclass
                    """, Boolean.class)).isTrue();
            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);
            assertThat(jdbc.queryForObject("SELECT to_regclass('idx_llm_usage_runless_open')", String.class))
                    .isNull();
            assertThatThrownBy(() -> insertUsage(jdbc, UUID.randomUUID(), "PROBE"))
                    .hasMessageContaining("ck_llm_usage_event_scope");
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            UUID call = UUID.randomUUID();
            insertUsage(jdbc, call, "PROBE");
            assertThat(jdbc.queryForObject("SELECT source_type FROM llm_usage_event WHERE id=?", String.class, call))
                    .isEqualTo("PROBE");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasMessageContaining("Probe accounting must expire through retention before rollback");
            assertThat(jdbc.queryForObject("SELECT source_type FROM llm_usage_event WHERE id=?", String.class, call))
                    .isEqualTo("PROBE");
            assertThat(jdbc.queryForObject("SELECT source_type FROM llm_usage_event WHERE id=?", String.class, draft))
                    .isEqualTo("PRODUCT_FEEDBACK_DRAFT");
        }
    }

    private static void insertUsage(JdbcTemplate jdbc, UUID call, String sourceType) {
        String jobType = sourceType.equals("PROBE") ? "MODEL_CAPABILITY_PROBE" : "PRODUCT_FEEDBACK_DRAFT";
        jdbc.update("""
            INSERT INTO llm_usage_event(id,job_type,source_type,source_id,source_attempt,model_kind,funding_source,
                settle_after,initial_pricing_state,pricing_state,call_state,occurred_at,started_at)
            VALUES (?,?,?,gen_random_uuid(),0,'CHAT','INSTANCE',clock_timestamp()+interval '15 seconds',
                'UNPRICED','UNPRICED','ENDED',clock_timestamp(),clock_timestamp())
            """, call, jobType, sourceType);
    }
}
