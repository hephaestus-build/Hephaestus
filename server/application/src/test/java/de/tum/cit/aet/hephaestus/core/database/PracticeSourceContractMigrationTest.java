package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("database")
class PracticeSourceContractMigrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String REVIEW = "00000000-0000-0000-0000-000000978011";
    private static final String CUSTOM_SCRIPT = """
            import { definePracticeScript } from "../lib/core/practice-script.ts";
            export default definePracticeScript({ meta: {}, async run(ctx) {
                return ctx.review({ leads: [{ at: { record: "metadata.json" }, label: "Custom merge facts" }] });
            }});
            """;

    @TempDir
    Path temporary;

    @Test
    void shouldUpgradeQueuedHistoricalPinsAndComparisonBasesWithoutReplacingCustomWork() throws Exception {
        var database = NativePracticeFixture.resultParent("prod");
        var jdbc = jdbc(database);
        var queued = definition(jdbc, 978005);
        queued.withObject("/reads").put("contract", "2.0.0");
        queued.put("criteria", "Keep the queued criterion.\n  Keep its whitespace.\n");
        queued.put("script", CUSTOM_SCRIPT);
        queued.withArray("/reads/sources")
                .add(JSON.readTree("{\"source\":\"scm.pull-request.checks\",\"need\":\"REQUIRED\"}"));
        queued.set("scan", JSON.readTree("""
            {"itemKind":"scm.file","include":["**/*"],"exclude":[],"context":[],
             "reads":{"contract":"2.0.0","sources":[{"source":"scm.repository.tree","need":"COMPLETE"}]}}
            """));
        append(jdbc, 978105, 2, queued);
        jdbc.update(
                "UPDATE review_practice SET practice_version_id=978105 WHERE review_id=?::uuid AND workspace_id=978001",
                REVIEW);
        var latest = queued.deepCopy().put("criteria", "A later workspace edit.");
        append(jdbc, 978106, 3, latest);
        String historyDigest =
                text(jdbc, "SELECT review_digest FROM practice_version WHERE id=978105 AND workspace_id=978001");
        jdbc.update("""
            UPDATE practice SET catalog_slug='contract-fixture',catalog_base=jsonb_build_object('kind',kind,'definition',?::jsonb)
            WHERE id=978004 AND workspace_id=978001
            """, latest.toString());
        jdbc.update("""
            INSERT INTO catalog_entry(entry_type,slug,kind,definition,base)
            SELECT 'PRACTICE','contract-fixture',kind,catalog_base,catalog_base FROM practice WHERE id=978004 AND workspace_id=978001
            """);
        PracticeMigrationFixture.execute(database, """
            BEGIN;
            INSERT INTO practice(id,workspace_id,slug,kind,current_version_id) VALUES(978204,978002,'foreign','slack.thread',978205);
            INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
            SELECT 978205,978002,978204,1,
                jsonb_set(jsonb_set(definition,'{criteria}','"Slack stays Slack"'),'{reads}',
                    '{"contract":"2.0.0","sources":[{"source":"slack.conversation.thread","need":"COMPLETE"}]}')
            FROM practice_version WHERE id=978005 AND workspace_id=978001;
            COMMIT;
            """);

        migrate(database);
        var upgraded = current(jdbc);
        assertThat(upgraded.path("criteria")).isEqualTo(latest.path("criteria"));
        assertThat(upgraded.path("script").asString()).isEqualTo(CUSTOM_SCRIPT);
        assertThat(upgraded.at("/reads/contract").asString()).isEqualTo("3.0.0");
        assertThat(upgraded.at("/scan/reads/contract").asString()).isEqualTo("3.0.0");
        assertThat(upgraded.at("/reads/sources/0")).isEqualTo(queued.at("/reads/sources/0"));
        assertThat(upgraded.at("/reads/sources")).hasSize(3);
        assertThat(upgraded.at("/reads/sources/1")).isEqualTo(queued.at("/reads/sources/1"));
        var resumed = JSON.readTree(text(jdbc, """
            SELECT v.definition::text FROM review_practice rp JOIN practice_version v
              ON (v.id,v.practice_id,v.workspace_id)=(rp.practice_version_id,rp.practice_id,rp.workspace_id)
            WHERE rp.review_id='00000000-0000-0000-0000-000000978011' AND rp.workspace_id=978001 AND rp.practice_id=978004
            """));
        assertThat(resumed.path("criteria")).isEqualTo(queued.path("criteria"));
        assertThat(resumed.path("script").asString()).isEqualTo(CUSTOM_SCRIPT);
        assertThat(resumed.at("/reads/contract").asString()).isEqualTo("3.0.0");
        assertThat(text(jdbc, "SELECT status FROM review WHERE id='" + REVIEW + "' AND workspace_id=978001"))
                .isEqualTo("QUEUED");
        assertThat(definition(jdbc, 978105)).isEqualTo(queued);
        assertThat(definition(jdbc, 978106)).isEqualTo(latest);
        assertThat(text(jdbc, "SELECT review_digest FROM practice_version WHERE id=978105 AND workspace_id=978001"))
                .isEqualTo(historyDigest);
        assertThat(
                        text(
                                jdbc,
                                "SELECT (review_digest=practice_review_digest(definition))::text FROM practice_version WHERE id=(SELECT current_version_id FROM practice WHERE id=978004 AND workspace_id=978001)"))
                .isEqualTo("true");
        assertThat(JSON.readTree(text(
                                jdbc,
                                "SELECT catalog_base::text FROM practice WHERE id=978004 AND workspace_id=978001"))
                        .path("definition"))
                .isEqualTo(upgraded);
        assertThat(JSON.readTree(
                                text(
                                        jdbc,
                                        "SELECT definition::text FROM catalog_entry WHERE entry_type='PRACTICE' AND slug='contract-fixture'"))
                        .path("definition"))
                .isEqualTo(upgraded);
        assertThat(JSON.readTree(
                                text(
                                        jdbc,
                                        "SELECT base::text FROM catalog_entry WHERE entry_type='PRACTICE' AND slug='contract-fixture'"))
                        .path("definition"))
                .isEqualTo(upgraded);
        assertThat(
                        text(
                                jdbc,
                                "SELECT v.definition#>>'{reads,contract}' FROM practice p JOIN practice_version v ON (v.id,v.practice_id,v.workspace_id)=(p.current_version_id,p.id,p.workspace_id) WHERE p.id=978204 AND p.workspace_id=978002"))
                .isEqualTo("3.0.0");
        assertThat(
                        text(
                                jdbc,
                                "SELECT v.definition#>>'{reads,sources,0,source}' FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=978204 AND p.workspace_id=978002"))
                .isEqualTo("slack.conversation.thread");
        String pins = text(
                jdbc,
                "SELECT jsonb_agg(jsonb_build_array(id,review_digest) ORDER BY id)::text FROM practice_version WHERE practice_id IN(978004,978204) AND workspace_id IN(978001,978002)");
        migrate(database);
        assertThat(
                        text(
                                jdbc,
                                "SELECT jsonb_agg(jsonb_build_array(id,review_digest) ORDER BY id)::text FROM practice_version WHERE practice_id IN(978004,978204) AND workspace_id IN(978001,978002)"))
                .isEqualTo(pins);
        runScript("custom-fixture", resumed);
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        var nativeDatabase =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var liquibase = new Liquibase(
                "db/changelog/1793196763354_changelog.xml", new ClassLoaderResourceAccessor(), nativeDatabase)) {
            assertThatThrownBy(() -> liquibase.rollback(1, ""))
                    .hasStackTraceContaining("Restore the verified pre-upgrade backup");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"merges-only-after-approval", "merged-past-unresolved-review-threads"})
    void shouldRunAnUnchangedAdoptedScriptWithCustomizedCriteriaAfterMigration(String slug) throws Exception {
        var database = NativePracticeFixture.resultParent("prod");
        var jdbc = jdbc(database);
        var adopted = definition(jdbc, 978005);
        String script = resource("practices/source-contract-2.0.0/" + slug + ".ts.txt");
        adopted.withObject("/reads").put("contract", "2.0.0");
        adopted.put("script", script);
        adopted.put("criteria", "Keep my customized criterion.\n  Preserve these exact words.\n");
        append(jdbc, 978105, 2, adopted);
        jdbc.update(
                "UPDATE review_practice SET practice_version_id=978105 WHERE review_id=?::uuid AND workspace_id=978001",
                REVIEW);
        migrate(database);
        var upgraded = current(jdbc);
        assertThat(upgraded.path("script").asString()).isEqualTo(script);
        assertThat(upgraded.path("criteria")).isEqualTo(adopted.path("criteria"));
        assertThat(definition(jdbc, 978105)).isEqualTo(adopted);
        runScript(slug, upgraded);
    }

    @Test
    void shouldResumeAnUnfinishedScanWithoutSpendingARetry() throws Exception {
        var database = NativePracticeFixture.resultParent("prod");
        var jdbc = jdbc(database);
        oldScan(jdbc);
        seedScan(database);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO review_job(id,review_id,workspace_id,status,attempt,input,config_snapshot,capture,trace_id,submitted_at,worker_id)
            VALUES('00000000-0000-0000-0000-000000999824','00000000-0000-0000-0000-000000999811',978001,'RUNNING',2,'{}','{}','{}',repeat('f',32),now(),'stopped-worker');
            """);
        migrate(database);
        assertThat(
                        text(
                                jdbc,
                                "SELECT status||'|'||attempt||'|'||(capture IS NULL)||'|'||(worker_id IS NULL) FROM review_job WHERE id='00000000-0000-0000-0000-000000999824' AND workspace_id=978001"))
                .isEqualTo("QUEUED|2|true|true");
        assertThat(
                        text(
                                jdbc,
                                "SELECT v.definition#>>'{scan,reads,contract}' FROM review_practice rp JOIN practice_version v ON (v.id,v.practice_id,v.workspace_id)=(rp.practice_version_id,rp.practice_id,rp.workspace_id) WHERE rp.review_id='00000000-0000-0000-0000-000000999811' AND rp.workspace_id=978001"))
                .isEqualTo("3.0.0");
        assertThat(
                        text(
                                jdbc,
                                "SELECT state FROM review_item WHERE review_id='00000000-0000-0000-0000-000000999811' AND workspace_id=978001 AND item_id=999842"))
                .isEqualTo("LISTED");
    }

    @Test
    void shouldKeepStagedOutputAndRecordAContractStop() throws Exception {
        var database = NativePracticeFixture.resultParent("prod");
        var jdbc = jdbc(database);
        oldScan(jdbc);
        seedScan(database);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO review_job(id,review_id,workspace_id,status,processing_status,input,config_snapshot,output,trace_id,submitted_at)
            VALUES('00000000-0000-0000-0000-000000999825','00000000-0000-0000-0000-000000999811',978001,'COMPLETED','PENDING','{}','{}','{"candidate":"Keep the staged output"}',repeat('f',32),now());
            """);
        String staged = text(
                jdbc,
                "SELECT output::text FROM review_job WHERE id='00000000-0000-0000-0000-000000999825' AND workspace_id=978001");
        migrate(database);
        assertThat(
                        text(
                                jdbc,
                                "SELECT status||'|'||stop_reason FROM review WHERE id='00000000-0000-0000-0000-000000999811' AND workspace_id=978001"))
                .isEqualTo("PARTIAL|CONTRACT_CHANGED");
        assertThat(
                        text(
                                jdbc,
                                "SELECT output::text FROM review_job WHERE id='00000000-0000-0000-0000-000000999825' AND workspace_id=978001"))
                .isEqualTo(staged);
        assertThat(
                        text(
                                jdbc,
                                "SELECT status||'|'||processing_status||'|'||cancellation_reason FROM review_job WHERE id='00000000-0000-0000-0000-000000999825' AND workspace_id=978001"))
                .isEqualTo("COMPLETED|DONE|CONTRACT_CHANGED");
        assertThat(
                        text(
                                jdbc,
                                "SELECT state||'|'||reason FROM review_item WHERE review_id='00000000-0000-0000-0000-000000999811' AND workspace_id=978001 AND item_id=999842"))
                .isEqualTo("WITHHELD|CONTRACT_CHANGED");
    }

    @Test
    void shouldKeepCompletedResultReferencesWhenAPartialScanStopsForTheContractUpgrade() throws Exception {
        var database = NativePracticeFixture.resultParent("prod");
        var jdbc = jdbc(database);
        var scan = oldScan(jdbc);
        seedScan(database);
        PracticeMigrationFixture.execute(database, """
            BEGIN;
            INSERT INTO result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,outcome,coverage,recorded_at)
            VALUES('00000000-0000-0000-0000-000000999851',978001,'00000000-0000-0000-0000-000000999811',978004,978105,999841,'file-a-head','MET','{}',now());
            UPDATE review_item SET state='JUDGED',result_id='00000000-0000-0000-0000-000000999851'
            WHERE review_id='00000000-0000-0000-0000-000000999811' AND workspace_id=978001 AND item_id=999841;
            INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,budget_usd,ordered_at,completed_at)
            VALUES('00000000-0000-0000-0000-000000999813',978001,978008,'SCAN','repository-head','REQUEST','COMPLETE',0.125,now(),now());
            INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status,items_listed,listing_complete)
            VALUES('00000000-0000-0000-0000-000000999813',978001,978004,978105,'DONE',1,true);
            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version,state,result_id)
            VALUES('00000000-0000-0000-0000-000000999813',978001,978004,999841,'file-a-head','REUSED','00000000-0000-0000-0000-000000999851');
            INSERT INTO review_job(id,review_id,workspace_id,status,attempt,input,config_snapshot,capture,trace_id,submitted_at)
            VALUES('00000000-0000-0000-0000-000000999823','00000000-0000-0000-0000-000000999811',978001,'QUEUED',2,'{}','{}','{}',repeat('f',32),now());
            COMMIT;
            """);
        String result = text(
                jdbc,
                "SELECT to_jsonb(r)::text FROM result r WHERE id='00000000-0000-0000-0000-000000999851' AND workspace_id=978001");
        String digest =
                text(jdbc, "SELECT review_digest FROM practice_version WHERE id=978105 AND workspace_id=978001");
        migrate(database);
        assertThat(
                        text(
                                jdbc,
                                "SELECT to_jsonb(r)::text FROM result r WHERE id='00000000-0000-0000-0000-000000999851' AND workspace_id=978001"))
                .isEqualTo(result);
        assertThat(definition(jdbc, 978105)).isEqualTo(scan);
        assertThat(text(jdbc, "SELECT review_digest FROM practice_version WHERE id=978105 AND workspace_id=978001"))
                .isEqualTo(digest);
        assertThat(
                        text(
                                jdbc,
                                "SELECT status||'|'||stop_reason FROM review WHERE id='00000000-0000-0000-0000-000000999811' AND workspace_id=978001"))
                .isEqualTo("PARTIAL|CONTRACT_CHANGED");
        assertThat(
                        text(
                                jdbc,
                                "SELECT state||'|'||reason FROM review_item WHERE review_id='00000000-0000-0000-0000-000000999811' AND workspace_id=978001 AND item_id=999842"))
                .isEqualTo("WITHHELD|CONTRACT_CHANGED");
        assertThat(
                        text(
                                jdbc,
                                "SELECT state||'|'||result_id FROM review_item WHERE review_id='00000000-0000-0000-0000-000000999813' AND workspace_id=978001 AND item_id=999841"))
                .isEqualTo("REUSED|00000000-0000-0000-0000-000000999851");
        assertThat(
                        text(
                                jdbc,
                                "SELECT status||'|'||cancellation_reason FROM review_job WHERE id='00000000-0000-0000-0000-000000999823' AND workspace_id=978001"))
                .isEqualTo("CANCELLED|CONTRACT_CHANGED");
        assertThat(
                        text(
                                jdbc,
                                "SELECT v.definition#>>'{scan,reads,contract}' FROM review_practice rp JOIN practice_version v ON (v.id,v.practice_id,v.workspace_id)=(rp.practice_version_id,rp.practice_id,rp.workspace_id) WHERE rp.review_id='00000000-0000-0000-0000-000000999813' AND rp.workspace_id=978001"))
                .isEqualTo("2.0.0");
        // Recheck the native result/version constraint after the upgrade, not only the stored bytes.
        jdbc.update(
                "UPDATE review_item SET detail='{}' WHERE workspace_id=978001 AND review_id IN ('00000000-0000-0000-0000-000000999811','00000000-0000-0000-0000-000000999813') AND result_id IS NOT NULL");
    }

    private static ObjectNode oldScan(JdbcTemplate jdbc) {
        var scan = definition(jdbc, 978005);
        scan.withObject("/reads").put("contract", "2.0.0");
        scan.set("scan", JSON.readTree("""
            {"itemKind":"scm.file","include":["**/*"],"exclude":[],"context":[],
             "reads":{"contract":"2.0.0","sources":[{"source":"scm.repository.tree","need":"COMPLETE"}]}}
            """));
        append(jdbc, 978105, 2, scan);
        return scan;
    }

    private static void seedScan(TestDatabase database) throws Exception {
        PracticeMigrationFixture.execute(database, """
            BEGIN;
            INSERT INTO item(id,workspace_id,kind,source_id) VALUES(978008,978001,'scm.repository',42);
            INSERT INTO item(id,workspace_id,kind,parent_id,path) VALUES(999841,978001,'scm.file',978008,'a.ts'),(999842,978001,'scm.file',978008,'b.ts');
            INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,budget_usd,ordered_at)
            VALUES('00000000-0000-0000-0000-000000999811',978001,978008,'SCAN','repository-head','REQUEST','RUNNING',0.125,now());
            INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status,items_listed,listing_complete)
            VALUES('00000000-0000-0000-0000-000000999811',978001,978004,978105,'READY',2,true);
            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)
            VALUES('00000000-0000-0000-0000-000000999811',978001,978004,999841,'file-a-head'),
                ('00000000-0000-0000-0000-000000999811',978001,978004,999842,'file-b-head');
            COMMIT;
            """);
    }

    private void runScript(String slug, JsonNode definition) throws Exception {
        Path payload = temporary.resolve(slug + ".json");
        Files.writeString(
                payload,
                JSON.createObjectNode()
                        .put("slug", slug)
                        .put("script", definition.path("script").asString())
                        .put("criteria", definition.path("criteria").asString())
                        .toString());
        Path runner = null;
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path candidate =
                    root.resolve("server/application/src/test/resources/agent/assert-migrated-practice-script.ts");
            if (Files.isRegularFile(candidate)) {
                runner = candidate;
                break;
            }
        }
        assertThat(runner).isNotNull();
        Path output = temporary.resolve(slug + ".log");
        Process process = new ProcessBuilder(
                        "node", Objects.requireNonNull(runner).toString(), payload.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean ended = process.waitFor(60, TimeUnit.SECONDS);
        if (!ended) process.destroyForcibly();
        assertThat(ended).isTrue();
        assertThat(process.exitValue())
                .withFailMessage(Files.readString(output))
                .isZero();
    }

    private static void append(JdbcTemplate jdbc, long id, int number, ObjectNode definition) {
        jdbc.update(
                "INSERT INTO practice_version(id,workspace_id,practice_id,number,definition) VALUES(?,978001,978004,?,?::jsonb)",
                id,
                number,
                definition.toString());
        jdbc.update("UPDATE practice SET current_version_id=? WHERE id=978004 AND workspace_id=978001", id);
    }

    private static JdbcTemplate jdbc(TestDatabase database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
    }

    private static ObjectNode definition(JdbcTemplate jdbc, long id) {
        return (ObjectNode) JSON.readTree(Objects.requireNonNull(
                jdbc.queryForObject("SELECT definition::text FROM practice_version WHERE id=?", String.class, id)));
    }

    private static JsonNode current(JdbcTemplate jdbc) {
        return JSON.readTree(
                text(
                        jdbc,
                        "SELECT v.definition::text FROM practice p JOIN practice_version v ON (v.id,v.practice_id,v.workspace_id)=(p.current_version_id,p.id,p.workspace_id) WHERE p.id=978004 AND p.workspace_id=978001"));
    }

    private static String text(JdbcTemplate jdbc, String sql) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class));
    }

    private static void migrate(TestDatabase database) throws Exception {
        PracticeMigrationFixture.execute(database, resource("db/changelog/source-contract-3.sql"));
    }

    private static String resource(String path) throws Exception {
        try (var input =
                Objects.requireNonNull(PracticeSourceContractMigrationTest.class.getResourceAsStream("/" + path))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
