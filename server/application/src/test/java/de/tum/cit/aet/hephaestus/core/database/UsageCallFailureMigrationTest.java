package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Populated upgrade proof for the call-failure columns, including their trust boundary and safe rollback. */
@Tag("database")
class UsageCallFailureMigrationTest {
    private static final String CHANGELOG = "-fix-sbx4-failure-recording.xml";
    private static final long WORKSPACE = 979201;

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveExistingCallsAndAcceptOnlyFailureKindAndStatusPairedWithTheirTime(String context)
            throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "usage_failure_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,leased_until,started_at)
                VALUES('00000000-0000-0000-0000-000000979202',%1$d,'REVIEW','BATCH','failure-upgrade','LEASED',0,1,
                    now() + interval '10 minutes',now());
                INSERT INTO llm_usage_event(id,workspace_id,job_type,source_type,source_id,source_attempt,run_id,
                    lease_epoch,call_state,model_kind,model,input_tokens,cost_usd,pricing_state,initial_pricing_state,
                    applied_per_1m_input_usd,started_at,occurred_at)
                SELECT gen_random_uuid(),%1$d,'PRACTICE_REVIEW','REVIEW_JOB',gen_random_uuid(),0,
                    CASE WHEN state = 'ENDED' THEN NULL ELSE '00000000-0000-0000-0000-000000979202'::uuid END,
                    1,state,'CHAT','frozen-model',CASE WHEN state = 'ENDED' THEN 77 ELSE 0 END,
                    CASE state WHEN 'ENDED' THEN 0.5 WHEN 'OPEN' THEN 0 END,
                    CASE WHEN state = 'SETTLED' THEN 'UNPRICED' ELSE 'PRICED' END,'PRICED',2,
                    '2026-08-01T12:00:00Z','2026-08-01T12:00:00Z'
                FROM unnest(ARRAY['OPEN','SETTLED','ENDED']) AS state;
                """.formatted(WORKSPACE));
        String retained =
                "SELECT call_state || '/' || input_tokens || '/' || COALESCE(cost_usd::text,'-') || '/' || initial_pricing_state"
                        + " || '/' || applied_per_1m_input_usd || '/' || occurred_at::text FROM llm_usage_event"
                        + " WHERE workspace_id=" + WORKSPACE + " ORDER BY call_state";
        var before = PracticeMigrationFixture.column(database, retained);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var contexts = new Contexts(context);
            liquibase.tag("before-call-failure");
            liquibase.update(contexts);

            assertThat(PracticeMigrationFixture.column(database, retained)).isEqualTo(before);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT count(*)::text FROM llm_usage_event WHERE workspace_id=" + WORKSPACE
                                    + " AND call_failure IS NULL AND failed_at IS NULL"))
                    .containsExactly("3");
            for (String failure : List.of(
                    "null",
                    "[]",
                    "{}",
                    "{\"kind\":\"upstream_status\"}",
                    "{\"kind\":null,\"status\":502}",
                    "{\"kind\":1,\"status\":502}",
                    "{\"kind\":\"\",\"status\":502}",
                    "{\"kind\":\"upstream_status\",\"status\":\"502\"}",
                    "{\"kind\":\"upstream_status\",\"status\":502.5}",
                    "{\"kind\":\"upstream_status\",\"status\":999}",
                    "{\"kind\":\"upstream_status\",\"status\":502,\"body\":\"provider text\"}")) {
                assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                                database,
                                "UPDATE llm_usage_event SET call_failure='" + failure
                                        + "'::jsonb,failed_at=clock_timestamp() WHERE workspace_id=" + WORKSPACE))
                        .hasMessageContaining("ck_llm_usage_event_call_failure");
            }
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE llm_usage_event SET failed_at=clock_timestamp() WHERE workspace_id=" + WORKSPACE))
                    .hasMessageContaining("ck_llm_usage_event_call_failure");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE llm_usage_event SET call_failure='{\"kind\":\"upstream_stalled\",\"status\":null}'"
                                    + "::jsonb WHERE workspace_id=" + WORKSPACE))
                    .hasMessageContaining("ck_llm_usage_event_call_failure");
            for (String status : List.of("502", "null")) {
                PracticeMigrationFixture.execute(
                        database,
                        "UPDATE llm_usage_event SET call_failure=jsonb_build_object('kind','upstream_status','status',"
                                + status + "::integer),failed_at=clock_timestamp() WHERE workspace_id=" + WORKSPACE);
                assertThat(PracticeMigrationFixture.column(database, retained)).isEqualTo(before);
            }

            liquibase.rollback("before-call-failure", contexts, new LabelExpression());
            assertThat(PracticeMigrationFixture.column(database, retained)).isEqualTo(before);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT column_name FROM information_schema.columns WHERE table_name='llm_usage_event'"
                                    + " AND column_name IN ('call_failure','failed_at')"))
                    .isEmpty();
        }
    }
}
