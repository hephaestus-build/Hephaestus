package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class NativeHibernateJsonCutoverMigrationTest {
    private static final String CUTOVER = "1791931753089_changelog.xml";
    private static final String SNAPSHOT = " { \"hash\" : \"sha256:abc\", \"text\" : \"雪\\\\path\" } \n";
    private static final List<Shape> SHAPES = List.of(
            new Shape(null, null),
            new Shape("null", null),
            new Shape("\"null\"", "null"),
            new Shape(JsonMapper.builder().build().writeValueAsString(SNAPSHOT), SNAPSHOT),
            new Shape("\"Connection paused.\"", "Connection paused."),
            new Shape("{\"hash\":\"sha256:abc\"}", "{\"hash\": \"sha256:abc\"}"),
            new Shape("[1,\"雪\",null]", "[1, \"雪\", null]"),
            new Shape("42", "42"),
            new Shape("true", "true"),
            new Shape("\"\"", ""));

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldPreservePopulatedValuesMetadataAndHistoryWhenCutoverRuns(String context) throws Exception {
        TestDatabase database = beforeCutover(context);
        seed(database);
        List<String> metadata = metadata(database);
        List<@Nullable String> history = query(
                database,
                "SELECT id || ':' || author || ':' || filename || ':' || md5sum FROM databasechangelog ORDER BY"
                        + " orderexecuted");
        var store = exportStore(database);
        var selectedScope = new PersonScope(null, List.of(), List.of(995101L));
        var selected = store.select(selectedScope);
        var beforeExport = store.export(selected, selectedScope);
        var configStore = new JdbcPersonDataStore(
                new NamedParameterJdbcTemplate(
                        new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password())),
                JsonMapper.builder().build(),
                "config-cutover",
                "config_audit_event",
                "t.entity_id = 'hash:sha256:abc'",
                "id,old_value,new_value",
                "id",
                "old_value=NULL,new_value=NULL",
                0);
        var configSelectedScope = new PersonScope(null, List.of(), List.of());
        var configSelected = configStore.select(configSelectedScope);
        var beforeConfigExport = configStore.export(configSelected, configSelectedScope);
        assertThat(beforeConfigExport.get(5).path("old_value").isObject()).isTrue();
        assertThat(beforeConfigExport.get(5).path("new_value").isObject()).isTrue();
        assertThat(beforeExport.get(5).path("detail").isObject()).isTrue();

        try (Liquibase liquibase = liquibase(database)) {
            assertThat(liquibase
                            .listUnrunChangeSets(new Contexts(context), new LabelExpression())
                            .getFirst()
                            .getFilePath())
                    .endsWith(CUTOVER);
            liquibase.update(1, new Contexts(context), new LabelExpression());
            assertThat(query(database, "SELECT filename FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1")
                            .getFirst())
                    .endsWith(CUTOVER);
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(context), new LabelExpression()))
                    .hasStackTraceContaining("restore the verified database backup");
        }
        migrate(database, context);
        assertThat(metadata(database)).isEqualTo(metadata);
        assertThat(query(
                                database,
                                "SELECT id || ':' || author || ':' || filename || ':' || md5sum FROM databasechangelog"
                                        + " ORDER BY orderexecuted")
                        .subList(0, history.size()))
                .isEqualTo(history);
        for (String projection : List.of(
                "SELECT details FROM auth_event WHERE id BETWEEN 995201 AND 995210 ORDER BY id",
                "SELECT old_value FROM config_audit_event WHERE id BETWEEN 995201 AND 995210 ORDER BY id",
                "SELECT new_value FROM config_audit_event WHERE id BETWEEN 995201 AND 995210 ORDER BY id",
                "SELECT options FROM project_field WHERE project_id=995102 ORDER BY id",
                "SELECT detail FROM connection_audit WHERE connection_id=995101 ORDER BY id")) {
            assertThat(query(database, projection))
                    .isEqualTo(SHAPES.stream().map(Shape::text).toList());
        }
        assertThat(query(database, "SELECT details FROM auth_event WHERE id=995300"))
                .containsExactly("Retained old prose");
        assertThat(query(
                        database,
                        "SELECT (tableoid <> 'public.auth_event_default'::regclass)::text FROM auth_event"
                                + " WHERE id=995204"))
                .containsExactly("true");
        var afterExport = store.export(selected, selectedScope);
        assertThat(afterExport.get(5).path("detail").isString()).isTrue();
        assertThat(afterExport.get(5).path("detail").asString())
                .isEqualTo(SHAPES.get(5).text());
        assertThat(afterExport.get(3).path("detail"))
                .isEqualTo(beforeExport.get(3).path("detail"));
        assertThat(afterExport.get(1).path("detail").isNull()).isTrue();
        assertThat(afterExport.get(2).path("detail").asString()).isEqualTo("null");
        var afterConfigExport = configStore.export(configSelected, configSelectedScope);
        for (String column : List.of("old_value", "new_value")) {
            assertThat(afterConfigExport.get(5).path(column).isString()).isTrue();
            assertThat(afterConfigExport.get(5).path(column).asString())
                    .isEqualTo(SHAPES.get(5).text());
            assertThat(afterConfigExport.get(3).path(column))
                    .isEqualTo(beforeConfigExport.get(3).path(column));
        }
        assertPartitionTypes(database);
        execute(
                database,
                "SELECT partman.create_partition_time('public.auth_event', ARRAY['2101-01-01'::timestamptz])",
                "INSERT INTO auth_event(id,occurred_at,event_type,result,details)"
                        + " VALUES(995301,'2101-01-02','LOGIN','SUCCESS','Future partition prose')");
        assertThat(query(
                        database,
                        "SELECT (tableoid <> 'public.auth_event_default'::regclass)::text || ':' || details"
                                + " FROM auth_event WHERE id=995301"))
                .containsExactly("true:Future partition prose");
        assertPartitionTypes(database);
        if (context.equals("prod")) assertGuards(database);
        List<@Nullable String> migratedHistory =
                query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        List<String> migratedRows = metadata(database);
        migrate(database, context);
        assertThat(query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(migratedHistory);
        assertThat(metadata(database)).isEqualTo(migratedRows);
        int appliedSinceBoundary = migratedHistory.size() - history.size();
        assertThat(appliedSinceBoundary).isPositive();
        try (Liquibase liquibase = liquibase(database)) {
            assertThatThrownBy(() ->
                            liquibase.rollback(appliedSinceBoundary, new Contexts(context), new LabelExpression()))
                    .hasStackTraceContaining("verified");
        }
        assertPartitionTypes(database);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldInitializeFreshDatabasesAndLeaveMigrationHistoryStable(String context) throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "native_json_fresh_" + UUID.randomUUID().toString().replace("-", ""));
        migrate(database, context);
        assertThat(
                        query(
                                database,
                                "SELECT count(*)::text FROM information_schema.columns WHERE table_schema='public' AND"
                                        + " data_type='text' AND (table_name,column_name) IN"
                                        + " (('auth_event','details'),('config_audit_event','old_value'),('config_audit_event','new_value'),('project_field','options'),('connection_audit','detail'))"))
                .containsExactly("5");
        List<@Nullable String> history =
                query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        migrate(database, context);
        assertThat(query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
    }

    @Test
    void shouldRefuseIncompatibleSourceAndTemplateStatesWithoutPartialConversion() throws Exception {
        TestDatabase database = beforeCutover("prod");
        execute(database, "ALTER TABLE project_field ALTER COLUMN options TYPE text USING options::text");
        assertThatThrownBy(() -> migrate(database, "prod")).hasStackTraceContaining("requires all five source columns");
        assertThat(query(
                        database,
                        "SELECT atttypid::regtype::text FROM pg_attribute WHERE"
                                + " attrelid='auth_event'::regclass AND attname='details'"))
                .containsExactly("jsonb");
        execute(database, "ALTER TABLE project_field ALTER COLUMN options TYPE jsonb USING options::jsonb");
        String template = Objects.requireNonNull(
                query(database, "SELECT template_table FROM partman.part_config WHERE parent_table='public.auth_event'")
                        .getFirst());
        // regclass text is database-owned, not an input from a user.
        execute(database, "ALTER TABLE " + template + " ALTER COLUMN details TYPE text USING details::text");
        assertThatThrownBy(() -> migrate(database, "prod"))
                .hasStackTraceContaining("standalone jsonb auth_event template");
        assertThat(query(
                        database,
                        "SELECT atttypid::regtype::text FROM pg_attribute WHERE"
                                + " attrelid='config_audit_event'::regclass AND attname='old_value'"))
                .containsExactly("jsonb");
        execute(
                database,
                "ALTER TABLE " + template + " ALTER COLUMN details TYPE jsonb USING details::jsonb",
                "UPDATE partman.part_config SET template_table='public.missing_cutover_template' WHERE"
                        + " parent_table='public.auth_event'");
        assertThatThrownBy(() -> migrate(database, "prod"))
                .hasStackTraceContaining("standalone jsonb auth_event template");
        execute(
                database,
                "UPDATE partman.part_config SET template_table='" + template
                        + "' WHERE parent_table='public.auth_event'",
                "ALTER TABLE auth_event DETACH PARTITION auth_event_default");
        assertThatThrownBy(() -> migrate(database, "prod"))
                .hasStackTraceContaining("retain its native partition layout");
        execute(
                database,
                "ALTER TABLE auth_event ATTACH PARTITION auth_event_default DEFAULT",
                "DELETE FROM partman.part_config WHERE parent_table='public.auth_event'");
        assertThatThrownBy(() -> migrate(database, "prod"))
                .hasStackTraceContaining("one configured auth_event template");
        assertThat(query(
                        database,
                        "SELECT count(*)::text FROM databasechangelog WHERE filename LIKE '%" + CUTOVER + "'"))
                .containsExactly("0");
    }

    private static void assertGuards(TestDatabase database) throws SQLException {
        for (String sql : List.of(
                "UPDATE auth_event SET result='FAILURE' WHERE id=995204",
                "UPDATE auth_event SET result='FAILURE' WHERE id=995201",
                "UPDATE auth_event SET details='rewritten' WHERE id=995301",
                "DELETE FROM auth_event WHERE id=995204",
                "UPDATE config_audit_event SET action='CREATED' WHERE id=995204",
                "UPDATE config_audit_event SET old_value='rewritten' WHERE id=995204",
                "DELETE FROM config_audit_event WHERE id=995204",
                "TRUNCATE config_audit_event")) {
            assertThatThrownBy(() -> execute(database, sql))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }
        execute(
                database,
                "UPDATE auth_event SET ip_inet=NULL,user_agent=NULL,details=NULL WHERE id=995204",
                "UPDATE config_audit_event SET old_value=NULL,new_value=NULL WHERE id=995204",
                "DELETE FROM config_audit_event WHERE id=995201");
        assertThat(query(
                        database,
                        "SELECT (details IS NULL AND ip_inet IS NULL AND user_agent IS NULL)::text FROM"
                                + " auth_event WHERE id=995204"))
                .containsExactly("true");
        assertThat(query(
                        database,
                        "SELECT (old_value IS NULL AND new_value IS NULL)::text FROM config_audit_event WHERE"
                                + " id=995204"))
                .containsExactly("true");
        assertThat(query(database, "SELECT count(*)::text FROM config_audit_event WHERE id=995201"))
                .containsExactly("0");
        // Partition retention removes the table, without issuing forbidden row DELETEs.
        String expiredPartition = Objects.requireNonNull(
                query(database, "SELECT tableoid::regclass::text FROM auth_event WHERE id=995300")
                        .getFirst());
        execute(
                database,
                "ALTER TABLE auth_event DETACH PARTITION " + expiredPartition,
                "DROP TABLE " + expiredPartition);
        assertThat(query(database, "SELECT count(*)::text FROM auth_event WHERE id=995300"))
                .containsExactly("0");
    }

    private static void assertPartitionTypes(TestDatabase database) throws SQLException {
        assertThat(query(
                        database,
                        "SELECT DISTINCT a.atttypid::regtype::text FROM pg_partition_tree('public.auth_event')"
                                + " p JOIN pg_attribute a ON a.attrelid=p.relid WHERE a.attname='details'"))
                .containsExactly("text");
        assertThat(query(
                        database,
                        "SELECT a.atttypid::regtype::text FROM partman.part_config p JOIN pg_attribute a ON"
                                + " a.attrelid=to_regclass(p.template_table) WHERE"
                                + " p.parent_table='public.auth_event' AND a.attname='details'"))
                .containsExactly("text");
        assertThat(query(
                        database,
                        "SELECT count(*)::text FROM auth_event WHERE id=995201 AND"
                                + " tableoid='public.auth_event_default'::regclass"))
                .containsExactly("1");
    }

    private static void seed(TestDatabase database) throws SQLException {
        execute(
                database,
                "INSERT INTO workspace(id,account_login,account_type,display_name,slug,status)"
                        + " VALUES(995101,'json-cutover','ORG','JSON cutover','json-cutover','ACTIVE')",
                "INSERT INTO connection(id,workspace_id,kind,instance_key,config)"
                        + " VALUES(995101,995101,'GITHUB','cutover','{\"type\":\"GITHUB_PAT\",\"enabledStreams\":[]}')",
                "INSERT INTO project(id,native_id,provider_id,owner_type,owner_id,number,template,closed,is_public)"
                        + " SELECT 995102,995102,id,'ORGANIZATION',995101,1,false,false,false FROM identity_provider WHERE"
                        + " type='GITHUB' AND server_url='https://github.com'",
                "SELECT partman.create_partition_time('public.auth_event', ARRAY['2020-01-01'::timestamptz])",
                "INSERT INTO auth_event(id,occurred_at,event_type,result,details)"
                        + " VALUES(995300,'2020-01-02','LOGIN','SUCCESS','\"Retained old prose\"')");
        try (Connection connection = connect(database)) {
            for (int i = 0; i < SHAPES.size(); i++) {
                long id = 995201L + i;
                for (String sql : List.of(
                        "INSERT INTO auth_event(id,occurred_at,event_type,result,details,ip_inet,user_agent) VALUES(?,"
                                + (i == 0 ? "'2100-01-02'" : "now()")
                                + ",'LOGIN','SUCCESS',?::jsonb,'192.0.2.1','cutover fixture')",
                        "INSERT INTO"
                                + " config_audit_event(id,occurred_at,actor_kind,entity_type,entity_id,action,changed_keys,old_value,new_value)"
                                + " VALUES(?,"
                                + (i == 0 ? "now()-interval '366 days'" : "now()")
                                + ",'SYSTEM','CURATED_PRACTICE','hash:sha256:abc','UPDATED',ARRAY['hash'],?::jsonb,?::jsonb)",
                        "INSERT INTO project_field(id,project_id,name,data_type,options,created_at,updated_at)"
                                + " VALUES(?::text,995102,?::text,'SINGLE_SELECT',?::jsonb,now(),now())",
                        "INSERT INTO connection_audit(id,connection_id,event_type,actor_kind,detail,correlation_id)"
                                + " VALUES(?,995101,'SUSPEND','SYSTEM',?::jsonb,?)")) {
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, id);
                        if (sql.contains("project_field")) {
                            statement.setLong(2, id);
                            statement.setString(3, SHAPES.get(i).json());
                        } else {
                            statement.setString(2, SHAPES.get(i).json());
                            if (sql.contains("config_audit_event"))
                                statement.setString(3, SHAPES.get(i).json());
                            if (sql.contains("connection_audit")) statement.setString(3, "cutover-" + id);
                        }
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    private static List<String> metadata(TestDatabase database) throws SQLException {
        List<String> result = new ArrayList<>();
        for (String sql : List.of(
                "SELECT (to_jsonb(t)-'details')::text FROM auth_event t ORDER BY id",
                "SELECT (to_jsonb(t)-'old_value'-'new_value')::text FROM config_audit_event t ORDER BY id",
                "SELECT (to_jsonb(t)-'options')::text FROM project_field t ORDER BY id",
                "SELECT (to_jsonb(t)-'detail')::text FROM connection_audit t ORDER BY id")) {
            for (var row : query(database, sql)) result.add(Objects.requireNonNull(row));
        }
        return result;
    }

    private static JdbcPersonDataStore exportStore(TestDatabase database) {
        return new JdbcPersonDataStore(
                new NamedParameterJdbcTemplate(
                        new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password())),
                JsonMapper.builder().build(),
                "cutover",
                "connection_audit",
                "t.connection_id = ANY(:users)",
                "id,detail",
                "id",
                "detail=NULL",
                0);
    }

    private static TestDatabase beforeCutover(String context) throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "native_json_" + UUID.randomUUID().toString().replace("-", ""));
        try (Liquibase liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int index = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(CUTOVER))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalStateException("Cutover migration is absent from the native upgrade chain"));
            liquibase.update(index, new Contexts(context), new LabelExpression());
        }
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

    private record Shape(@Nullable String json, @Nullable String text) {}
}
