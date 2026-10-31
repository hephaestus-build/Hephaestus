package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Tag("database")
class ScriptRunNotFinishedCorrectionMigrationTest {
    private static final String CHANGELOG = "1792318767974_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");

    /** A capture that excluded the practice because Applies when proved it absent. */
    private static final String ABSENT_CAPTURE = """
            {"practices":[{"slug":"other-practice"}],
             "readiness":{"decisions":[{"practiceSlug":"native-practice","ready":true}],
                          "applicability":{"native-practice":{"absent":true}}}}""";

    private static final String ABSENT_DECISION =
            "{\"practiceSlug\":\"native-practice\",\"ready\":true,\"applicability\":{\"absent\":true}}";

    @Test
    void shouldDeleteOnlyTheCurrentAttemptsUnmeasuredRunsOfPracticesThatCaptureExcluded() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("script_run_not_finished_correction");
        migrateBeforeTheCorrection(database);
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        PracticeMigrationFixture.execute(database, """
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT 978007,workspace_id,practice_id,2,definition FROM practice_version WHERE id=978005;
                """);
        // Wrong: Applies when proved the practice absent, and the runner never received it.
        scenario(database, 1, ABSENT_DECISION, 1, ABSENT_CAPTURE, 1, 978005, null, false);
        // Wrong: its evidence was not ready, and the runner never received it.
        scenario(database, 2, "{\"practiceSlug\":\"native-practice\",\"ready\":false}", 1, """
                {"practices":[{"slug":"other-practice"}],
                 "readiness":{"decisions":[{"practiceSlug":"native-practice","ready":false}],"applicability":{}}}""", 1, 978005, null, false);
        // Kept: the runner received the practice and its report did not name it.
        scenario(database, 3, "{\"practiceSlug\":\"native-practice\",\"ready\":true}", 1, """
                {"practices":[{"slug":"native-practice"}],
                 "readiness":{"decisions":[{"practiceSlug":"native-practice","ready":true}],"applicability":{}}}""", 1, 978005, null, false);
        // Kept: the run carries a real measurement.
        scenario(database, 4, ABSENT_DECISION, 1, ABSENT_CAPTURE, 1, 978005, 5, false);
        // Kept: the capture belongs to attempt 2, not to the run's attempt 1.
        scenario(database, 5, ABSENT_DECISION, 2, ABSENT_CAPTURE, 1, 978005, null, false);
        // Kept: usage is recorded for the practice in this attempt.
        scenario(database, 6, ABSENT_DECISION, 1, ABSENT_CAPTURE, 1, 978005, null, true);
        // Kept: the run names another version than the review pinned.
        scenario(database, 7, ABSENT_DECISION, 1, ABSENT_CAPTURE, 1, 978007, null, false);
        // Kept: the facts are not JSON booleans.
        scenario(
                database,
                8,
                "{\"practiceSlug\":\"native-practice\",\"ready\":true,\"applicability\":{\"absent\":\"true\"}}",
                1,
                ABSENT_CAPTURE.replace("\"absent\":true", "\"absent\":\"true\""),
                1,
                978005,
                null,
                false);
        // Kept: the job capture and the capture decision disagree.
        scenario(
                database,
                9,
                "{\"practiceSlug\":\"native-practice\",\"ready\":true,\"applicability\":{\"absent\":false}}",
                1,
                ABSENT_CAPTURE,
                1,
                978005,
                null,
                false);
        // Kept: retention removed the job capture, so the frozen facts are unknown.
        scenario(database, 10, ABSENT_DECISION, 1, null, 1, 978005, null, false);
        // Kept: the staged index is not an array, so whether the runner received the practice is unknown.
        scenario(
                database,
                11,
                ABSENT_DECISION,
                1,
                ABSENT_CAPTURE.replace("[{\"slug\":\"other-practice\"}]", "{}"),
                1,
                978005,
                null,
                false);
        // Kept: a practice withheld before capture records no readiness fact.
        scenario(
                database,
                12,
                "{\"practiceSlug\":\"native-practice\",\"sourceContract\":\"1.0.0\"}",
                1,
                """
                {"practices":[{"slug":"other-practice"}],"readiness":{"decisions":[],"applicability":{}}}""",
                1,
                978005,
                null,
                false);
        // Kept: the run reports its calls, so it is a measurement.
        scenario(database, 13, ABSENT_DECISION, 1, ABSENT_CAPTURE, 1, 978005, null, false);
        PracticeMigrationFixture.execute(
                database,
                "UPDATE review_job_script_run SET calls='{}'" + " WHERE job_id='00000000-0000-0000-0000-000000996113'");
        var untouched = """
                SELECT md5(string_agg(row::text, ',' ORDER BY row::text)) FROM (
                    SELECT to_jsonb(j)::text AS row FROM review_job j WHERE workspace_id=978001
                    UNION ALL SELECT to_jsonb(p)::text FROM review_practice p WHERE workspace_id=978001
                    UNION ALL SELECT to_jsonb(u)::text FROM review_job_script_usage u WHERE workspace_id=978001
                    UNION ALL SELECT to_jsonb(r)::text FROM review r WHERE workspace_id=978001) rows
                """;
        var before = PracticeMigrationFixture.column(database, untouched);

        correct(database);

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT right(job_id::text,2)||':'||attempt FROM review_job_script_run"
                                + " WHERE workspace_id=978001 ORDER BY job_id"))
                .containsExactly(
                        "03:1", "04:1", "05:1", "06:1", "07:1", "08:1", "09:1", "10:1", "11:1", "12:1", "13:1");
        assertThat(PracticeMigrationFixture.column(database, untouched)).isEqualTo(before);
        try (var liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.rollback(1, CONTEXTS, new LabelExpression()))
                    .hasStackTraceContaining("Restore the database backup taken before this upgrade");
        }
    }

    private static void scenario(
            TestDatabase database,
            int n,
            String captureDecision,
            int jobAttempt,
            @Nullable String capture,
            int runAttempt,
            long runVersion,
            @Nullable Integer durationMs,
            boolean usage) {
        String review = "00000000-0000-0000-0000-0000009960%02d".formatted(n);
        String job = "00000000-0000-0000-0000-0000009961%02d".formatted(n);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                VALUES('%1$s',978001,978006,'ITEM','native-v1','REQUEST','QUEUED',now());
                INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status,reason,
                    capture_decision)
                VALUES('%1$s',978001,978004,978005,%9$s,'%3$s');
                INSERT INTO review_job(id,review_id,workspace_id,submitted_at,status,attempt,trace_id,input,
                    config_snapshot,capture)
                VALUES('%2$s','%1$s',978001,now(),'COMPLETED',%4$d,'trace','{}','{}',%5$s);
                INSERT INTO review_job_script_run(job_id,attempt,practice_id,workspace_id,practice_version_id,status,
                    script_kind,finished_at,duration_ms)
                VALUES('%2$s',%6$d,978004,978001,%7$d,'NOT_FINISHED','BUILTIN',now(),%8$s);
                """.formatted(
                        review,
                        job,
                        captureDecision,
                        jobAttempt,
                        capture == null ? "NULL" : "'" + capture + "'",
                        runAttempt,
                        runVersion,
                        durationMs == null ? "NULL" : durationMs.toString(),
                        captureDecision.contains("\"ready\":false")
                                ? "'WITHHELD','readiness_refused'"
                                : captureDecision.contains("sourceContract")
                                        ? "'WITHHELD','source_contract_unsupported'"
                                        : "'DONE',NULL"));
        if (usage) PracticeMigrationFixture.execute(database, """
                    INSERT INTO review_job_script_usage(job_id,attempt,model_kind,practice_id,workspace_id,calls)
                    VALUES('%s',%d,'DECISION',978004,978001,1);
                    """.formatted(job, runAttempt));
    }

    private static void migrateBeforeTheCorrection(TestDatabase database) throws Exception {
        try (var liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(CONTEXTS, new LabelExpression());
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(before, CONTEXTS, new LabelExpression());
        }
    }

    private static void correct(TestDatabase database) throws Exception {
        try (var liquibase = liquibase(database)) {
            liquibase.update(1, CONTEXTS, new LabelExpression());
            assertThat(liquibase.listUnrunChangeSets(CONTEXTS, new LabelExpression()))
                    .noneMatch(change -> change.getFilePath().endsWith(CHANGELOG));
        }
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }
}
