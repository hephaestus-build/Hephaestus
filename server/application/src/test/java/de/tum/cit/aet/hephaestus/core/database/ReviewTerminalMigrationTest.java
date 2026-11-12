package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ReviewTerminalMigrationTest {
    private static final long WORKSPACE = 979102;
    private static final UUID ITEM_REVIEW = UUID.randomUUID();

    @Test
    void shouldKeepEveryPopulatedTerminalStatusAndSupportRollback() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "review_terminal_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", "-fix-review-closure.xml");
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%1$d,%1$d,'scm.pull_request',%1$d);
                """.formatted(WORKSPACE));
        for (var before : ReviewStatus.values()) {
            for (var after : ReviewStatus.values()) insert(database, id(before, after), before);
        }
        insert(database, ITEM_REVIEW, ReviewStatus.COMPLETE);
        migrate(database, false);

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            for (var before : ReviewStatus.values()) {
                for (var after : ReviewStatus.values()) {
                    String sql = update(id(before, after), after);
                    boolean refused = before.isTerminal() && before != after;
                    if (refused) {
                        assertThatExceptionOfType(SQLException.class)
                                .isThrownBy(() -> statement.executeUpdate(sql))
                                .satisfies(failure ->
                                        assertThat(failure.getSQLState()).isEqualTo("55000"));
                    } else {
                        assertThat(statement.executeUpdate(sql)).isEqualTo(1);
                    }
                    assertThat(PracticeMigrationFixture.column(
                                    database, "SELECT status FROM review WHERE id='" + id(before, after) + "'"))
                            .containsExactly((refused ? before : after).name());
                }
            }
        }

        // Other metadata can change without reopening the terminal review.
        PracticeMigrationFixture.execute(
                database,
                "UPDATE review SET checks=checks||'{\"closure\":true}'::jsonb WHERE id='" + ITEM_REVIEW + "'");
        migrate(database, true);
        PracticeMigrationFixture.execute(database, update(ITEM_REVIEW, ReviewStatus.RUNNING));
        PracticeMigrationFixture.execute(database, update(ITEM_REVIEW, ReviewStatus.COMPLETE));
        migrate(database, false);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeUpdate(update(ITEM_REVIEW, ReviewStatus.RUNNING)))
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("55000"));
        }
    }

    private static UUID id(ReviewStatus before, ReviewStatus after) {
        return UUID.nameUUIDFromBytes((before + ":" + after).getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(ReviewStatus status) {
        return status.isTerminal() && status != ReviewStatus.COMPLETE ? "'EXECUTION_INCOMPLETE'" : "NULL";
    }

    private static void insert(TestDatabase database, UUID id, ReviewStatus status) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,completed_at,stop_reason)
                VALUES('%1$s',%2$d,%2$d,'ITEM','v1','REQUEST','%3$s',now(),%4$s,%5$s);
                """.formatted(
                        id, WORKSPACE, status.name(), status.isTerminal() ? "now()" : "NULL", reason(status)));
    }

    private static String update(UUID id, ReviewStatus status) {
        return "UPDATE review SET status='%s',completed_at=%s,stop_reason=%s WHERE id='%s'"
                .formatted(status.name(), status.isTerminal() ? "now()" : "NULL", reason(status), id);
    }

    private static void migrate(TestDatabase database, boolean rollback) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            if (rollback) liquibase.rollback(1, new Contexts("prod"), new LabelExpression());
            else liquibase.update(1, new Contexts("prod"), new LabelExpression());
        }
    }
}
