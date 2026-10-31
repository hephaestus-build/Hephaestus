package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ItemEventPendingFenceDatabaseTest {
    private static final String CHANGELOG = "1792301912171_changelog.xml";
    private static final String FIRST_CHANGE_SET = "1792301912171-1";
    private static final String RELEASED = "00000000-0000-0000-0000-000000979101";
    private static final String PENDING = "00000000-0000-0000-0000-000000979102";
    private static final String WITHHELD = "00000000-0000-0000-0000-000000979103";
    private static final String TWIN = "00000000-0000-0000-0000-000000979104";
    private static final String EXACT = "scm.pull_request:v1:exact";
    private static final String IMMUTABLE = "55000";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldKeepReleasedEventsAndLetAPendingOccasionReceiveItsVersionOnce(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("item_event_fence_" + context + "_"
                + UUID.randomUUID().toString().replace("-", ""));
        try (var liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int prefix = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getId().equals(FIRST_CHANGE_SET)
                            && pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(prefix, new Contexts(context), new LabelExpression());
        }
        PracticeMigrationFixture.execute(database, """
            INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
            VALUES(979001,'fence-events','Fence events','fence-events','ORG','ACTIVE');
            INSERT INTO item(id,workspace_id,kind,source_id) VALUES(979002,979001,'scm.pull_request',42);
            INSERT INTO item_event(id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)
            VALUES('00000000-0000-0000-0000-000000979101',979001,979002,'scm.pull_request.opened',
                   'scm.pull_request:v1:exact',now(),'WEBHOOK','STARTED',now());
            """);
        var before = released(database);

        try (var liquibase = liquibase(database)) {
            liquibase.update(new Contexts(context), new LabelExpression());
        }
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT id FROM databasechangelog WHERE filename LIKE '%" + CHANGELOG
                                + "' ORDER BY orderexecuted"))
                .containsExactly("1792301912171-1", "1792301912171-2", "1792301912171-3", "1792301912171-4");
        assertThat(released(database)).isEqualTo(before);
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT pending_fence FROM item_event WHERE id='" + RELEASED + "'"))
                .containsExactly((String) null);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (evidence IS NULL AND evidence_expires_at IS NULL)::text FROM item_event WHERE id='"
                                + RELEASED + "'"))
                .as("the upgrade preserves the released occasion without inventing original evidence")
                .containsExactly("true");

        // An exact version never changes and never becomes pending again.
        refused(database, "UPDATE item_event SET item_version='other' WHERE id='" + RELEASED + "'", IMMUTABLE);
        refused(database, "UPDATE item_event SET item_version=NULL WHERE id='" + RELEASED + "'", IMMUTABLE);
        refused(database, "UPDATE item_event SET pending_fence='late' WHERE id='" + RELEASED + "'", IMMUTABLE);
        // An occasion carries a version or a fence.
        refused(database, insert(UUID.randomUUID().toString(), "scm.pull_request.ready", "NULL", "RECORDED"), "23514");

        PracticeMigrationFixture.execute(database, insert(PENDING, "scm.pull_request.ready", "'fence-a'", "RECORDED"));
        refused(database, "UPDATE item_event SET pending_fence='fence-b' WHERE id='" + PENDING + "'", IMMUTABLE);
        refused(database, "UPDATE item_event SET name='scm.pull_request.edited' WHERE id='" + PENDING + "'", IMMUTABLE);
        refused(
                database,
                "UPDATE item_event SET occurred_at=now() - interval '1 day' WHERE id='" + PENDING + "'",
                IMMUTABLE);
        refused(database, "UPDATE item_event SET found_by='SYNC' WHERE id='" + PENDING + "'", IMMUTABLE);
        PracticeMigrationFixture.execute(
                database, "UPDATE item_event SET item_version='scm.pull_request:v1:ready' WHERE id='" + PENDING + "'");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT item_version || '|' || pending_fence FROM item_event WHERE id='" + PENDING + "'"))
                .containsExactly("scm.pull_request:v1:ready|fence-a");
        refused(
                database,
                "UPDATE item_event SET item_version='scm.pull_request:v1:later' WHERE id='" + PENDING + "'",
                IMMUTABLE);
        refused(database, "UPDATE item_event SET item_version=NULL WHERE id='" + PENDING + "'", IMMUTABLE);

        // A decided occasion keeps the version it had when it was decided.
        PracticeMigrationFixture.execute(
                database, insert(WITHHELD, "scm.pull_request.edited", "'fence-c'", "RECORDED"));
        PracticeMigrationFixture.execute(
                database,
                "UPDATE item_event SET state='WITHHELD',reason='VERSION_SUPERSEDED' WHERE id='" + WITHHELD + "'");
        refused(
                database,
                "UPDATE item_event SET item_version='scm.pull_request:v1:edited' WHERE id='" + WITHHELD + "'",
                IMMUTABLE);

        // Pending rows of one name coexist; the version key still refuses a second holder of a version.
        PracticeMigrationFixture.execute(database, insert(TWIN, "scm.pull_request.opened", "'fence-d'", "WAITING"));
        refused(database, "UPDATE item_event SET item_version='" + EXACT + "' WHERE id='" + TWIN + "'", "23505");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT count(*)::text FROM item_event WHERE item_id=979002 AND name='scm.pull_request.opened'"))
                .containsExactly("2");

        // The new reason is admitted without weakening the existing finite reason constraint.
        PracticeMigrationFixture.execute(
                database,
                "UPDATE item_event SET state='WITHHELD',reason='OCCASION_EVIDENCE_UNAVAILABLE' WHERE id='" + TWIN
                        + "'");
        assertThat(PracticeMigrationFixture.column(database, "SELECT reason FROM item_event WHERE id='" + TWIN + "'"))
                .containsExactly("OCCASION_EVIDENCE_UNAVAILABLE");
        refused(database, "UPDATE item_event SET reason='UNKNOWN_REASON' WHERE id='" + TWIN + "'", "23514");
    }

    private static String insert(String id, String name, String fence, String state) {
        return "INSERT INTO item_event(id,workspace_id,item_id,name,item_version,pending_fence,occurred_at,found_by,state,"
                + "decided_at) VALUES('" + id + "',979001,979002,'" + name + "',NULL," + fence + ",now(),'WEBHOOK','"
                + state + "',now())";
    }

    private static List<@Nullable String> released(TestDatabase database) {
        return PracticeMigrationFixture.column(
                database,
                "SELECT concat_ws('|',id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)"
                        + " FROM item_event WHERE id='" + RELEASED + "'");
    }

    private static void refused(TestDatabase database, String sql, String state) {
        assertThatThrownBy(() -> {
                    try (var connection = DriverManager.getConnection(
                                    database.jdbcUrl(), database.username(), database.password());
                            var statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo(state));
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
