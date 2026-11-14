package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A job the 1.0 cutover copied with nothing to settle reads as settled; a job settlement still owes stays unsettled. */
@Tag("database")
class MigratedJobSettlementMigrationTest {
    private static final String CHANGELOG = "-fix-review-job-retention-unsettled.xml";
    private static final long WORKSPACE = 979001;
    private static final long ITEM = 979002;
    private static final Instant OLD = Instant.now().minus(Duration.ofDays(100));

    @Test
    void shouldMarkOnlyMigratedEndedJobsWithoutSettlementDone() throws Exception {
        var database = beforeTheChangelog();
        UUID migrated = job(database, true, "COMPLETED", "NONE", Instant.now());
        UUID owed = job(database, false, "CANCELLED", "NONE", Instant.now());
        UUID pending = job(database, true, "COMPLETED", "PENDING", Instant.now());
        UUID failed = job(database, true, "FAILED", "FAILED", Instant.now());

        migrate(database);

        assertThat(processing(database, migrated)).isEqualTo("DONE");
        assertThat(processing(database, owed)).isEqualTo("NONE");
        assertThat(processing(database, pending)).isEqualTo("PENDING");
        assertThat(processing(database, failed)).isEqualTo("FAILED");
    }

    /**
     * Retention on the previous release excluded only PENDING, so it already emptied and deleted an old migrated job at
     * NONE. After the correction that job still goes; an old job that settlement still owes now stays.
     */
    @Test
    void shouldKeepAMigratedJobOnItsRetentionPathAndKeepAJobThatSettlementStillOwes() throws Exception {
        var database = beforeTheChangelog();
        UUID migrated = job(database, true, "COMPLETED", "NONE", OLD);
        UUID owed = job(database, false, "CANCELLED", "NONE", OLD);

        // The repository's current statements run below, so the schema is the current one.
        migrateToHead(database);
        var jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        var window = Map.of("cutoff", Timestamp.from(Instant.now().minus(Duration.ofDays(90))), "batchSize", 500);
        jdbc.update(statement("stripTerminalPayloads"), window);

        assertThat(output(database, migrated)).isNull();
        assertThat(output(database, owed)).isEqualTo("{\"receipt\": \"kept\"}");

        jdbc.queryForList(statement("deleteUnreferencedTerminalRowsOlderThan"), window);

        assertThat(processing(database, migrated)).isNull();
        assertThat(processing(database, owed)).isEqualTo("NONE");
    }

    private static TestDatabase beforeTheChangelog() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "migrated_settlement_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%2$d,%1$d,'scm.pull_request',42);
                """.formatted(WORKSPACE, ITEM));
        return database;
    }

    private static void migrate(TestDatabase database) throws Exception {
        update(database, liquibase -> liquibase.update(2, new Contexts("prod"), new LabelExpression()));
    }

    private static void migrateToHead(TestDatabase database) throws Exception {
        update(database, liquibase -> liquibase.update(new Contexts("prod"), new LabelExpression()));
    }

    private interface Update {
        void apply(Liquibase liquibase) throws Exception;
    }

    private static void update(TestDatabase database, Update update) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            update.apply(liquibase);
        }
    }

    private static String statement(String method) throws Exception {
        for (var candidate : ReviewJobRepository.class.getMethods())
            if (candidate.getName().equals(method))
                return Objects.requireNonNull(candidate.getAnnotation(Query.class))
                        .value();
        throw new IllegalArgumentException(method);
    }

    /** A review with one ended job; a migrated job shares its review's id and the review carries the cutover's mark. */
    private static UUID job(
            TestDatabase database, boolean migrated, String status, String processing, Instant completedAt) {
        UUID job = UUID.randomUUID();
        UUID review = migrated ? job : UUID.randomUUID();
        UUID run = UUID.randomUUID();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,checks)
                VALUES('%1$s',%2$d,%3$d,'ITEM','v1','REQUEST','QUEUED',now(),'%4$s');
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,stop_reason,
                    started_at,ended_at)
                VALUES('%5$s',%2$d,'REVIEW','BATCH','review:%1$s','ENDED',0,1,'COMPLETED',now(),now());
                INSERT INTO review_job(id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,
                    config_snapshot,run_id,completed_at,output)
                VALUES('%6$s','%1$s',%2$d,now(),'%7$s','%8$s',repeat('f',32),'{}','{}','%5$s','%9$s',
                    '{"receipt":"kept"}');
                """.formatted(
                        review,
                        WORKSPACE,
                        ITEM,
                        migrated ? "{\"MIGRATION\":{}}" : "{}",
                        run,
                        job,
                        status,
                        processing,
                        completedAt));
        return job;
    }

    private static @Nullable String processing(TestDatabase database, UUID job) {
        return PracticeMigrationFixture.column(
                        database, "SELECT processing_status FROM review_job WHERE id='" + job + "'")
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static @Nullable String output(TestDatabase database, UUID job) {
        return PracticeMigrationFixture.column(database, "SELECT output::text FROM review_job WHERE id='" + job + "'")
                .getFirst();
    }
}
