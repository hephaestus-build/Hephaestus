package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMethodVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewModelBindingsAdapter;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Practice scripts call no model of their own. Stored methods keep the review slot as the server now freezes it,
 * queued configurations drop their script models, and the script-only purposes, protocols, charges and counters go.
 */
@Tag("database")
class ScriptModelMachineryMigrationTest {
    private static final String CHANGELOG = "20261112053359-fix-remove-script-model-machinery.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    // As the server's own mapper: a null property is left out, so a slot without a model has no model key.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final String REVIEW = "00000000-0000-0000-0000-000000978011";
    private static final String SECOND_REVIEW = "00000000-0000-0000-0000-000000978012";
    private static final String QUEUED_JOB = "00000000-0000-0000-0000-000000978251";
    private static final String ENDED_JOB = "00000000-0000-0000-0000-000000978252";
    private static final long CHAT_MODEL = 978212;
    private static final long DECISION_MODEL = 978211;

    /** The review model as the previous release froze it: the server's own options and the two direct limits. */
    private static final String FROZEN_BEFORE = """
            {"modelId":"instance:978212","providerProtocol":"openai-completions","providerModelId":"chat-1",
             "options":{"contextWindow":32000,"maxOutputTokens":16000,
             "practiceModelCalls":36,"practiceOutputTokens":48000,
             "directModelTokensPerAttempt":200000,"directModelSearchUnitsPerAttempt":1000}}""";

    private static final String DECISION_BEFORE = """
            {"modelId":"instance:978211","providerProtocol":"openai-decisions","providerModelId":"decide-1",
             "options":{"practiceModelCalls":36,"practiceOutputTokens":48000,
             "directModelTokensPerAttempt":200000,"directModelSearchUnitsPerAttempt":1000}}""";

    @Test
    void shouldKeepOnlyTheReviewSlotAndDeleteEveryScriptModelWhenUpgrading() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("script_model_machinery");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var dataSource = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(dataSource);
        var sql = new JdbcTemplate(dataSource);
        seed(sql);
        long decisionPool = requireLong(sql, "SELECT call_pool_id FROM llm_model WHERE id=?", DECISION_MODEL);

        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            liquibase.update(CONTEXTS, LABELS);

            var adapter = bindings();
            assertMethod(sql, REVIEW, MAPPER.writeValueAsString(adapter.models(978001, List.of(978003L))));
            assertMethod(sql, SECOND_REVIEW, MAPPER.writeValueAsString(adapter.models(978001, List.of())));

            assertThat(sql.queryForObject(
                            "SELECT config_snapshot::text FROM review_job WHERE id=?::uuid", String.class, QUEUED_JOB))
                    .isEqualTo(sql.queryForObject("""
                            SELECT '{"schemaVersion":10,"model":{"apiProtocol":"openai-completions"},
                              "contextWindow":32000,"maxOutputTokens":16000,"timeoutSeconds":600}'::jsonb::text""", String.class));
            assertThat(sql.queryForObject(
                            "SELECT config_snapshot::text FROM review_job WHERE id=?::uuid", String.class, ENDED_JOB))
                    .as("a configuration of another version is left alone")
                    .isEqualTo(sql.queryForObject("""
                            SELECT '{"schemaVersion":8,"model":{"apiProtocol":"openai-completions"},
                              "practiceScript":{"DECISION":{"apiProtocol":"openai-decisions"}}}'::jsonb::text""", String.class));

