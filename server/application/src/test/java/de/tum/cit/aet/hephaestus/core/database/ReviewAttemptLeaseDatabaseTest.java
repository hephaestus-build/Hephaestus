package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ReviewAttemptLeaseDatabaseTest {
    private static final String CHANGELOG = "20261101080313-review-attempt-leases.xml";
    private static final String CHANGE_SET = "1793475620791-1";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldGiveARunningAttemptOfThePreviousReleaseALeaseThatNoWorkerHolds(String context) throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "review_lease_" + context + "_" + UUID.randomUUID().toString().replace("-", ""));
        try (Liquibase liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int prefix = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getId().equals(CHANGE_SET)
                            && pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(prefix, new Contexts(context), new LabelExpression());
        }
        // Only review_job's own columns matter here, so its parents are not seeded; each row is its own review.
        PracticeMigrationFixture.execute(database, """
                SET session_replication_role = replica;
                INSERT INTO review_job(id,review_id,workspace_id,status,attempt,input,config_snapshot,trace_id,
                                       submitted_at,worker_id,leased_until)
                VALUES('00000000-0000-0000-0000-000000897461','00000000-0000-0000-0000-000000897460',897461,
                       'RUNNING',2,'{}','{}',repeat('a',32),now(),'old-worker',now()+interval '1 minute'),
                      ('00000000-0000-0000-0000-000000897462','00000000-0000-0000-0000-000000897464',897461,
                       'QUEUED',1,'{}','{}',repeat('b',32),now(),NULL,NULL),
                      ('00000000-0000-0000-0000-000000897463','00000000-0000-0000-0000-000000897465',897461,
                       'COMPLETED',0,'{}','{}',repeat('c',32),now(),'old-worker',NULL);
                SET session_replication_role = origin;
                """);
        var before = PracticeMigrationFixture.column(
                database,
                "SELECT (to_jsonb(j)-'worker_id')::text FROM review_job j WHERE workspace_id=897461 ORDER BY id");

        try (Liquibase liquibase = liquibase(database)) {
            liquibase.update(1, new Contexts(context), new LabelExpression());
        }

        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT (to_jsonb(j)-'lease_id')::text FROM review_job j WHERE workspace_id=897461 ORDER BY id"))
                .as("the migration changes no other column of any row")
                .isEqualTo(before);
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT status||'|'||(lease_id IS NOT NULL) FROM review_job WHERE workspace_id=897461 ORDER BY id"))
                .as("only the running attempt gets a lease, which no worker of the new release holds")
                .containsExactly("RUNNING|true", "QUEUED|false", "COMPLETED|false");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM information_schema.columns"
                                + " WHERE table_name='review_job' AND column_name='worker_id'"))
                .containsExactly("0");
        try (Liquibase liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(context), new LabelExpression()))
                    .hasMessageContaining("Restore the verified pre-upgrade database backup");
        }
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
        // The fixture starts before the migration under test, before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }
}
