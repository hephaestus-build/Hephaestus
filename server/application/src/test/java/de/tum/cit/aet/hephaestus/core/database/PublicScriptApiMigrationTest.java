package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.regex.Pattern;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** A practice with the exact text of a bundled script moves to the public API, and nothing else changes. */
@Tag("database")
class PublicScriptApiMigrationTest {
    private static final String CHANGELOG = "practice-scripts-public-api.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final Pattern FIRST_ROW = Pattern.compile("INSERT INTO _cutover_program VALUES\\('[a-f0-9]{64}',"
            + "convert_from\\(decode\\('(?<source>[A-Za-z0-9+/=]+)','base64'\\),'UTF8'\\),'[a-f0-9]{64}',"
            + "convert_from\\(decode\\('(?<target>[A-Za-z0-9+/=]+)','base64'\\),'UTF8'\\)\\);");
    private static final String OWN_SCRIPT = "import x from \"../lib/core/practice-script.ts\"; // my own script";

    @Test
    void shouldRewriteOnlyExactBundledTextIntoANewVersionWithoutStartingAReview() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("public_script_api");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(before, CONTEXTS, LABELS);

            var dataSource = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
            new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                    .execute(dataSource);
            var sql = new JdbcTemplate(dataSource);
            var resource = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/changelog/next/*" + CHANGELOG)[0];
            var row = FIRST_ROW.matcher(resource.getContentAsString(StandardCharsets.UTF_8));
            assertThat(row.find()).isTrue();
            String bundled = decode(row.group("source"));
            String rewritten = decode(row.group("target"));

            // 978004 holds bundled text; a copy of it holds an author's own; a third holds no script.
            sql.update("""
                    INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                    SELECT 978100,workspace_id,practice_id,2,jsonb_set(definition,'{script}',to_jsonb(?::text))
                    FROM practice_version WHERE id=978005""", bundled);
            sql.update("UPDATE practice SET current_version_id=978100 WHERE id=978004");
            // A practice and its first version refer to each other, so one statement writes both.
            sql.update("""
                    WITH practices AS(
                      INSERT INTO practice(id,workspace_id,slug,kind,current_version_id,feedback_mode)
                      VALUES(978101,978001,'own-script','scm.pull_request',978102,'AUTOMATIC'),
                            (978103,978001,'no-script','scm.pull_request',978104,'AUTOMATIC'))
                    INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                    SELECT 978102,workspace_id,978101,1,jsonb_set(definition,'{script}',to_jsonb(?::text))
                    FROM practice_version WHERE id=978005
                    UNION ALL
                    SELECT 978104,workspace_id,978103,1,definition FROM practice_version WHERE id=978005""", OWN_SCRIPT);
            // The base and the catalog hold the same texts, so that the rewrite is not offered as a catalog update.
            sql.update("""
                    UPDATE practice SET catalog_slug='bundled-script',
                      catalog_base=jsonb_build_object('definition',jsonb_build_object('script',?::text))
                    WHERE id=978004""", bundled);
            sql.update("""
                    INSERT INTO catalog_entry(entry_type,slug,kind,definition,base)
                    VALUES('PRACTICE','bundled-script','scm.pull_request',
                           jsonb_build_object('definition',jsonb_build_object('script',?::text)),
                           jsonb_build_object('definition',jsonb_build_object('script',?::text))),
                          ('PRACTICE','own-script','scm.pull_request',
                           jsonb_build_object('definition',jsonb_build_object('script',?::text)),
                           jsonb_build_object('definition',jsonb_build_object('script',?::text)))""", bundled, bundled, OWN_SCRIPT, OWN_SCRIPT);
            int reviewsBefore = count(sql, "review");
            int jobsBefore = count(sql, "review_job");

            liquibase.update(1, CONTEXTS, LABELS);

            assertThat(current(sql, 978004)).isEqualTo(rewritten);
            assertThat(sql.queryForObject(
                            "SELECT definition->>'script' FROM practice_version WHERE id=978100", String.class))
                    .as("the earlier version keeps its text")
                    .isEqualTo(bundled);
            assertThat(sql.queryForObject(
                            "SELECT v.number FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=978004",
                            Integer.class))
                    .isEqualTo(3);
            assertThat(current(sql, 978101)).as("an author's own text").isEqualTo(OWN_SCRIPT);
            assertThat(sql.queryForObject("SELECT current_version_id FROM practice WHERE id=978101", Long.class))
                    .isEqualTo(978102L);
            assertThat(current(sql, 978103)).isNull();
            assertThat(sql.queryForObject("SELECT current_version_id FROM practice WHERE id=978103", Long.class))
                    .isEqualTo(978104L);
            assertThat(sql.queryForObject(
                            "SELECT catalog_base->'definition'->>'script' FROM practice WHERE id=978004", String.class))
                    .isEqualTo(rewritten);
            assertThat(catalog(sql, "definition", "bundled-script")).isEqualTo(rewritten);
            assertThat(catalog(sql, "base", "bundled-script")).isEqualTo(rewritten);
            assertThat(catalog(sql, "definition", "own-script")).isEqualTo(OWN_SCRIPT);
            assertThat(catalog(sql, "base", "own-script")).isEqualTo(OWN_SCRIPT);
            assertThat(count(sql, "review")).as("no review starts").isEqualTo(reviewsBefore);
            assertThat(count(sql, "review_job")).as("no job starts").isEqualTo(jobsBefore);
        }
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static @Nullable String current(JdbcTemplate sql, long practice) {
        return sql.queryForObject(
                "SELECT v.definition->>'script' FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=?",
                String.class,
                practice);
    }

    private static @Nullable String catalog(JdbcTemplate sql, String column, String slug) {
        return sql.queryForObject(
                "SELECT " + column
                        + "->'definition'->>'script' FROM catalog_entry WHERE entry_type='PRACTICE' AND slug=?",
                String.class,
                slug);
    }

    private static int count(JdbcTemplate sql, String table) {
        Integer rows = sql.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return rows == null ? 0 : rows;
    }

    private static Connection connect(TestDatabase database) throws Exception {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        return new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
    }
}