            assertThat(count(sql, "SELECT count(*) FROM llm_connection WHERE id IN (978201,978202)"))
                    .isEqualTo(1);
            assertThat(count(sql, "SELECT count(*) FROM llm_connection WHERE id=978202"))
                    .isEqualTo(1);
            assertThat(count(sql, "SELECT count(*) FROM llm_model WHERE id=" + DECISION_MODEL))
                    .isZero();
            assertThat(count(sql, "SELECT count(*) FROM llm_model WHERE id=" + CHAT_MODEL))
                    .isEqualTo(1);
            assertThat(count(sql, "SELECT count(*) FROM llm_model_price WHERE model_id=" + DECISION_MODEL))
                    .isZero();
            assertThat(count(sql, "SELECT count(*) FROM llm_model_workspace_grant WHERE model_id=" + DECISION_MODEL))
                    .isZero();
            assertThat(count(sql, "SELECT count(*) FROM llm_call_pool WHERE id=" + decisionPool))
                    .as("the model's own call pool ends with it")
                    .isZero();
            assertThat(sql.queryForList(
                            "SELECT purpose FROM workspace_agent_binding WHERE workspace_id=978001", String.class))
                    .containsExactly(AgentPurpose.PRACTICE_REVIEW.name());
            assertThat(count(sql, "SELECT count(*) FROM pg_class WHERE relname='review_job_script_usage'"))
                    .isZero();
            assertThat(count(
                            sql,
                            "SELECT count(*) FROM information_schema.columns"
                                    + " WHERE table_name='review_job_script_run' AND column_name='models'"))
                    .isZero();

