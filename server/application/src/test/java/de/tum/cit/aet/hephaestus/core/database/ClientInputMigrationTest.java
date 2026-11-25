package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The create receipts and their expiry rule: the change sets install the table, its purge trigger and the sweep
 * branch, and their rollback leaves the owner function exactly as the owner sweep admission change set left it.
 */
@Tag("database")
class ClientInputMigrationTest {

    private static final String CHANGELOG = "-client-input.xml";
    private static final long WORKSPACE = 979301;
    private static final long ACCOUNT = 979302;
    private static final String SWEEP = "SELECT pg_get_functiondef('public.person_row_sweep'::regproc)";
    private static final String EXPIRY = "SELECT person_row_sweep('CLIENT_INPUT_EXPIRY', NULL, NULL, now())::text";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldInstallTheReceiptsAndRollBackToThePreviousOwnerFunction(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "client_input_" + context + "_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        var sweepBefore = PracticeMigrationFixture.column(database, SWEEP);
        // The workspace is a plain id; only the account is a key.
        PracticeMigrationFixture.execute(
                database, "INSERT INTO account(id,display_name) VALUES(" + ACCOUNT + ",'Receipt owner')");

        try (var liquibase = liquibase(database)) {
            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);
            PracticeMigrationFixture.execute(database, """
                    INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id,created_at)
                    VALUES(%1$d,%2$d,gen_random_uuid(),sha256('old'::bytea),gen_random_uuid(),gen_random_uuid(),
                           now() - interval '9 days'),
                          (%1$d,%2$d,gen_random_uuid(),sha256('new'::bytea),gen_random_uuid(),gen_random_uuid(),now());
                    """.formatted(WORKSPACE, ACCOUNT));
            assertThat(PracticeMigrationFixture.column(database, EXPIRY))
                    .as("only the receipt older than eight days expires")
                    .containsExactly("1");

            PracticeMigrationFixture.rollbackOnly(liquibase, context, CHANGELOG);

            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT (to_regclass('public.client_input') IS NULL
                        AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'purge_workspace_client_inputs')
                        AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'workspace_client_input_purge'))::text
                    """))
                    .as("the table, its purge trigger and the trigger function are gone")
                    .containsExactly("true");
            assertThat(PracticeMigrationFixture.column(database, SWEEP))
                    .as("the owner function is the one the owner sweep admission change set installed")
                    .containsExactlyElementsOf(sweepBefore);
            assertThatThrownBy(() -> PracticeMigrationFixture.column(database, EXPIRY))
                    .hasMessageContaining("unknown rule CLIENT_INPUT_EXPIRY");

            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);
        }
        assertThat(PracticeMigrationFixture.column(database, EXPIRY))
                .as("the upgrade runs again after the rollback")
                .containsExactly("0");
    }

    /**
     * The application role may pass any cutoff. A future one still expires only receipts older than eight days, so a
     * retry inside the window cannot create twice. Each call removes at most one batch, which the cleanup's own batch
     * size relies on to tell a drained backlog from a remaining one.
     */
    @Test
    void shouldExpireAtMostOneBatchOfOldReceiptsWhateverTheCutoff() {
        var database = NativePracticeFixture.database("prod");
        PracticeMigrationFixture.execute(
                database, "INSERT INTO account(id,display_name) VALUES(" + ACCOUNT + ",'Receipt owner')");
        PracticeMigrationFixture.execute(database, """
                INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id,created_at)
                SELECT %1$d,%2$d,gen_random_uuid(),sha256('old'::bytea),gen_random_uuid(),gen_random_uuid(),
                       now() - interval '9 days'
                FROM generate_series(1,1001);
                INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id,created_at)
                VALUES(%1$d,%2$d,gen_random_uuid(),sha256('new'::bytea),gen_random_uuid(),gen_random_uuid(),
                       now() - interval '1 day');
                """.formatted(WORKSPACE, ACCOUNT));
        String future = "SELECT person_row_sweep('CLIENT_INPUT_EXPIRY', NULL, NULL, 'infinity')::text";

        assertThat(PracticeMigrationFixture.column(database, future))
                .as("one call removes one batch")
                .containsExactly("1000");
        assertThat(PracticeMigrationFixture.column(database, future))
                .as("the next call removes the rest of the old receipts and keeps the fresh one")
                .containsExactly("1");
        assertThat(PracticeMigrationFixture.column(database, "SELECT count(*)::text FROM client_input"))
                .containsExactly("1");
        assertThatThrownBy(() -> PracticeMigrationFixture.column(
                        database, "SELECT person_row_sweep('CLIENT_INPUT_EXPIRY', NULL, NULL, NULL)::text"))
                .hasMessageContaining("CLIENT_INPUT_EXPIRY needs the cutoff");
    }

    /**
     * A receipt that another transaction holds, as an erasure or a purge does, is skipped rather than waited on; the
     * next call removes it once the holder commits.
     */
    @Test
    void shouldSkipAReceiptThatAnotherTransactionHolds() throws Exception {
        var database = NativePracticeFixture.database("prod");
        PracticeMigrationFixture.execute(
                database, "INSERT INTO account(id,display_name) VALUES(" + ACCOUNT + ",'Receipt owner')");
        UUID held = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO client_input(workspace_id,account_id,idem_key,fingerprint,result_id,related_id,created_at)
                VALUES(%1$d,%2$d,'%3$s',sha256('held'::bytea),gen_random_uuid(),gen_random_uuid(),now() - interval '9 days'),
                      (%1$d,%2$d,'%4$s',sha256('free'::bytea),gen_random_uuid(),gen_random_uuid(),now() - interval '9 days');
                """.formatted(WORKSPACE, ACCOUNT, held, free));

        try (var holder = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var sweeper =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT 1 FROM client_input WHERE idem_key = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery().close();
            }
            try (var statement = sweeper.createStatement()) {
                statement.execute("SET lock_timeout = '2s'");
                try (var result = statement.executeQuery(EXPIRY)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1))
                            .as("the free receipt goes, the held one is skipped")
                            .isEqualTo("1");
                }
            }
            holder.commit();
        }

        assertThat(PracticeMigrationFixture.column(database, EXPIRY))
                .as("the next call removes the receipt once its holder committed")
                .containsExactly("1");
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        return new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
    }
}
