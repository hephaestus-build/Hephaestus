package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ReviewStagingMigrationTest {
    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("hephaestus_review_staging_migration");

    @Test
    void shouldPreserveExistingRowsAndRollbackStagedWorkAndUnsealedRoots() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE review_item (
                        review_id uuid NOT NULL, practice_id bigint NOT NULL, item_id bigint NOT NULL,
                        workspace_id bigint NOT NULL, state varchar(32) NOT NULL, reason varchar(64),
                        PRIMARY KEY (review_id,practice_id,item_id),
                        CONSTRAINT ck_review_item_state CHECK (state IN ('LISTED','JUDGED','REUSED','WITHHELD')),
                        CONSTRAINT ck_review_item_reason CHECK ((state='WITHHELD')=(reason IS NOT NULL)))
                    """);
            sql.execute(
                    "CREATE TABLE review_composition_basis (job_id uuid PRIMARY KEY, admission_digest varchar(64) NOT NULL)");
            sql.execute("INSERT INTO review_item VALUES ('00000000-0000-4000-8000-000000000001',1,1,1,'JUDGED',NULL)");
            sql.execute(
                    "INSERT INTO review_composition_basis VALUES ('00000000-0000-4000-8000-000000000001',repeat('a',64))");

            try (var migration = new Liquibase(
                    "db/changelog/next/20261031185423-durable-practice-staging.xml",
                    new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
                migration.update(new Contexts("prod"));
                connection.setAutoCommit(true);
                sql.execute("""
                        INSERT INTO review_item (review_id,practice_id,item_id,workspace_id,state,staged_attempt,staged_payload)
                        VALUES ('00000000-0000-4000-8000-000000000001',2,1,1,'STAGED',0,'{}')
                        """);
                sql.execute(
                        "INSERT INTO review_composition_basis VALUES ('00000000-0000-4000-8000-000000000002',NULL)");
                assertThatThrownBy(() -> sql.execute("UPDATE review_item SET state='INVALID' WHERE practice_id=2"))
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));

                migration.rollback(4, "prod");
                connection.setAutoCommit(true);

                try (var row = sql.executeQuery("SELECT state,reason FROM review_item WHERE practice_id=2")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("state")).isEqualTo("WITHHELD");
                    assertThat(row.getString("reason")).isEqualTo("EXECUTION_INCOMPLETE");
                }
                try (var row = sql.executeQuery("""
                        SELECT count(*) FROM information_schema.columns WHERE table_schema='public'
                        AND table_name='review_item' AND column_name IN ('staged_attempt','staged_payload')
                        """)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isZero();
                }
                try (var row = sql.executeQuery("SELECT job_id,admission_digest FROM review_composition_basis")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("job_id")).isEqualTo("00000000-0000-4000-8000-000000000001");
                    assertThat(row.getString("admission_digest")).isEqualTo("a".repeat(64));
                    assertThat(row.next()).isFalse();
                }
                assertThatThrownBy(
                                () -> sql.execute(
                                        "INSERT INTO review_composition_basis VALUES ('00000000-0000-4000-8000-000000000003',NULL)"))
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("23502"));
                migration.update(new Contexts("prod"));
                sql.execute(
                        "UPDATE review_item SET state='STAGED',reason=NULL,staged_attempt=1,staged_payload='{}' WHERE practice_id=2");
                try (var row = sql.executeQuery("SELECT state FROM review_item WHERE practice_id=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("state")).isEqualTo("JUDGED");
                }
            }
        }
    }
}