            assertThatThrownBy(() -> liquibase.rollback(1, CONTEXTS, LABELS))
                    .hasStackTraceContaining("verified pre-upgrade database backup");
        }
    }

    /** The stored method of the review's one practice must be the one-slot method that the server now pins. */
    private static void assertMethod(JdbcTemplate sql, String review, String models) {
        assertThat(sql.queryForObject(
                        "SELECT method->'models' = CAST(? AS jsonb) FROM review_practice WHERE review_id=?::uuid",
                        Boolean.class,
                        models,
                        review))
                .as("the slots of review %s", review)
                .isTrue();
        assertThat(sql.queryForObject(
                        "SELECT method->>'modelDigest' FROM review_practice WHERE review_id=?::uuid",
                        String.class,
                        review))
                .isEqualTo(sql.queryForObject("SELECT review_model_digest(CAST(? AS jsonb))", String.class, models));
        assertThat(sql.queryForObject(
                        "SELECT (method->>'epoch')::int FROM review_practice WHERE review_id=?::uuid",
                        Integer.class,
                        review))
                .isEqualTo(1);
    }

    /** What the server freezes now for the bound chat model, with the settings the seed's options came from. */
    private static ReviewModelBindingsAdapter bindings() {
        var resolver = mock(LlmModelResolver.class);
        var binding = mock(WorkspaceAgentBinding.class);
        when(resolver.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://chat.example/v1", "openai-completions", "chat-1", 32000, 16000, null, false));
        when(resolver.connectionRef(binding))
                .thenReturn(
                        new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 978202L, CHAT_MODEL, 978001L));
        var routing = mock(MemberAiRoutingAdapter.class);
        when(routing.bindingForPeople(anyLong(), eq(AgentPurpose.PRACTICE_REVIEW), anyList()))
                .thenReturn(Optional.of(binding));
        var verifier = new ReviewMethodVerifier(
                resolver,
                new PracticeReviewProperties(false, 15, 5, null, 36, 48000, 1_152_000, Duration.ofMinutes(10)));
        return new ReviewModelBindingsAdapter(routing, verifier);
    }

    private static void seed(JdbcTemplate sql) {
        // The second review holds the same practice with a method that froze no model at all.
        sql.execute("""
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                VALUES('%s',978001,978006,'ITEM','native-v2','REQUEST','QUEUED',now());
                INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)
                VALUES('%s',978001,978004,978005,'READY')""".formatted(SECOND_REVIEW, SECOND_REVIEW));
        sql.update("""
                WITH slots AS (SELECT jsonb_build_array(
                    jsonb_build_object('slot','chat','kind','CHAT','model',CAST(? AS jsonb)),
                    jsonb_build_object('slot','decision','kind','DECISION','model',CAST(? AS jsonb)),
                    jsonb_build_object('slot','embedding','kind','EMBEDDING'),
                    jsonb_build_object('slot','reranking','kind','RERANKING'),
                    jsonb_build_object('slot','review','kind','CHAT','model',CAST(? AS jsonb))) AS models)
                UPDATE review_practice SET method=jsonb_build_object('epoch',1,'models',models,
                    'modelDigest',review_model_digest(models))
                FROM slots WHERE review_id=?::uuid""", FROZEN_BEFORE, DECISION_BEFORE, FROZEN_BEFORE, REVIEW);
        sql.update("""
                WITH slots AS (SELECT jsonb_build_array(
                    jsonb_build_object('slot','chat','kind','CHAT'),
                    jsonb_build_object('slot','decision','kind','DECISION'),
                    jsonb_build_object('slot','embedding','kind','EMBEDDING'),
                    jsonb_build_object('slot','reranking','kind','RERANKING'),
                    jsonb_build_object('slot','review','kind','CHAT')) AS models)
                UPDATE review_practice SET method=jsonb_build_object('epoch',1,'models',models,
                    'modelDigest',review_model_digest(models))
                FROM slots WHERE review_id=?::uuid""", SECOND_REVIEW);
        // Each model gets its own call pool from the insert trigger.
        sql.execute("""
                INSERT INTO llm_connection(id,slug,display_name,base_url,api_protocol,enabled,created_at) VALUES
                 (978201,'native-decisions','Decisions','https://decisions.example/v1','openai-decisions',true,now()),
                 (978202,'native-chat','Chat','https://chat.example/v1','openai-completions',true,now());
                INSERT INTO llm_model(id,connection_id,slug,display_name,upstream_model_id,context_window,
                    max_output_tokens,created_at) VALUES
                 (978211,978201,'native-decide','Decide','decide-1',null,null,now()),
                 (978212,978202,'native-chat','Chat','chat-1',32000,16000,now());
                INSERT INTO llm_model_price(id,model_id,pricing_mode,note,effective_from,created_at) VALUES
                 (978221,978211,'NO_CHARGE','Self-hosted',now(),now()),
                 (978222,978212,'NO_CHARGE','Self-hosted',now(),now());
                INSERT INTO llm_model_workspace_grant(model_id,workspace_id,granted_at)
                VALUES(978211,978001,now()),(978212,978001,now());
                INSERT INTO workspace_agent_binding(id,workspace_id,purpose,instance_model_id)
                VALUES(978231,978001,'PRACTICE_DECISION',978211),(978232,978001,'PRACTICE_REVIEW',978212)""");
        sql.execute("""
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key)
                VALUES('00000000-0000-0000-0000-000000978241',978001,'REVIEW','INTERACTIVE','native-queued');
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,ended_at,stop_reason)
                VALUES('00000000-0000-0000-0000-000000978242',978001,'REVIEW','INTERACTIVE','native-ended','ENDED',
                    now(),'COMPLETED');
                INSERT INTO review_job(id,review_id,workspace_id,submitted_at,completed_at,status,trace_id,input,
                    config_snapshot,run_id)
                VALUES('%1$s','%3$s',978001,now(),NULL,'QUEUED','trace','{}',
                    '{"schemaVersion":9,"model":{"apiProtocol":"openai-completions"},"contextWindow":32000,
                      "maxOutputTokens":16000,"timeoutSeconds":600,
                      "practiceScript":{"CHAT":{"apiProtocol":"openai-completions"},
                                        "DECISION":{"apiProtocol":"openai-decisions"}}}',
                    '00000000-0000-0000-0000-000000978241'),
                  ('%2$s','%3$s',978001,now(),now(),'COMPLETED','trace','{}',
                    '{"schemaVersion":8,"model":{"apiProtocol":"openai-completions"},
                      "practiceScript":{"DECISION":{"apiProtocol":"openai-decisions"}}}',
                    '00000000-0000-0000-0000-000000978242');
""".formatted(QUEUED_JOB, ENDED_JOB, REVIEW));
    }

    private static long requireLong(JdbcTemplate sql, String query, Object... arguments) {
        Long value = sql.queryForObject(query, Long.class, arguments);
        assertThat(value).isNotNull();
        return value == null ? 0 : value;
    }

    private static int count(JdbcTemplate sql, String query) {
        @Nullable Integer rows = sql.queryForObject(query, Integer.class);
        return rows == null ? 0 : rows;
    }

    private static Connection connect(TestDatabase database) throws Exception {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        return new Liquibase(
                "db/changelog/next/" + CHANGELOG,
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
    }
}
