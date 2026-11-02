package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class SeededPracticeRetirementMigrationTest {
    private static final String CHANGELOG = "20261103081558-retire-seeded-practices.xml";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long PURGED = 996001;
    private static final long ACTIVE = 996002;
    private static final long DEVELOPER = 996003;
    private static final long ITEM = 996004;
    private static final String REVIEW = "00000000-0000-0000-0000-000000996011";
    private static final String JOB = "00000000-0000-0000-0000-000000996012";
    private static final String RESULT = "00000000-0000-0000-0000-000000996013";
    private static final List<String> SLUGS = List.of(
            "commit-message-quality", "error-handling", "pr-description-quality", "pr-scope", "review-thoroughness");

    @Test
    void shouldDeleteUnreviewedRowsOfAPurgedWorkspaceWhoseFeedbackModeIsNull() throws Exception {
        var database = beforeRetirement();
        seedWorkspace(database, PURGED, "PURGED");
        for (int index = 0; index < SLUGS.size(); index++)
            seedPractice(database, PURGED, 100 + index, SLUGS.get(index));

        migrate(database);

        assertThat(PracticeMigrationFixture.column(database, "SELECT slug FROM practice WHERE workspace_id=" + PURGED))
                .isEmpty();
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT id::text FROM practice_version WHERE workspace_id=" + PURGED))
                .isEmpty();
    }

    @Test
    void shouldJudgeHistoryOnEveryReferencingTableAndKeepResultsOfRetiredRows() throws Exception {
        var database = beforeRetirement();
        seedWorkspace(database, ACTIVE, "ACTIVE");
        PracticeMigrationFixture.execute(
                database, """
                INSERT INTO account(id,display_name,status) VALUES(%d,'Developer','ACTIVE');
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%d,%d,'scm.pull_request',42);
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                VALUES('%s',%d,%d,'ITEM','v1','REQUEST','QUEUED',now());
                INSERT INTO review_job(id,review_id,workspace_id,submitted_at,status,attempt,trace_id,input,config_snapshot,capture)
                VALUES('%s','%s',%d,now(),'COMPLETED',0,'trace','{}','{}','{}');
                """.formatted(DEVELOPER, ITEM, ACTIVE, REVIEW, ACTIVE, ITEM, JOB, REVIEW, ACTIVE));
        long membership = seedPractice(database, ACTIVE, 200, "pr-scope");
        long judged = seedPractice(database, ACTIVE, 210, "error-handling");
        long run = seedPractice(database, ACTIVE, 220, "commit-message-quality");
        long usage = seedPractice(database, ACTIVE, 230, "pr-description-quality");
        long unreviewed = seedPractice(database, ACTIVE, 240, "review-thoroughness");
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)
                VALUES('%1$s',%2$d,%3$d,%4$d,'READY'),('%1$s',%2$d,%5$d,%6$d,'READY');
                INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)
                VALUES('%1$s',%2$d,%5$d,%7$d,'v1');
                INSERT INTO result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,outcome,coverage,recorded_at)
                VALUES('%8$s',%2$d,'%1$s',%5$d,%6$d,%7$d,'v1','MET','{}',now());
                INSERT INTO review_job_script_run(job_id,attempt,practice_id,workspace_id,practice_version_id,status,script_kind,finished_at,duration_ms)
                VALUES('%9$s',0,%10$d,%2$d,%11$d,'NOT_FINISHED','BUILTIN',now(),1);
                INSERT INTO review_job_script_usage(job_id,attempt,model_kind,practice_id,workspace_id,calls)
                VALUES('%9$s',0,'DECISION',%12$d,%2$d,1);
                """.formatted(
                        REVIEW,
                        ACTIVE,
                        membership,
                        membership + 1,
                        judged,
                        judged + 1,
                        ITEM,
                        RESULT,
                        JOB,
                        run,
                        run + 1,
                        usage));

        migrate(database);

        assertThat(PracticeMigrationFixture.column(database, "SELECT id::text FROM practice WHERE id=" + unreviewed))
                .isEmpty();
        for (long retired : List.of(membership, judged, run, usage)) {
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT p.feedback_mode || ':' || v.number || ':' || (v.definition#>>'{reviewedBy,by}')"
                                    + " FROM practice p JOIN practice_version v ON v.id=p.current_version_id WHERE p.id="
                                    + retired))
                    .containsExactly("OFF:2:NOBODY");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT number::text FROM practice_version WHERE practice_id=" + retired + " ORDER BY 1"))
                    .containsExactly("1", "2");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT v.definition#>>'{reviewedBy,limits,-1,description}' FROM practice p"
                                    + " JOIN practice_version v ON v.id=p.current_version_id WHERE p.id=" + retired))
                    .singleElement()
                    .satisfies(note -> assertThat(note)
                            .startsWith("Retired in 1.0")
                            .contains("Hephaestus no longer reviews it")
                            .contains("To bring it back"));
        }
        assertThat(PracticeMigrationFixture.column(database, "SELECT id::text FROM result WHERE id='" + RESULT + "'"))
                .containsExactly(RESULT);
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT practice_version_id::text FROM result WHERE id='" + RESULT + "'"))
                .containsExactly(Long.toString(judged + 1));
    }

    @Test
    void shouldLeaveEveryOtherPracticeAloneAndRefuseToRollBack() throws Exception {
        var database = beforeRetirement();
        seedWorkspace(database, ACTIVE, "ACTIVE");
        long edited = seedPractice(database, ACTIVE, 300, "error-handling");
        PracticeMigrationFixture.execute(database, """
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT %1$d+2,workspace_id,practice_id,2,jsonb_set(definition,'{criteria}','"Our own words."')
                FROM practice_version WHERE id=%1$d+1;
                UPDATE practice SET current_version_id=%1$d+2 WHERE id=%1$d;
                """.formatted(edited));
        long catalog = seedPractice(database, ACTIVE, 310, "pr-scope");
        PracticeMigrationFixture.execute(
                database, "UPDATE practice SET catalog_slug='pr-scope', catalog_base='{}' WHERE id=" + catalog);
        long own = seedPractice(database, ACTIVE, 320, "commit-message-quality");
        PracticeMigrationFixture.execute(database, """
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT %1$d+2,workspace_id,practice_id,2,jsonb_set(definition,'{reviewedBy,by}','"NOBODY"')
                FROM practice_version WHERE id=%1$d+1;
                UPDATE practice SET current_version_id=%1$d+2 WHERE id=%1$d;
                """.formatted(own));

        // Unchanged criteria, but an admin renamed it: an edit in any field keeps the copy out of the retirement.
        long renamed = seedPractice(database, ACTIVE, 330, "pr-description-quality");
        // A Nobody version must not keep a scan, so a copy with an added scan would fail the definition trigger.
        long scanned = seedPractice(database, ACTIVE, 340, "review-thoroughness");
        PracticeMigrationFixture.execute(database, """
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT %1$d+2,workspace_id,practice_id,2,jsonb_set(definition,'{name}','"Our description rules"')
                FROM practice_version WHERE id=%1$d+1;
                UPDATE practice SET current_version_id=%1$d+2 WHERE id=%1$d;
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT %2$d+2,workspace_id,practice_id,2,jsonb_set(definition,'{scan}',
                    '{"itemKind":"scm.file","include":["**/*.java"],"exclude":[],"context":["**/*"],
                      "reads":{"contract":"3.0.0","sources":[{"source":"scm.repository.tree","need":"COMPLETE"}]}}')
                FROM practice_version WHERE id=%2$d+1;
                UPDATE practice SET current_version_id=%2$d+2 WHERE id=%2$d;
                """.formatted(renamed, scanned));

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = liquibase(connection)) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts("prod"), new LabelExpression()))
                    .hasStackTraceContaining("verified pre-upgrade database backup");
        }

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM practice_version WHERE practice_id IN (" + edited + "," + own + ","
                                + renamed + "," + scanned + ")"))
                .containsExactly("8");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT coalesce(feedback_mode,'unset') FROM practice WHERE id IN (" + edited + "," + catalog
                                + "," + own + "," + renamed + "," + scanned + ")"))
                .containsOnly("unset")
                .hasSize(5);
    }

    private static TestDatabase beforeRetirement() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "seeded_retirement_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        return database;
    }

    private static void seedWorkspace(TestDatabase database, long id, String status) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','%2$s');
                """.formatted(id, status));
    }

    /** One practice as the first seed left it, with no feedback mode. Its version id is the practice id plus one. */
    private static long seedPractice(TestDatabase database, long workspace, long offset, String slug) throws Exception {
        long id = workspace + offset;
        String definition = definition(slug).toString().replace("'", "''");
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                SET CONSTRAINTS ALL DEFERRED;
                INSERT INTO practice(id,workspace_id,slug,kind,current_version_id) VALUES(%1$d,%2$d,'%3$s','scm.pull_request',%1$d+1);
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition) VALUES(%1$d+1,%2$d,%1$d,1,'%4$s');
                COMMIT;
                """.formatted(id, workspace, slug, definition));
        return id;
    }

    private static JsonNode definition(String slug) throws Exception {
        try (InputStream input = Objects.requireNonNull(SeededPracticeRetirementMigrationTest.class.getResourceAsStream(
                "/db/practice-retired/seeded-definitions.json"))) {
            return MAPPER.readTree(input).path(slug);
        }
    }

    private static void migrate(TestDatabase database) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = liquibase(connection)) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        return new Liquibase(
                "db/changelog/next/" + CHANGELOG,
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
    }
}
