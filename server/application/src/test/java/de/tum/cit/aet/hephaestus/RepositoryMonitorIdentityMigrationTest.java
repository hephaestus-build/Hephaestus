package de.tum.cit.aet.hephaestus;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("database")
class RepositoryMonitorIdentityMigrationTest {
    private static final String CHANGELOG = "1792284494860_changelog.xml";
    private static final String WORKSPACE = """
            INSERT INTO workspace (id,account_login,account_type,created_at,display_name,slug,status,practices_enabled,
                practice_review_auto_trigger_enabled,practice_review_manual_trigger_enabled,review_scope_repositories,
                review_scope_people,practice_delivery_status,practice_rollout_revision,practice_config_version,
                public_activity_enabled,public_activity_search_engines)
            VALUES (%1$d,'ws-%1$d','ORG',now(),'Workspace %1$d','ws-%1$d','ACTIVE',true,true,true,'SELECTED','ALL',
                'ACTIVE',0,0,false,false);
            """;

    @Test
    void shouldKeepTheLowestMonitorWithItsOwnStateAndItsWorkspacesReviewScope() throws Exception {
        try (var database = MigratingDatabase.atBranchTail("repository_monitor_identity_merge")) {
            database.execute(WORKSPACE.formatted(994001) + WORKSPACE.formatted(994002) + """
                    INSERT INTO identity_provider (id,type,server_url,created_at)
                    VALUES (994050,'GITHUB','https://github.monitor-identity.test',now());
                    INSERT INTO repository (id,native_id,provider_id,name,name_with_owner,is_archived,is_disabled,
                        is_private,has_discussions_enabled,default_branch)
                    VALUES (994060,1228805974,994050,'explAIner','ls1intum/explAIner',false,false,false,false,'develop');
                    INSERT INTO repository_to_monitor (id,workspace_id,name_with_owner,native_id,generated_paths,
                        issue_sync_cursor,issues_synced_at,pull_request_backfill_high_water_mark,
                        pull_request_backfill_checkpoint,recent_sync_error)
                    VALUES (994011,994001,'ls1intum/explAIner',1228805974,'["dist/**"]','cursor-a',
                            '2026-01-01T00:00:00Z',40,5,'error-a'),
                           (994012,994001,'ls1intum/explAIner',1228805974,'["dist/**"]','cursor-b',
                            '2026-02-01T00:00:00Z',40,1,NULL),
                           (994013,994001,'org/equal',42,'[]',NULL,NULL,NULL,NULL,NULL),
                           (994014,994001,'org/equal',42,'[]',NULL,NULL,NULL,NULL,NULL),
                           (994015,994001,'org/unresolved-one',NULL,'[]',NULL,NULL,NULL,NULL,NULL),
                           (994016,994001,'org/unresolved-two',NULL,'[]',NULL,NULL,NULL,NULL,NULL),
                           (994017,994002,'ls1intum/explAIner',1228805974,'[]',NULL,NULL,NULL,NULL,NULL);
                    INSERT INTO review_scope_repository (workspace_id,repository_monitor_id,base_branches)
                    VALUES (994001,994012,'["main"]'),
                           (994001,994013,'["main","release"]'),
                           (994001,994014,'["main","release"]'),
                           (994002,994017,'["develop"]');
                    """);

            database.migrateBranch();

            assertThat(database.strings("SELECT workspace_id||':'||id FROM repository_to_monitor"
                            + " WHERE workspace_id IN (994001,994002) ORDER BY id"))
                    .containsExactly(
                            "994001:994011", "994001:994013", "994001:994015", "994001:994016", "994002:994017");
            assertThat(database.strings("""
                    SELECT concat_ws('|',issue_sync_cursor,issues_synced_at AT TIME ZONE 'UTC',
                        pull_request_backfill_high_water_mark,pull_request_backfill_checkpoint,recent_sync_error,
                        generated_paths::text)
                    FROM repository_to_monitor WHERE id=994011
                    """))
                    .containsExactly("cursor-a|2026-01-01 00:00:00|40|5|error-a|[\"dist/**\"]");
            assertThat(database.strings("SELECT workspace_id||':'||repository_monitor_id||':'||base_branches::text"
                            + " FROM review_scope_repository WHERE workspace_id IN (994001,994002)"
                            + " ORDER BY repository_monitor_id"))
                    .containsExactly(
                            "994001:994011:[\"main\"]",
                            "994001:994013:[\"main\", \"release\"]",
                            "994002:994017:[\"develop\"]");
            assertThat(database.strings("SELECT id||':'||name_with_owner||':'||default_branch FROM repository"
                            + " WHERE provider_id=994050"))
                    .containsExactly("994060:ls1intum/explAIner:develop");

            database.execute("""
                    INSERT INTO repository_to_monitor (id,workspace_id,name_with_owner,native_id,generated_paths)
                    VALUES (994018,994001,'org/unresolved-three',NULL,'[]');
                    """);
            assertThatThrownBy(() -> database.execute("""
                            INSERT INTO repository_to_monitor (id,workspace_id,name_with_owner,native_id,generated_paths)
                            VALUES (994019,994001,'org/renamed',1228805974,'[]');
                            """))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            error -> assertThat(error.getSQLState()).isEqualTo("23505"))
                    .hasMessageContaining("uk_repository_to_monitor_workspace_native_id");
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "generated paths  | org/a | org/a | []         | [\"dist/**\"] | [\"main\"] | [\"main\"]",
                "review branches  | org/a | org/a | []         | []            | [\"main\"] | [\"develop\"]",
                "repository names | org/a | org/A | []         | []            | [\"main\"] | [\"main\"]",
            })
    void shouldStopWithoutChangingMonitorsThatDisagree(
            String conflict,
            String firstName,
            String secondName,
            String firstPaths,
            String secondPaths,
            String firstBranches,
            String secondBranches)
            throws Exception {
        try (var database =
                MigratingDatabase.atBranchTail("repository_monitor_identity_conflict_" + conflict.replace(' ', '_'))) {
            database.execute(WORKSPACE.formatted(994101)
                    + """
                    INSERT INTO repository_to_monitor (id,workspace_id,name_with_owner,native_id,generated_paths,
                        issue_sync_cursor)
                    VALUES (994111,994101,'%s',7,'%s','cursor-a'),(994112,994101,'%s',7,'%s','cursor-b');
                    INSERT INTO review_scope_repository (workspace_id,repository_monitor_id,base_branches)
                    VALUES (994101,994111,'%s'),(994101,994112,'%s');
                    """.formatted(firstName, firstPaths, secondName, secondPaths, firstBranches, secondBranches));

            assertThatThrownBy(database::migrateBranch)
                    .as(conflict)
                    .hasStackTraceContaining("Duplicate repository monitors disagree")
                    .hasStackTraceContaining("monitors {994111,994112}");

            database.reconnect();
            assertThat(database.strings("SELECT id||':'||name_with_owner||':'||generated_paths::text||':'"
                            + "||issue_sync_cursor FROM repository_to_monitor WHERE workspace_id=994101 ORDER BY id"))
                    .containsExactly(
                            "994111:" + firstName + ":" + firstPaths + ":cursor-a",
                            "994112:" + secondName + ":" + secondPaths + ":cursor-b");
            assertThat(database.strings("SELECT repository_monitor_id||':'||base_branches::text"
                            + " FROM review_scope_repository WHERE workspace_id=994101 ORDER BY repository_monitor_id"))
                    .containsExactly("994111:" + firstBranches, "994112:" + secondBranches);
            assertThat(database.strings("SELECT conname FROM pg_constraint"
                            + " WHERE conname='uk_repository_to_monitor_workspace_native_id'"))
                    .isEmpty();
        }
    }

    @Test
    void shouldLetOneOfTwoConcurrentMonitorsOfARepositoryCommit() throws Exception {
        try (var database = MigratingDatabase.atBranchTail("repository_monitor_identity_race")) {
            database.execute(WORKSPACE.formatted(994201));
            database.migrateBranch();
            String insert =
                    "INSERT INTO repository_to_monitor (id,workspace_id,name_with_owner,native_id,generated_paths)"
                            + " VALUES (%d,994201,'%s',9,'[]')";

            try (Connection first = database.open();
                    Connection second = database.open()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                int firstPid = backendPid(first);
                int secondPid = backendPid(second);
                // Closing a statement keeps its transaction open; the first insert holds the key until commit.
                try (Statement statement = first.createStatement()) {
                    statement.execute(insert.formatted(994211, "org/first"));
                }
                var losing = CompletableFuture.runAsync(() -> {
                    try (Statement statement = second.createStatement()) {
                        statement.execute(insert.formatted(994212, "org/second"));
                        second.commit();
                    } catch (SQLException error) {
                        throw new IllegalStateException(error.getSQLState(), error);
                    }
                });
                try (Connection observer = database.open()) {
                    await().atMost(Duration.ofSeconds(20))
                            .untilAsserted(() ->
                                    assertThat(blockers(observer, secondPid)).contains(firstPid));
                }
                first.commit();

                assertThatThrownBy(() -> losing.get(20, SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .satisfies(error -> assertThat(error.getCause()).hasMessage("23505"));
            }
            assertThat(database.strings("SELECT id::text FROM repository_to_monitor WHERE workspace_id=994201"))
                    .containsExactly("994211");
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<Integer> blockers(Connection observer, int pid) throws SQLException {
        try (var statement = observer.prepareStatement("SELECT unnest(pg_blocking_pids(?))")) {
            statement.setInt(1, pid);
            var found = new ArrayList<Integer>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) found.add(rows.getInt(1));
            }
            return found;
        }
    }

    /** A database migrated by the released chain up to this branch's changelog. */
    private static final class MigratingDatabase implements AutoCloseable {
        private final PostgreSQLTestContainer.TestDatabase testDatabase;
        private final Contexts contexts = new Contexts("prod");
        private final LabelExpression labels = new LabelExpression();
        private Connection connection;
        private Liquibase liquibase;
        private int branchChanges;

        private MigratingDatabase(PostgreSQLTestContainer.TestDatabase testDatabase) throws Exception {
            this.testDatabase = testDatabase;
            this.connection = open();
            this.liquibase = liquibase(connection);
        }

        static MigratingDatabase atBranchTail(String name) throws Exception {
            var database = new MigratingDatabase(PostgreSQLTestContainer.createDatabase(name));
            var pending = database.liquibase.listUnrunChangeSets(database.contexts, database.labels);
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            database.branchChanges = (int) pending.stream()
                    .filter(change -> change.getFilePath().endsWith(CHANGELOG))
                    .count();
            database.liquibase.update(before, database.contexts, database.labels);
            return database;
        }

        Connection open() throws SQLException {
            return DriverManager.getConnection(
                    testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password());
        }

        void execute(String sql) throws SQLException {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            commit();
        }

        void migrateBranch() throws Exception {
            liquibase.update(branchChanges, contexts, labels);
        }

        /** A failed update leaves its connection in an aborted transaction; reads use a new one. */
        void reconnect() throws Exception {
            close();
            connection = open();
            liquibase = liquibase(connection);
        }

        List<String> strings(String sql) throws SQLException {
            var values = new ArrayList<String>();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(sql)) {
                while (rows.next()) values.add(rows.getString(1));
            }
            commit();
            return values;
        }

        private void commit() throws SQLException {
            if (!connection.getAutoCommit()) connection.commit();
        }

        @Override
        public void close() throws LiquibaseException, SQLException {
            liquibase.close();
            connection.close();
        }

        private static Liquibase liquibase(Connection connection) throws Exception {
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
}
