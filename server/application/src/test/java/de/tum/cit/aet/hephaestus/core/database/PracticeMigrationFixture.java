package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

final class PracticeMigrationFixture {
    private static final String BRANCH_FILE = "1791486276211_changelog.xml";

    private PracticeMigrationFixture() {}

    private static final Map<String, TestDatabase> NATIVE_TEMPLATES = new HashMap<>();

    static synchronized TestDatabase migratedDatabase(String context) throws Exception {
        var template = NATIVE_TEMPLATES.get(context);
        if (template == null) {
            template = releasedDatabase(context);
            upgrade(template, context);
            NATIVE_TEMPLATES.put(context, template);
        }
        return PostgreSQLTestContainer.copyDatabase(
                "native_model_" + UUID.randomUUID().toString().replace("-", ""), template);
    }

    static TestDatabase releasedDatabase(String context) throws Exception {
        var database = PostgreSQLTestContainer.createReleasedDatabase(
                "practice_v1_" + context + "_" + UUID.randomUUID().toString().replace("-", ""), context);
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM databasechangelog WHERE filename LIKE '%" + BRANCH_FILE + "'"))
                .containsExactly("0");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND"
                                + " table_name='observation'"))
                .containsExactly("1");
        try (var input = Objects.requireNonNull(
                PracticeMigrationFixture.class.getResourceAsStream("/db/practice-cutover/released-seed.sql"))) {
            execute(database, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        return database;
    }

    static void seedDuplicateHistory(TestDatabase database) throws Exception {
        try (var input = Objects.requireNonNull(
                PracticeMigrationFixture.class.getResourceAsStream("/db/practice-cutover/duplicate-history.sql"))) {
            execute(database, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static void seedBundledPractice(
            Connection connection, long practiceId, long versionId, JsonNode entry, @Nullable String customizedCriteria)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO practice(id,workspace_id,slug,name,criteria,applies_to,subject,signals,
                evidence_requirements,review_when,automated_review_policy,delivery_behavior,precondition,
                precompute_script,why_it_matters,what_good_looks_like,visual,guide,
                source_curated_slug,source_curated_fingerprint,created_at,updated_at)
            SELECT ?,992101,?,d->>'name',coalesce(?,d->>'criteria'),?,d->>'subject',d->'signals',
                d->'evidenceRequirements',d->'reviewWhen',d->'automatedReviewPolicy',d->'deliveryBehavior',nullif(d->'precondition','null'::jsonb),
                d->>'precomputeScript',d->>'whyItMatters',d->>'whatGoodLooksLike',nullif(d->'visual','null'::jsonb),nullif(d->'guide','null'::jsonb),?,?,
                '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z' FROM (SELECT ?::jsonb AS d) authored
            """)) {
            statement.setLong(1, practiceId);
            statement.setString(
                    2,
                    (customizedCriteria == null ? "bundle-" : "custom-")
                            + entry.path("slug").asString());
            statement.setString(3, customizedCriteria);
            statement.setString(4, entry.path("kind").asString());
            statement.setString(5, entry.path("slug").asString());
            statement.setString(6, entry.path("oldReviewFingerprint").asString());
            statement.setString(7, entry.path("oldDefinition").toString());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
            INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,
                precondition,precompute_script,why_it_matters,what_good_looks_like,visual,guide,created_at)
            SELECT ?,id,1,slug,name,applies_to,subject,signals,evidence_requirements,review_when,criteria,
                automated_review_policy,delivery_behavior,precondition,precompute_script,why_it_matters,
                what_good_looks_like,visual,guide,'2026-01-01T00:00:00Z' FROM practice WHERE id=?
            """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, practiceId);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("UPDATE practice SET current_revision_id=? WHERE id=?")) {
            statement.setLong(1, versionId);
            statement.setLong(2, practiceId);
            statement.executeUpdate();
        }
    }

    record ProgramMapping(String sourceSha256, String source, String targetSha256, String target) {}

    static Map<String, ProgramMapping> mappedPrograms() throws Exception {
        final String migration;
        try (var input = Objects.requireNonNull(
                PracticeMigrationFixture.class.getResourceAsStream("/db/changelog/" + BRANCH_FILE))) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var literal = Pattern.compile("INSERT INTO _cutover_program VALUES\\('([a-f0-9]{64})',"
                + "convert_from\\(decode\\('([A-Za-z0-9+/=]+)','base64'\\),'UTF8'\\),"
                + "'([a-f0-9]{64})',convert_from\\(decode\\('([A-Za-z0-9+/=]+)','base64'\\),'UTF8'\\)\\);");
        var programs = new HashMap<String, ProgramMapping>();
        var matcher = literal.matcher(migration);
        while (matcher.find()) {
            var source = Base64.getDecoder().decode(matcher.group(2));
            var target = Base64.getDecoder().decode(matcher.group(4));
            var digest = MessageDigest.getInstance("SHA-256");
            assertThat(HexFormat.of().formatHex(digest.digest(source))).isEqualTo(matcher.group(1));
            assertThat(HexFormat.of().formatHex(digest.digest(target))).isEqualTo(matcher.group(3));
            var mapping = new ProgramMapping(
                    matcher.group(1),
                    new String(source, StandardCharsets.UTF_8),
                    matcher.group(3),
                    new String(target, StandardCharsets.UTF_8));
            assertThat(programs.put(mapping.sourceSha256(), mapping)).isNull();
        }
        assertThat(programs).isNotEmpty();
        return programs;
    }

    static void seedMappedPrograms(TestDatabase database, List<ProgramMapping> programs) throws SQLException {
        long first = 1600000L;
        long last = first + programs.size() - 1;
        try (var connection = dataSource(database).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                WITH supplied AS (SELECT ?::bigint AS id,?::text AS slug,?::text AS source)
                INSERT INTO practice(id,workspace_id,slug,name,criteria,applies_to,subject,signals,
                    evidence_requirements,review_when,automated_review_policy,delivery_behavior,precompute_script,
                    source_curated_slug,source_curated_fingerprint,adopted_base,adopted_base_source,created_at,updated_at)
                SELECT s.id,p.workspace_id,s.slug,'Synthetic workspace practice',
                    E'Workspace customized criterion.\\n  Keep exact whitespace.\\n',
                    p.applies_to,p.subject,p.signals,p.evidence_requirements,p.review_when,
                    p.automated_review_policy,p.delivery_behavior,s.source,s.slug,'v5:'||repeat('a',64),
                    jsonb_build_object('name','Synthetic workspace base','signals',p.signals,'reviewWhen',p.review_when,
                        'subject',p.subject,'precondition',p.precondition,'evidenceRequirements',p.evidence_requirements,
                        'automatedReviewPolicy',p.automated_review_policy,'deliveryBehavior',p.delivery_behavior,
                        'criteria',E'Workspace comparison criterion.\\n  Keep exact whitespace.\\n',
                        'precomputeScript',s.source,'whyItMatters',NULL,'whatGoodLooksLike',NULL,
                        'groupSlug',NULL,'visual',NULL,'guide',NULL),
                    'EXACT_ADOPTION','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'
                FROM practice p CROSS JOIN supplied s WHERE p.id=992121
                """)) {
                for (int index = 0; index < programs.size(); index++) {
                    statement.setLong(1, first + index);
                    statement.setString(2, "synthetic-mapping-" + index);
                    statement.setString(3, programs.get(index).source());
                    statement.addBatch();
                }
                assertThat(statement.executeBatch()).hasSize(programs.size());
            }
            try (var statement = connection.prepareStatement("""
                INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,subject,
                    signals,evidence_requirements,review_when,criteria,automated_review_policy,delivery_behavior,
                    precompute_script,created_at)
                SELECT id+100000,id,1,slug,name,applies_to,subject,signals,evidence_requirements,review_when,
                    criteria,automated_review_policy,delivery_behavior,precompute_script,'2026-01-01T00:00:00Z'
                FROM practice WHERE id BETWEEN ? AND ?
                """)) {
                statement.setLong(1, first);
                statement.setLong(2, last);
                assertThat(statement.executeUpdate()).isEqualTo(programs.size());
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE practice SET current_revision_id=id+100000 WHERE id BETWEEN ? AND ?")) {
                statement.setLong(1, first);
                statement.setLong(2, last);
                assertThat(statement.executeUpdate()).isEqualTo(programs.size());
            }
            try (var statement = connection.prepareStatement("""
                INSERT INTO curated_practice_override(slug,name,applies_to,subject,signals,evidence_requirements,
                    review_when,criteria,automated_review_policy,delivery_behavior,precompute_script,
                    adopted_base,adopted_base_source,position,version,created_at,updated_at)
                SELECT source_curated_slug,'Synthetic instance practice',applies_to,subject,signals,
                    evidence_requirements,review_when,E'Instance customized criterion.\\n  Keep exact whitespace.\\n',
                    automated_review_policy,delivery_behavior,precompute_script,
                    jsonb_set(adopted_base,'{criteria}',to_jsonb(E'Instance comparison criterion.\\n  Keep exact whitespace.\\n'::text)),
                    'EXACT_ADOPTION',id-?,1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'
                FROM practice WHERE id BETWEEN ? AND ?
                """)) {
                statement.setLong(1, first);
                statement.setLong(2, first);
                statement.setLong(3, last);
                assertThat(statement.executeUpdate()).isEqualTo(programs.size());
            }
            connection.commit();
        }
    }

    static void upgrade(TestDatabase database, String context) throws Exception {
        try (var connection = dataSource(database).getConnection();
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            // These historical fixtures stop before 0.95.0; the entry guard has its own integration test.
            liquibase
                    .getDatabaseChangeLog()
                    .getPreconditions()
                    .getNestedPreconditions()
                    .clear();
            liquibase.update(new Contexts(context), new LabelExpression());
        }
    }

    /** Applies every pending changeset that comes before the first one of {@code changelog}. */
    static void upgradeBefore(TestDatabase database, String context, String changelog) throws Exception {
        try (var connection = dataSource(database).getConnection();
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase
                    .getDatabaseChangeLog()
                    .getPreconditions()
                    .getNestedPreconditions()
                    .clear();
            var contexts = new Contexts(context);
            var labels = new LabelExpression();
            var pending = liquibase.listUnrunChangeSets(contexts, labels);
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(changelog))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(changelog + " has no pending changeset"));
            liquibase.update(before, contexts, labels);
        }
    }

    private static DriverManagerDataSource dataSource(TestDatabase database) {
        return new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
    }

    static void execute(TestDatabase database, String sql) {
        new JdbcTemplate(dataSource(database)).execute(sql);
    }

    static List<@Nullable String> column(TestDatabase database, String sql) {
        return JdbcClient.create(dataSource(database))
                .sql(sql)
                .query((RowMapper<@Nullable String>) (rows, index) -> rows.getString(1))
                .list();
    }
}
