package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ConversationRowOwnerMigrationTest {
    private static final String CHANGELOG = "-conversation-row-owner.xml";
    private static final String CONVERSATION = "00000000-0000-0000-0000-000000979201";
    private static final String TURN = "00000000-0000-0000-0000-000000979202";
    private static final String ENTRY = "00000000-0000-0000-0000-000000979203";
    private static final String RUN = "00000000-0000-0000-0000-000000979206";
    private static final String OTHER_RUN = "00000000-0000-0000-0000-000000979207";
    private static final long OWNER = 979204;
    private static final long OTHER = 979205;

    @Test
    void shouldGiveEachEntryAndTurnItsConversationsAccountKeepThemEqualAndRollBack() throws Exception {
        String name = "conversation_row_owner_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979200, 'row-owner', 'Row owner', 'row-owner', 'ORG', 'ACTIVE');
                INSERT INTO account(id, display_name) VALUES (%2$d, 'Owner'), (%3$d, 'Other');
                INSERT INTO conversation(id, workspace_id, account_id, created_at, last_activity_at)
                VALUES ('%1$s', 979200, %2$d, now(), now());
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    available_at, stop_reason, created_at, ended_at)
                VALUES ('%6$s', 979200, 'TURN', 'INTERACTIVE', 'conversation:%1$s', 'ENDED', 0, 0, now(),
                    'COMPLETED', now(), now()),
                    ('%7$s', 979200, 'TURN', 'INTERACTIVE', 'conversation:%1$s', 'ENDED', 0, 0, now(),
                    'COMPLETED', now(), now());
                INSERT INTO conversation_turn(id, workspace_id, conversation_id, request_id, status, surface,
                    run_id, created_at)
                VALUES ('%4$s', 979200, '%1$s', 'row-owner', 'DONE', 'APP', '%6$s', now());
                INSERT INTO conversation_entry(id, workspace_id, conversation_id, seq, kind, surface, text,
                    turn_id, created_at)
                VALUES ('%5$s', 979200, '%1$s', 1, 'ANSWER', 'APP', 'An answer.', '%4$s', now());
                """.formatted(CONVERSATION, OWNER, OTHER, TURN, ENTRY, RUN, OTHER_RUN));

        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));

        assertThat(accounts(owner)).containsExactly(String.valueOf(OWNER), String.valueOf(OWNER));
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        INSERT INTO conversation_entry(id, workspace_id, conversation_id, account_id, seq, kind,
                            surface, text, created_at)
                        VALUES (gen_random_uuid(), 979200, '%s', %d, 2, 'ANSWER', 'APP', 'Foreign.', now())
                        """.formatted(CONVERSATION, OTHER)))
                .hasStackTraceContaining("sfk_conversation_entry_conversation");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(owner, """
                        INSERT INTO conversation_turn(id, workspace_id, conversation_id, account_id, request_id,
                            status, surface, run_id, created_at)
                        VALUES (gen_random_uuid(), 979200, '%s', %d, 'foreign', 'DONE', 'APP', '%s', now())
                        """.formatted(CONVERSATION, OTHER, OTHER_RUN)))
                .hasStackTraceContaining("sfk_conversation_turn_conversation");
        PracticeMigrationFixture.execute(
                owner, "UPDATE conversation SET account_id = %d WHERE id = '%s'".formatted(OTHER, CONVERSATION));
        assertThat(accounts(owner)).containsExactly(String.valueOf(OTHER), String.valueOf(OTHER));

        // A run that committed the change but stopped before recording it marks the change run when it starts again.
        PracticeMigrationFixture.execute(
                owner, "DELETE FROM databasechangelog WHERE filename LIKE '%" + CHANGELOG + "'");
        liquibase(owner, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(owner, """
                        SELECT id || ' ' || exectype FROM databasechangelog WHERE filename LIKE '%%%s' ORDER BY id
                        """.formatted(CHANGELOG)))
                .as("the function replacement runs again, as it is idempotent")
                .containsExactly("1795521600000-1 MARK_RAN", "1795521600000-2 EXECUTED");

        liquibase(owner, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name IN ('conversation_entry', 'conversation_turn') AND column_name = 'account_id'
                """)).containsExactly("0");
        assertThat(PracticeMigrationFixture.column(
                        owner, "SELECT count(*) FROM conversation_entry WHERE id = '%s'".formatted(ENTRY)))
                .containsExactly("1");
    }

    /**
     * The conversion seed runs on each release an upgrade can start from: the 1.0 baseline (v1.0.0-rc.1), a later release
     * before this change set, and a release that already has it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"baseline", "before", "after"})
    void shouldGiveTheConversionSeedsRowsTheirAccount(String release) throws Exception {
        String name = "conversation_row_seed_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        switch (release) {
            case "baseline" ->
                liquibase(
                        owner,
                        liquibase -> liquibase.update("1.0.0-baseline", new Contexts("prod"), new LabelExpression()));
            case "before" -> PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
            default -> PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        }
        PracticeMigrationFixture.execute(owner, seed("seed"));

        PostgreSQLTestContainer.migrateDatabase(owner, "prod");

        PracticeMigrationFixture.execute(owner, seed("expect"));
    }

    private static String seed(String part) throws Exception {
        try (var input = Objects.requireNonNull(ConversationRowOwnerMigrationTest.class.getResourceAsStream(
                "/db/cutover-seeds/conversation-row-owner." + part + ".sql"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The account of the entry, then of the turn. */
    private static List<@Nullable String> accounts(TestDatabase database) {
        return PracticeMigrationFixture.column(database, """
                SELECT account_id FROM conversation_entry WHERE id = '%s'
                UNION ALL SELECT account_id FROM conversation_turn WHERE id = '%s'
                """.formatted(ENTRY, TURN));
    }

    private interface Migration {
        void run(Liquibase liquibase) throws Exception;
    }

    private static void liquibase(TestDatabase database, Migration migration) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            migration.run(liquibase);
        }
    }
}
