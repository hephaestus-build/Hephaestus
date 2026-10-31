package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class UsageMeasurementVerificationMigrationTest {

    private static final String CUTOVER = "1791941746633_changelog.xml";
    private static final String HISTORY =
            "SELECT id || ':' || author || ':' || filename || ':' || md5sum FROM databasechangelog ORDER BY"
                    + " orderexecuted";
    private static final String HISTORICAL_ROWS =
            " FROM llm_usage_event t WHERE workspace_id IN (996101,996102) AND model LIKE 'historical-%' ORDER BY id";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldPreserveHistoricalUsageAndEnforceClosedVerificationWhenFullMasterIsUpgraded(String context)
            throws Exception {
        TestDatabase database = beforeCutover(context);
        seed(database);
        List<@Nullable String> before = query(database, "SELECT to_jsonb(t)::text" + HISTORICAL_ROWS);
        assertThat(before).hasSize(4);
        List<@Nullable String> history = query(database, HISTORY);

        migrate(database, context);

        assertThat(query(database, "SELECT (to_jsonb(t)-'measurement_verification')::text" + HISTORICAL_ROWS))
                .isEqualTo(before);
        assertThat(query(database, "SELECT measurement_verification" + HISTORICAL_ROWS))
                .containsExactly("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        assertThat(query(database, HISTORY).subList(0, history.size())).isEqualTo(history);
        assertThat(query(database, """
            SELECT data_type || ':' || character_maximum_length || ':' || is_nullable
            FROM information_schema.columns
            WHERE table_schema='public' AND table_name='llm_usage_event'
              AND column_name='measurement_verification'
            """)).containsExactly("character varying:16:NO");
        assertThat(query(database, """
            SELECT convalidated::text FROM pg_constraint
            WHERE conrelid='public.llm_usage_event'::regclass
              AND conname='ck_llm_usage_event_measurement_verification' AND contype='c'
            """)).containsExactly("true");

        execute(database, """
            INSERT INTO llm_usage_event
                (id, workspace_id, job_type, source_type, source_id, model, occurred_at)
            VALUES (gen_random_uuid(),996101,'PULL_REQUEST_REVIEW','AGENT_JOB',
                    gen_random_uuid(),'default-verification',now())
            """);
        assertThat(query(database, """
            SELECT measurement_verification FROM llm_usage_event
            WHERE workspace_id=996101 AND model='default-verification'
            """)).containsExactly("UNKNOWN");

        UUID verifiedSource = UUID.randomUUID();
        UUID unverifiableSource = UUID.randomUUID();
        insert(database, 996101, verifiedSource, 0, "VERIFIED");
        insert(database, 996101, unverifiableSource, 0, "UNVERIFIABLE");
        assertThat(query(database, """
            SELECT measurement_verification || ':' || input_tokens || ':' || output_tokens
            FROM llm_usage_event WHERE workspace_id=996101 AND model='fresh-verification'
            ORDER BY measurement_verification
            """)).containsExactly("UNVERIFIABLE:100:50", "VERIFIED:100:50");

        assertThatThrownBy(() -> insert(database, 996101, UUID.randomUUID(), 0, "NOT_A_DECISION"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> insert(database, 996101, UUID.randomUUID(), 0, null))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23502"));
        // The source key is global across workspaces, but a distinct attempt must still append.
        assertThatThrownBy(() -> insert(database, 996102, verifiedSource, 0, "UNVERIFIABLE"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23505"));
        insert(database, 996102, verifiedSource, 1, "VERIFIED");
        assertThat(query(
                        database,
                        "SELECT workspace_id || ':' || source_attempt FROM llm_usage_event WHERE source_id='"
                                + verifiedSource + "' ORDER BY source_attempt"))
                .containsExactly("996101:0", "996102:1");
        assertThatThrownBy(() -> insert(database, Long.MAX_VALUE, UUID.randomUUID(), 0, "VERIFIED"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

        List<@Nullable String> migratedHistory = query(database, HISTORY);
        List<@Nullable String> migratedRows = query(
                database,
                "SELECT to_jsonb(t)::text FROM llm_usage_event t WHERE workspace_id IN (996101,996102) ORDER BY id");
        migrate(database, context);
        assertThat(query(database, HISTORY)).isEqualTo(migratedHistory);
        assertThat(query(
                        database,
                        "SELECT to_jsonb(t)::text FROM llm_usage_event t WHERE workspace_id IN (996101,996102)"
                                + " ORDER BY id"))
                .isEqualTo(migratedRows);
    }

    private static void seed(TestDatabase database) throws SQLException {
        execute(database, """
            INSERT INTO workspace(id,account_login,account_type,display_name,slug,status)
            VALUES (996101,'usage-upgrade-one','ORG','Usage upgrade one','usage-upgrade-one','ACTIVE'),
                   (996102,'usage-upgrade-two','ORG','Usage upgrade two','usage-upgrade-two','ACTIVE')
            """, """
            INSERT INTO llm_usage_event
                (id,workspace_id,job_type,source_type,source_id,source_attempt,model,
                 input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,total_calls,
                 cost_usd,pricing_state,funding_source,applied_price_id,applied_workspace_model_id,
                 applied_per_1m_input_usd,applied_per_1m_output_usd,
                 applied_per_1m_cache_read_usd,applied_per_1m_cache_write_usd,occurred_at,usage_provenance)
            VALUES
                ('00000000-0000-0000-0000-000000996201',996101,'PULL_REQUEST_REVIEW','AGENT_JOB',
                 '00000000-0000-0000-0000-000000996301',0,'historical-priced',
                 1000000,250000,400,100,50,3,5.250520,'PRICED','INSTANCE',42,NULL,
                 3,9,0.3,4,'2026-10-01T12:00:00Z',NULL),
                ('00000000-0000-0000-0000-000000996202',996101,'PULL_REQUEST_REVIEW','AGENT_JOB',
                 '00000000-0000-0000-0000-000000996301',1,'historical-unpriced',
                 300,20,50,10,7,2,NULL,'UNPRICED','WORKSPACE',NULL,84,
                 1.1,NULL,0.03,0.04,'2026-10-02T12:00:00Z','RUNNER'),
                ('00000000-0000-0000-0000-000000996203',996102,'MENTOR_TURN','MENTOR_TURN',
                 '00000000-0000-0000-0000-000000996302',0,'historical-free',
                 120,30,0,0,10,1,0,'NO_CHARGE','INSTANCE',43,NULL,
                 NULL,NULL,NULL,NULL,'2026-10-03T12:00:00Z','PROXY'),
                ('00000000-0000-0000-0000-000000996204',996102,'MENTOR_TURN','MENTOR_TURN',
                 '00000000-0000-0000-0000-000000996303',2,'historical-zero',
                 0,0,0,0,0,2,NULL,'UNPRICED','WORKSPACE',NULL,NULL,
                 NULL,NULL,NULL,NULL,'2026-10-04T12:00:00Z','NONE')
            """);
    }

    private static void insert(
            TestDatabase database, long workspaceId, UUID sourceId, int attempt, @Nullable String verification)
            throws SQLException {
        try (Connection connection = connect(database);
                var statement = connection.prepareStatement("""
                    INSERT INTO llm_usage_event
                        (id,workspace_id,job_type,source_type,source_id,source_attempt,model,
                         input_tokens,output_tokens,occurred_at,measurement_verification)
                    VALUES (gen_random_uuid(),?,'PULL_REQUEST_REVIEW','AGENT_JOB',?,?,'fresh-verification',
                            100,50,now(),?)
                    """)) {
            statement.setLong(1, workspaceId);
            statement.setObject(2, sourceId);
            statement.setInt(3, attempt);
            statement.setString(4, verification);
            statement.executeUpdate();
        }
    }

    private static TestDatabase beforeCutover(String context) throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "usage_verification_" + UUID.randomUUID().toString().replace("-", ""));
        try (Liquibase liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int index = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(CUTOVER))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Usage verification migration is absent from master"));
            liquibase.update(index, new Contexts(context), new LabelExpression());
        }
        assertThat(query(database, """
            SELECT count(*)::text FROM information_schema.columns
            WHERE table_schema='public' AND table_name='llm_usage_event'
              AND column_name='measurement_verification'
            """)).containsExactly("0");
        return database;
    }

    private static void migrate(TestDatabase database, String context) throws Exception {
        try (Liquibase liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int count = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith("1791486276211_changelog.xml"))
                    .findFirst()
                    .orElse(pending.size());
            if (count > 0) liquibase.update(count, new Contexts(context), new LabelExpression());
        }
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect(database))));
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static void execute(TestDatabase database, String... statements) throws SQLException {
        try (Connection connection = connect(database);
                var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static List<@Nullable String> query(TestDatabase database, String sql) throws SQLException {
        try (Connection connection = connect(database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            List<@Nullable String> result = new ArrayList<>();
            while (rows.next()) result.add(rows.getString(1));
            return result;
        }
    }
}
