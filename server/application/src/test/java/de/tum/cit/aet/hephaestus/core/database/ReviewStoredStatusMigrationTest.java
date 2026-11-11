package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The upgrade stores the status that readers already showed, even when a budget hold prevents another claim. */
@Tag("database")
class ReviewStoredStatusMigrationTest {
    private static final String CHANGELOG = "-review-status-one-truth.xml";
    private static final long WORKSPACE = 979101;
    private static final String FIRST_START = "2026-10-01T12:00:00Z";

    @Test
    void shouldQueueOnlyRunningReviewsWhoseJobsWaitAndPreserveTheirFirstStart() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "review_stored_status_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                """.formatted(WORKSPACE));
        UUID waiting = review(database, 1, "RUNNING", "QUEUED");
        UUID running = review(database, 2, "RUNNING", "RUNNING");
        UUID noJob = review(database, 3, "RUNNING", null);
        UUID terminal = review(database, 4, "COMPLETE", "QUEUED");
        UUID queued = review(database, 5, "QUEUED", "QUEUED");

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(1, new Contexts("prod"), new LabelExpression());
        }

        assertThat(status(database, waiting)).isEqualTo("QUEUED");
        assertThat(status(database, running)).isEqualTo("RUNNING");
        assertThat(status(database, noJob)).isEqualTo("RUNNING");
        assertThat(status(database, terminal)).isEqualTo("COMPLETE");
        assertThat(status(database, queued)).isEqualTo("QUEUED");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT checks->>'startedAt' FROM review WHERE id='" + waiting + "'"))
                .containsExactly(FIRST_START);
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT hold_reason FROM agent_run WHERE concurrency_key='review:" + waiting + "'"))
                .containsExactly("BUDGET");
    }

    private static UUID review(TestDatabase database, long item, String status, @Nullable String jobStatus) {
        UUID review = UUID.randomUUID();
        long itemId = WORKSPACE + item;
        PracticeMigrationFixture.execute(database, """
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%1$d,%2$d,'scm.pull_request',%1$d);
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,checks,completed_at)
                VALUES('%3$s',%2$d,%1$d,'ITEM','v1','REQUEST','%4$s',now(),jsonb_build_object('startedAt','%5$s'),%6$s);
                """.formatted(
                        itemId, WORKSPACE, review, status, FIRST_START, status.equals("COMPLETE") ? "now()" : "NULL"));
        if (jobStatus == null) return review;
        UUID run = UUID.randomUUID();
        boolean leased = jobStatus.equals("RUNNING");
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,leased_until,
                    started_at,hold_reason)
                VALUES('%1$s',%2$d,'REVIEW','BATCH','review:%3$s','%4$s',0,%5$d,%6$s,%7$s,%8$s);
                INSERT INTO review_job(id,review_id,workspace_id,run_id,submitted_at,status,started_at,trace_id,input,
                    config_snapshot)
                VALUES('%9$s','%3$s',%2$d,'%1$s',now(),'%10$s',%7$s,'trace','{}','{}');
                COMMIT;
                """.formatted(
                        run,
                        WORKSPACE,
                        review,
                        leased ? "LEASED" : "QUEUED",
                        leased ? 1 : 0,
                        leased ? "now() + interval '10 minutes'" : "NULL",
                        leased ? "now()" : "NULL",
                        leased ? "NULL" : "'BUDGET'",
                        UUID.randomUUID(),
                        jobStatus));
        return review;
    }

    private static @Nullable String status(TestDatabase database, UUID review) {
        return PracticeMigrationFixture.column(database, "SELECT status FROM review WHERE id='" + review + "'")
                .getFirst();
    }
}
