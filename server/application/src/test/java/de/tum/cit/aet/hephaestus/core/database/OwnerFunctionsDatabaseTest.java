package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.tum.cit.aet.hephaestus.core.privacy.spi.DueWorkKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.TestDatabaseCleanup;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The owner functions reach every person's rows while row-level security hides them from the application role. The
 * seeded database turns row-level security on, with no policy, for the conversation tables and the account exports: the
 * application role then reads none of their rows, as under a policy for another owner. Each test calls one function
 * as the application role on its own copy and reads the outcome as the owner.
 */
@Tag("database")
class OwnerFunctionsDatabaseTest {

    private static final UUID ADA_MAIN = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID BO_MAIN = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID BO_ELSEWHERE = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final UUID ADA_OLD = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    private static final UUID ADA_NEW = UUID.fromString("00000000-0000-0000-0000-0000000e0002");
    private static final UUID BO_OLD = UUID.fromString("00000000-0000-0000-0000-0000000e0003");
    private static final UUID BO_ELSEWHERE_OLD = UUID.fromString("00000000-0000-0000-0000-0000000e0004");
    private static final UUID BO_TIE = UUID.fromString("00000000-0000-0000-0000-0000000e0005");
    private static final UUID BO_REDACTED = UUID.fromString("00000000-0000-0000-0000-0000000e0006");

    /**
     * Ada (11) and Bo (12, unclaimed) both have a main conversation in workspace 1, and Bo has one in workspace 2. Bo's
     * main in workspace 1 is bound to a Slack DM. Four entries are a year old, two of them written at the same time; one
     * is new, and one older entry is erased already. Each has a ready export past its expiry, and Ada has one that is still valid.
     */
    private static final String SEED = """
            INSERT INTO workspace (id, account_login, account_type, display_name, slug, status)
            VALUES (1, 'first-org', 'ORG', 'First', 'first-org', 'ACTIVE'),
                   (2, 'second-org', 'ORG', 'Second', 'second-org', 'ACTIVE');
            INSERT INTO account (id, display_name, status) VALUES (11, 'Ada', 'ACTIVE'), (12, 'Bo', 'UNCLAIMED'), (13, 'Cal', 'ACTIVE');
            INSERT INTO conversation (id, created_at, last_activity_at, account_id, workspace_id)
            VALUES ('00000000-0000-0000-0000-00000000a001', now(), now(), 11, 1),
                   ('00000000-0000-0000-0000-00000000b001', now(), now(), 12, 1),
                   ('00000000-0000-0000-0000-00000000b002', now(), now(), 12, 2);
            INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text, workspace_id,
                                            account_id)
            VALUES ('00000000-0000-0000-0000-0000000e0001', '00000000-0000-0000-0000-00000000a001',
                    now() - interval '366 days', 'USER', 1, 'APP', 'Ada, a year ago', 1, 11),
                   ('00000000-0000-0000-0000-0000000e0002', '00000000-0000-0000-0000-00000000a001',
                    now(), 'USER', 2, 'APP', 'Ada, today', 1, 11),
                   ('00000000-0000-0000-0000-0000000e0003', '00000000-0000-0000-0000-00000000b001',
                    now() - interval '365 days', 'USER', 1, 'APP', 'Bo, a year ago', 1, 12),
                   ('00000000-0000-0000-0000-0000000e0004', '00000000-0000-0000-0000-00000000b002',
                    now() - interval '364 days', 'USER', 1, 'APP', 'Bo elsewhere, a year ago', 2, 12),
                   ('00000000-0000-0000-0000-0000000e0005', '00000000-0000-0000-0000-00000000b002',
                    now() - interval '366 days', 'USER', 2, 'APP', 'Bo elsewhere, as old as Ada', 2, 12),
                   ('00000000-0000-0000-0000-0000000e0006', '00000000-0000-0000-0000-00000000b002',
                    now() - interval '400 days', 'USER', 3, 'APP', '', 2, 12);
            UPDATE conversation_entry SET redacted_at = now() WHERE id = '00000000-0000-0000-0000-0000000e0006';
            UPDATE conversation SET slack_team_id = 'T1', slack_user_id = 'U12', slack_channel_id = 'D12'
            WHERE id = '00000000-0000-0000-0000-00000000b001';
            INSERT INTO account_export (id, account_id, status, expires_at, payload)
            VALUES (21, 11, 'READY', now() - interval '1 hour', '\\x01'),
                   (22, 12, 'READY', now() - interval '1 hour', '\\x01'),
                   (23, 11, 'READY', now() + interval '1 hour', '\\x01');
            ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
            ALTER TABLE conversation_entry ENABLE ROW LEVEL SECURITY;
            ALTER TABLE conversation_turn ENABLE ROW LEVEL SECURITY;
            ALTER TABLE account_export ENABLE ROW LEVEL SECURITY;
            """;

    private static final TestDatabase SEEDED = seed();

    private static final String REFUSAL = "needs a positive age, a limit, a whole cursor or none, and whole owners";

    /** The start of the database's current retention week: 03:45 UTC on the last Sunday. */
    private static final String RETENTION_WEEK =
            "date_bin(interval '7 days', clock_timestamp(), timestamptz '2000-01-02 03:45:00+00')";

    private static TestDatabase seed() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_owner_functions");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_owner_functions");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            RowSweepAdmissionDatabaseFixture.installKey(connection);
            jdbc(connection).execute(SEED);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed the owner function database", e);
        }
        return database;
    }

    @Test
    void shouldNameTheDueEntriesOfEveryOwnerAPageAtATimeWhileRowLevelSecurityHidesTheirRows() throws SQLException {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_due", SEEDED);
        @Nullable Object firstDue;

        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            JdbcTemplate app = jdbc(connection);
            assertThat(app.queryForObject("SELECT count(*) FROM conversation_entry", Long.class))
                    .as("entries the application role reads itself")
                    .isZero();
            String page = "SELECT * FROM public.mentoring_due_work("
                    + "'ENTRY_RETENTION', 2592000, ?::timestamptz, ?::uuid, '{}', '{}', 1)";
            List<Map<String, @Nullable Object>> rows = new ArrayList<>();
            @Nullable Object[] cursor = {null, null};
            for (int i = 0; i < 10; i++) {
                List<Map<String, @Nullable Object>> one = app.queryForList(page, cursor);
                if (one.isEmpty()) {
                    break;
                }
                assertThat(one).as("a page holds at most its limit").hasSize(1);
                rows.add(one.getFirst());
                cursor = new @Nullable Object[] {
                    one.getFirst().get("due_at"), one.getFirst().get("id")
                };
            }
            assertThat(rows.getFirst().keySet())
                    .as("the due work names its rows and returns no content")
                    .containsExactly("kind", "id", "workspace_id", "account_id", "due_at");
            assertThat(rows)
                    .as("one row a page: the two entries written at the same time fall on two pages")
                    .extracting(row -> row.get("kind") + " " + row.get("id") + " " + row.get("workspace_id") + " "
                            + row.get("account_id"))
                    .containsExactly(
                            "ENTRY_RETENTION " + ADA_OLD + " 1 11",
                            "ENTRY_RETENTION " + BO_TIE + " 2 12",
                            "ENTRY_RETENTION " + BO_OLD + " 1 12",
                            "ENTRY_RETENTION " + BO_ELSEWHERE_OLD + " 2 12");
            firstDue = rows.getFirst().get("due_at");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(firstDue)
                    .as("due_at is when the entry was written")
                    .isEqualTo(jdbc(connection)
                            .queryForObject(
                                    "SELECT created_at FROM conversation_entry WHERE id = ?",
                                    Timestamp.class,
                                    ADA_OLD));
        }
    }

    @Test
    void shouldPassOverTheNamedOwnersAndKeepTheSameAccountInAnotherWorkspace() throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(SEEDED))) {
            assertThat(jdbc(connection)
                            .queryForList(
                                    "SELECT id FROM public.mentoring_due_work("
                                            + "'ENTRY_RETENTION', 2592000, NULL, NULL, '{1, 2}', '{11, 12}', 10)",
                                    UUID.class))
                    .as("Ada in the first workspace and Bo in the second are passed over, Bo in the first is not")
                    .containsExactly(BO_OLD);
        }
    }

    /**
     * The database clock decides which entries are due, by the week: an entry written just before the start of the
     * current retention week less the age is due, one written just after it is not, whatever the application's clock
     * says.
     */
    @Test
    void shouldNameAnEntryDueByTheDatabaseClockFromTheStartOfTheRetentionWeek() throws SQLException {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_week", SEEDED);
        UUID justDue = UUID.fromString("00000000-0000-0000-0000-0000000e0011");
        UUID justKept = UUID.fromString("00000000-0000-0000-0000-0000000e0012");
        String week;
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            JdbcTemplate owner = jdbc(connection);
            week = Objects.requireNonNull(
                    owner.queryForObject("SELECT CAST(" + RETENTION_WEEK + " AS text)", String.class));
            owner.update("""
                    INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text,
                                                    workspace_id, account_id)
                    VALUES (?, '00000000-0000-0000-0000-00000000a001', CAST(? AS timestamptz) - interval '2592001 seconds',
                            'USER', 3, 'APP', 'Ada, just due', 1, 11),
                           (?, '00000000-0000-0000-0000-00000000a001', CAST(? AS timestamptz) - interval '2591999 seconds',
                            'USER', 4, 'APP', 'Ada, just kept', 1, 11)
                    """, justDue, week, justKept, week);
        }

        List<@Nullable UUID> due;
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            due = jdbc(connection)
                    .queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 2592000, NULL, NULL, '{}', '{}', 10)",
                            UUID.class);
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            // A new retention week between the insert and the call moves the cutoff by seven days.
            assumeTrue(week.equals(
                    jdbc(connection).queryForObject("SELECT CAST(" + RETENTION_WEEK + " AS text)", String.class)));
        }
        assertThat(due).contains(justDue).doesNotContain(justKept);
    }

    @Test
    void shouldPurgeTheConversationsOfEveryOwnerInTheWorkspaceOnly() throws Exception {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_purge", SEEDED);

        sweepAsApplication(database, RowSweepRule.WORKSPACE_PURGE, 1, null);

        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            JdbcTemplate owner = jdbc(connection);
            assertThat(owner.queryForList("SELECT id FROM conversation ORDER BY id", UUID.class))
                    .containsExactly(BO_ELSEWHERE);
            assertThat(owner.queryForList("SELECT id FROM conversation_entry ORDER BY id", UUID.class))
                    .containsExactly(BO_ELSEWHERE_OLD, BO_TIE, BO_REDACTED);
        }
    }

    @Test
    void shouldExpireTheReadyExportsOfEveryOwnerPastTheirExpiry() throws SQLException {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_exports", SEEDED);

        sweepAsApplication(database, "SELECT public.person_row_sweep('EXPIRED_EXPORTS', NULL, NULL, now())");

        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            List<String> exports = jdbc(connection)
                    .query(
                            "SELECT id, status, payload IS NULL AS dropped FROM account_export ORDER BY id",
                            (rs, row) ->
                                    rs.getLong("id") + " " + rs.getString("status") + " " + rs.getBoolean("dropped"));
            assertThat(exports).containsExactly("21 EXPIRED true", "22 EXPIRED true", "23 READY false");
        }
    }

    @Test
    void shouldMergeTheAbsorbedMainAndMoveEveryOtherConversationToTheClaimingAccount() throws Exception {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_absorb", SEEDED);

        sweepAsApplication(database, RowSweepRule.ACCOUNT_ABSORB, 12, 11L);

        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            JdbcTemplate owner = jdbc(connection);
            assertThat(owner.query(
                            "SELECT id, account_id FROM conversation ORDER BY id",
                            (rs, row) -> rs.getObject("id") + " " + rs.getLong("account_id")))
                    .containsExactly(ADA_MAIN + " 11", BO_ELSEWHERE + " 11");
            assertThat(owner.query(
                            "SELECT id, seq, account_id FROM conversation_entry WHERE conversation_id = ? ORDER BY seq",
                            (rs, row) -> rs.getObject("id") + " " + rs.getLong("seq") + " " + rs.getLong("account_id"),
                            ADA_MAIN))
                    .as("the merged entry takes the claiming account")
                    .containsExactly(ADA_OLD + " 1 11", ADA_NEW + " 2 11", BO_OLD + " 3 11");
            assertThat(owner.queryForList(
                            "SELECT account_id FROM conversation_entry WHERE conversation_id = ?",
                            Long.class,
                            BO_ELSEWHERE))
                    .as("the entries of a conversation without a clash follow its account")
                    .hasSize(3)
                    .containsOnly(11L);
            assertThat(owner.queryForList("SELECT id FROM conversation WHERE id = ?", UUID.class, BO_MAIN))
                    .isEmpty();
            assertThat(owner.queryForObject(
                            "SELECT slack_team_id || ' ' || slack_user_id || ' ' || slack_channel_id FROM conversation"
                                    + " WHERE id = ?",
                            String.class,
                            ADA_MAIN))
                    .as("the Slack DM moves to the claiming main, which had none")
                    .isEqualTo("T1 U12 D12");
        }
    }

    @ParameterizedTest
    @MethodSource("rulesThatRequireLifecycleAdmission")
    void shouldRefuseEachLifecycleRuleWithoutAdmission(RowSweepRule rule) throws SQLException {
        var database = TestDatabaseCleanup.copyDatabase(
                "sweep_without_admission_" + rule.name().toLowerCase(Locale.ROOT), SEEDED);
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var app = jdbc(connection);
            switch (rule) {
                case WORKSPACE_PURGE -> assertRefused(() -> sweep(app, rule, 1L, null));
                case ACCOUNT_ABSORB -> assertRefused(() -> sweep(app, rule, 12L, 11L));
                default -> throw new AssertionError("Add an unsigned sweep test for " + rule);
            }
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(connection)
                            .query(
                                    "SELECT id, account_id FROM conversation ORDER BY id",
                                    (rs, row) -> rs.getObject("id") + " " + rs.getLong("account_id")))
                    .containsExactly(ADA_MAIN + " 11", BO_MAIN + " 12", BO_ELSEWHERE + " 12");
        }
    }

    private static Stream<RowSweepRule> rulesThatRequireLifecycleAdmission() {
        return Arrays.stream(RowSweepRule.values()).filter(RowSweepRule::requiresLifecycleAdmission);
    }

    @Test
    void shouldRefuseOrdinaryAppWritesAndAdmissionForADifferentWorkspaceOrTransaction() throws Exception {
        var database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_admission", SEEDED);
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var app = jdbc(connection);
            assertThatThrownBy(() -> app.queryForList("SELECT inner_pad FROM lifecycle_private.sweep_key"))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> app.update("UPDATE lifecycle_private.sweep_key SET inner_pad = outer_pad"))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied");
            app.queryForObject("SELECT set_config('hephaestus.sweep_admission', 'forged', false)", String.class);
            assertRefused(() -> sweep(app, RowSweepRule.WORKSPACE_PURGE, 1L, null));
            assertRefused(() -> sweep(app, RowSweepRule.ACCOUNT_ABSORB, 12L, 11L));

            connection.setAutoCommit(false);
            String proof = RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(database), RowSweepRule.WORKSPACE_PURGE, 1, null);
            assertRefused(() -> sweep(app, RowSweepRule.WORKSPACE_PURGE, 2L, null));
            connection.rollback();
            app.queryForObject("SELECT set_config('hephaestus.sweep_admission', ?, true)", String.class, proof);
            assertRefused(() -> sweep(app, RowSweepRule.WORKSPACE_PURGE, 1L, null));
            connection.rollback();

            RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(database), RowSweepRule.ACCOUNT_ABSORB, 12, 11L);
            assertRefused(() -> sweep(app, RowSweepRule.ACCOUNT_ABSORB, 12L, 13L));
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(connection).queryForList("SELECT id FROM conversation ORDER BY id", UUID.class))
                    .containsExactly(ADA_MAIN, BO_MAIN, BO_ELSEWHERE);
        }
    }

    /**
     * The approved purge sweep takes a personal space only once its owner's erasure suspended it (#5094). An active
     * personal space is refused, also with a valid approval, so no purge reaches a space that its owner still uses.
     */
    @Test
    void shouldSweepAPersonalSpaceOnlyOnceItsErasureSuspendedIt() throws Exception {
        var database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_personal", SEEDED);
        try (Connection owner = connect(PostgreSQLTestContainer.asOwner(database))) {
            jdbc(owner).execute("""
                    INSERT INTO workspace (id, account_login, account_type, display_name, slug, status, kind,
                        personal_account_id)
                    VALUES (3, 'me--aaaaaaaaaaaaaaac', 'USER', 'Personal space', 'me--aaaaaaaaaaaaaaac', 'ACTIVE',
                        'PERSONAL', 13);
                    INSERT INTO conversation (id, created_at, last_activity_at, account_id, workspace_id)
                    VALUES ('00000000-0000-0000-0000-00000000c003', now(), now(), 13, 3);
                    """);
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var app = jdbc(connection);
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(database), RowSweepRule.WORKSPACE_PURGE, 3, null);
            assertThatThrownBy(() -> sweep(app, RowSweepRule.WORKSPACE_PURGE, 3L, null))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("workspace is unavailable for purge");
            connection.rollback();
        }
        try (Connection owner = connect(PostgreSQLTestContainer.asOwner(database))) {
            jdbc(owner).update("UPDATE workspace SET status = 'SUSPENDED' WHERE id = 3");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var app = jdbc(connection);
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(database), RowSweepRule.WORKSPACE_PURGE, 3, null);
            sweep(app, RowSweepRule.WORKSPACE_PURGE, 3L, null);
            connection.commit();
        }
        try (Connection owner = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(owner).queryForList("SELECT id FROM conversation WHERE workspace_id = 3", UUID.class))
                    .isEmpty();
            assertThat(jdbc(owner).queryForList("SELECT id FROM conversation ORDER BY id", UUID.class))
                    .containsExactly(ADA_MAIN, BO_MAIN, BO_ELSEWHERE);
        }
    }

    @Test
    void shouldRefuseAnOldApprovalAfterTheDatabaseOwnerReplacesItsSigningKey() throws Exception {
        var database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_key_reset", SEEDED);
        try (Connection ownerConnection = connect(PostgreSQLTestContainer.asOwner(database));
                Connection appConnection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var owner = jdbc(ownerConnection);
            var app = jdbc(appConnection);
            appConnection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    appConnection, signingKey(database), RowSweepRule.WORKSPACE_PURGE, 1, null);

            ownerConnection.setAutoCommit(false);
            owner.update("DELETE FROM public.system_secret WHERE name = 'owner-sweep-admission'");
            owner.update("DELETE FROM lifecycle_private.sweep_key");
            ownerConnection.commit();
            ownerConnection.setAutoCommit(true);
            byte[] replacement = RowSweepAdmissionDatabaseFixture.installKey(ownerConnection);

            assertRefused(() -> sweep(app, RowSweepRule.WORKSPACE_PURGE, 1L, null));
            appConnection.rollback();
            RowSweepAdmissionDatabaseFixture.admit(appConnection, replacement, RowSweepRule.WORKSPACE_PURGE, 1, null);
            assertThat(sweep(app, RowSweepRule.WORKSPACE_PURGE, 1L, null)).isPositive();
            appConnection.commit();

            assertThat(owner.queryForList("SELECT id FROM conversation ORDER BY id", UUID.class))
                    .containsExactly(BO_ELSEWHERE);
        }
    }

    /**
     * A short write on an account, such as a changed display name, holds its row. A sweep page passes that account over
     * for this pass; the deadline margin still sees its due entry, or one write would read as a drained backlog.
     */
    @Test
    void shouldStillNameTheDueEntryOfAnAccountThatAWriteHoldsForTheDeadlineMargin() throws SQLException {
        var database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_held", SEEDED);
        try (Connection ownerConnection = connect(PostgreSQLTestContainer.asOwner(database));
                Connection appConnection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var owner = jdbc(ownerConnection);
            var app = jdbc(appConnection);
            ownerConnection.setAutoCommit(false);
            owner.update("UPDATE account SET display_name = 'Ada Lovelace' WHERE id = 11");

            String page = "SELECT id FROM public.mentoring_due_work(?, 2592000, NULL, NULL, '{}', '{}', 10)";
            assertThat(app.queryForList(page, UUID.class, DueWorkKind.ENTRY_RETENTION.name()))
                    .as("a sweep page passes over the held account")
                    .doesNotContain(ADA_OLD)
                    .contains(BO_OLD);
            assertThat(app.queryForList(page, UUID.class, DueWorkKind.ENTRY_RETENTION_DEADLINE.name()))
                    .as("the deadline margin reads it")
                    .containsExactly(ADA_OLD, BO_TIE, BO_OLD, BO_ELSEWHERE_OLD);
            ownerConnection.rollback();
        }
    }

    @Test
    void shouldKeepMaintenanceAwayFromAnAccountDuringAndAfterErasure() throws SQLException {
        var database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_erasure", SEEDED);
        try (Connection ownerConnection = connect(PostgreSQLTestContainer.asOwner(database));
                Connection appConnection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var owner = jdbc(ownerConnection);
            var app = jdbc(appConnection);
            ownerConnection.setAutoCommit(false);
            owner.update("UPDATE account SET status = 'DELETING', deleted_at = now() WHERE id = 12");

            assertThat(dueEntries(app)).containsExactly(ADA_OLD);
            assertThat(dueEntriesAfter(app, ADA_OLD)).isEmpty();
            assertThat(sweep(app, RowSweepRule.EXPIRED_EXPORTS, null, null)).isEqualTo(1);
            ownerConnection.commit();

            assertThat(dueEntries(app)).containsExactly(ADA_OLD);
            assertThat(sweep(app, RowSweepRule.EXPIRED_EXPORTS, null, null)).isZero();
            owner.update("UPDATE account SET status = 'DELETED' WHERE id = 12");
            ownerConnection.commit();

            assertThat(dueEntries(app)).containsExactly(ADA_OLD);
            assertThat(sweep(app, RowSweepRule.EXPIRED_EXPORTS, null, null)).isZero();
            assertThat(owner.queryForObject(
                            "SELECT status FROM account_export WHERE id = 22 AND payload IS NOT NULL", String.class))
                    .isEqualTo("READY");
        }
    }

    @Test
    void shouldRefuseAnAdmittedClaimWhenErasureWinsTheAccountLock() throws Exception {
        var database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_claim_race", SEEDED);
        try (Connection ownerConnection = connect(PostgreSQLTestContainer.asOwner(database));
                Connection appConnection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var owner = jdbc(ownerConnection);
            var app = jdbc(appConnection);
            ownerConnection.setAutoCommit(false);
            appConnection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    appConnection, signingKey(database), RowSweepRule.ACCOUNT_ABSORB, 12, 11L);
            int appPid = Objects.requireNonNull(app.queryForObject("SELECT pg_backend_pid()", Integer.class));
            owner.update("UPDATE account SET status = 'DELETING', deleted_at = now() WHERE id = 12");
            var claim = CompletableFuture.runAsync(
                    () -> assertThatThrownBy(() -> sweep(app, RowSweepRule.ACCOUNT_ABSORB, 12L, 11L))
                            .isInstanceOf(DataAccessException.class)
                            .rootCause()
                            .hasMessageContaining("only an unclaimed account is absorbed"));
            try {
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> Boolean.TRUE.equals(owner.queryForObject(
                                "SELECT pg_backend_pid() = ANY (pg_blocking_pids(?))", Boolean.class, appPid)));
            } finally {
                ownerConnection.commit();
                claim.get(10, TimeUnit.SECONDS);
            }
            assertThat(owner.queryForList("SELECT id FROM conversation WHERE account_id = 12 ORDER BY id", UUID.class))
                    .containsExactly(BO_MAIN, BO_ELSEWHERE);
        }
    }

    private static List<@Nullable UUID> dueEntries(JdbcTemplate app) {
        return app.queryForList(
                "SELECT id FROM public.mentoring_due_work('ENTRY_RETENTION', 2592000, NULL, NULL, '{}', '{}', 10)",
                UUID.class);
    }

    private static List<@Nullable UUID> dueEntriesAfter(JdbcTemplate app, UUID cursor) {
        return app.queryForList("""
                SELECT id FROM public.mentoring_due_work(
                    'ENTRY_RETENTION', 2592000,
                    (SELECT due_at FROM public.mentoring_due_work('ENTRY_RETENTION', 2592000, NULL, NULL, '{}', '{}', 1)),
                    ?::uuid, '{}', '{}', 10)
                """, UUID.class, cursor);
    }

    private static void assertRefused(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, refusal -> {
                    assertThat(refusal.getSQLState()).isEqualTo("55000");
                    assertThat(refusal.getMessage()).contains("lifecycle admission required");
                });
    }

    @Test
    void shouldRefuseAnUnknownKindOrRule() throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(SEEDED))) {
            JdbcTemplate app = jdbc(connection);
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work('SOMETHING_ELSE', 86400, NULL, NULL, '{}', '{}', 1)",
                            UUID.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("unknown kind SOMETHING_ELSE");
            assertThatThrownBy(() -> app.queryForObject(
                            "SELECT public.person_row_sweep('SOMETHING_ELSE', 1, NULL, NULL)", Long.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("unknown rule SOMETHING_ELSE");
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 86400, NULL, NULL, '{}', '{}', NULL)",
                            UUID.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining(REFUSAL);
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 86400, now(), NULL, '{}', '{}', 1)",
                            UUID.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining(REFUSAL);
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 0, NULL, NULL, '{}', '{}', 1)",
                            UUID.class))
                    .as("an age of nothing would make every entry due")
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining(REFUSAL);
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 86400, NULL, NULL, '{1}', '{}', 1)",
                            UUID.class))
                    .as("an owner is a workspace and an account")
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining(REFUSAL);
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 86400, NULL, NULL, '{1}', '{NULL}', 1)",
                            UUID.class))
                    .as("an owner without an account")
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining(REFUSAL);
            assertThatThrownBy(() -> app.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 86400, NULL, NULL, NULL, NULL, 1)",
                            UUID.class))
                    .as("no list of owners")
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining(REFUSAL);
        }
    }

    /** Every registered kind reaches its own branch: the seeded due entries come back. */
    @ParameterizedTest
    @EnumSource(DueWorkKind.class)
    void shouldAnswerEveryRegisteredKindThroughItsOwnBranch(DueWorkKind kind) throws SQLException {
        List<UUID> expected = switch (kind) {
            case ENTRY_RETENTION, ENTRY_RETENTION_DEADLINE -> List.of(ADA_OLD, BO_TIE, BO_OLD, BO_ELSEWHERE_OLD);
        };
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(SEEDED))) {
            assertThat(jdbc(connection)
                            .queryForList(
                                    "SELECT id FROM public.mentoring_due_work(?, 2592000, NULL, NULL, '{}', '{}', 10)",
                                    UUID.class,
                                    kind.name()))
                    .containsExactlyElementsOf(expected);
        }
    }

    /**
     * Every registered rule reaches its own branch. A purge requires admission, an expiry before any export changes
     * no row, and an account cannot be absorbed into itself.
     */
    @ParameterizedTest
    @EnumSource(RowSweepRule.class)
    void shouldAnswerEveryRegisteredRuleThroughItsOwnBranch(RowSweepRule rule) throws SQLException {
        var arguments = switch (rule) {
            case WORKSPACE_PURGE -> new @Nullable Object[] {rule.name(), -1L, null, null};
            case EXPIRED_EXPORTS -> new @Nullable Object[] {rule.name(), null, null, "-infinity"};
            case ACCOUNT_ABSORB -> new @Nullable Object[] {rule.name(), 11L, 11L, null};
        };
        var refusal = switch (rule) {
            case WORKSPACE_PURGE -> Optional.of("lifecycle admission required");
            case EXPIRED_EXPORTS -> Optional.<String>empty();
            case ACCOUNT_ABSORB -> Optional.of("ACCOUNT_ABSORB needs two different accounts");
        };
        String call = "SELECT public.person_row_sweep(?, ?::bigint, ?::bigint, ?::timestamptz)";
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(SEEDED))) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate app = jdbc(connection);
                if (refusal.isEmpty()) {
                    assertThat(app.queryForObject(call, Long.class, arguments)).isZero();
                } else {
                    assertThatThrownBy(() -> app.queryForObject(call, Long.class, arguments))
                            .isInstanceOf(DataAccessException.class)
                            .rootCause()
                            .hasMessageContaining(refusal.get());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void shouldRegisterEveryBranchOfTheInstalledFunctions() throws SQLException {
        assertBranchesRegistered(SEEDED);
    }

    @Test
    void shouldFindABranchThatOnlyTheDatabaseHas() throws SQLException {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_branch", SEEDED);
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            JdbcTemplate owner = jdbc(connection);
            String definition = Objects.requireNonNull(owner.queryForObject(
                    "SELECT pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.proname = 'mentoring_due_work'",
                    String.class));
            owner.execute(definition.replace(
                    "IF work_kind = 'ENTRY_RETENTION' THEN",
                    "IF work_kind = 'DATABASE_ONLY_V2' THEN RETURN; ELSIF work_kind = 'ENTRY_RETENTION' THEN"));
        }

        assertThatThrownBy(() -> assertBranchesRegistered(database))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DATABASE_ONLY_V2");
    }

    /**
     * The installed functions answer exactly the registered kinds and rules. Each branch tests its name as
     * {@code work_kind = 'NAME'} or {@code rule = 'NAME'}, so the branches are read from the functions' source.
     */
    private static void assertBranchesRegistered(TestDatabase database) throws SQLException {
        try (Connection connection = connect(database)) {
            JdbcTemplate jdbc = jdbc(connection);
            assertThat(branches(jdbc, "mentoring_due_work", "work_kind"))
                    .as("branches of mentoring_due_work")
                    .isEqualTo(names(DueWorkKind.values()));
            assertThat(branches(jdbc, "person_row_sweep", "rule"))
                    .as("branches of person_row_sweep")
                    .isEqualTo(names(RowSweepRule.values()));
        }
    }

    private static Set<String> branches(JdbcTemplate jdbc, String function, String parameter) {
        Pattern branch = Pattern.compile("\\b" + parameter + "\\s*=\\s*'([^']*)'");
        return jdbc
                .queryForList(
                        "SELECT prosrc FROM pg_proc WHERE proname = ? AND pronamespace = 'public'::regnamespace",
                        String.class,
                        function)
                .stream()
                .flatMap(source -> branch.matcher(source).results().map(match -> match.group(1)))
                .collect(Collectors.toSet());
    }

    private static Set<String> names(Enum<?>[] registered) {
        return Arrays.stream(registered).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    void shouldRefuseToAbsorbAnAccountIntoItselfAndKeepItsConversations() throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(SEEDED))) {
            assertThatThrownBy(() -> jdbc(connection)
                            .queryForObject(
                                    "SELECT public.person_row_sweep('ACCOUNT_ABSORB', 12, 12, NULL)", Long.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("two different accounts");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(SEEDED))) {
            assertThat(jdbc(connection)
                            .queryForList("SELECT id FROM conversation WHERE account_id = 12 ORDER BY id", UUID.class))
                    .containsExactly(BO_MAIN, BO_ELSEWHERE);
        }
    }

    @Test
    void shouldRefuseToAbsorbAnAccountThatIsNotUnclaimedAndKeepItsConversations() throws Exception {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(SEEDED))) {
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(SEEDED), RowSweepRule.ACCOUNT_ABSORB, 11, 12L);
            assertThatThrownBy(() -> jdbc(connection)
                            .queryForObject(
                                    "SELECT public.person_row_sweep('ACCOUNT_ABSORB', 11, 12, NULL)", Long.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("only an unclaimed account is absorbed");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(SEEDED))) {
            assertThat(jdbc(connection).queryForList("SELECT id FROM conversation WHERE account_id = 11", UUID.class))
                    .containsExactly(ADA_MAIN);
        }
    }

    @Test
    void shouldRefuseAClashThatTheCallerDidNotPrepareAndMoveNothing() throws Exception {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_unprepared", SEEDED);
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            jdbc(connection).execute("""
                    INSERT INTO conversation_entry (id, conversation_id, created_at, kind, seq, surface, text, workspace_id,
                                                    account_id)
                    VALUES ('00000000-0000-0000-0000-0000000e0007', '00000000-0000-0000-0000-00000000b001', now(),
                            'SUMMARY', 2, 'APP', 'Bo talked about tests', 1, 12)
                    """);
        }

        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(database), RowSweepRule.ACCOUNT_ABSORB, 12, 11L);
            assertThatThrownBy(() -> jdbc(connection)
                            .queryForObject(
                                    "SELECT public.person_row_sweep('ACCOUNT_ABSORB', 12, 11, NULL)", Long.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("is not prepared");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            assertThat(jdbc(connection)
                            .queryForList("SELECT id FROM conversation WHERE account_id = 12 ORDER BY id", UUID.class))
                    .containsExactly(BO_MAIN, BO_ELSEWHERE);
        }
    }

    @Test
    void shouldRefuseAClashWhoseQueuedRunTheCallerDidNotMoveAndMoveNothing() throws Exception {
        TestDatabase database = TestDatabaseCleanup.copyDatabase("hephaestus_owner_functions_queued", SEEDED);
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            jdbc(connection).execute("""
                    INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                                           available_at, start_within_seconds, created_at)
                    VALUES ('00000000-0000-0000-0000-0000000a0001', 1, 'TURN', 'INTERACTIVE',
                            'conversation:00000000-0000-0000-0000-00000000b001', 'QUEUED', 0, 0, now(), 180, now());
                    INSERT INTO conversation_turn (id, conversation_id, created_at, request_id, status, surface,
                                                   workspace_id, run_id, account_id)
                    VALUES ('00000000-0000-0000-0000-0000000c0001', '00000000-0000-0000-0000-00000000b001', now(),
                            'queued-question', 'QUEUED', 'APP', 1, '00000000-0000-0000-0000-0000000a0001', 12);
                    """);
        }

        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(
                    connection, signingKey(database), RowSweepRule.ACCOUNT_ABSORB, 12, 11L);
            assertThatThrownBy(() -> jdbc(connection)
                            .queryForObject(
                                    "SELECT public.person_row_sweep('ACCOUNT_ABSORB', 12, 11, NULL)", Long.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("is not prepared");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            JdbcTemplate owner = jdbc(connection);
            assertThat(owner.queryForObject(
                            "SELECT conversation_id FROM conversation_turn WHERE id = '00000000-0000-0000-0000-0000000c0001'",
                            UUID.class))
                    .isEqualTo(BO_MAIN);
            assertThat(owner.queryForList("SELECT id FROM conversation WHERE account_id = 12 ORDER BY id", UUID.class))
                    .containsExactly(BO_MAIN, BO_ELSEWHERE);
        }
    }

    @Test
    void shouldRefuseBothFunctionsToARoleWithoutTheGrant() throws SQLException {
        try (Connection connection = connect(SEEDED)) {
            JdbcTemplate stranger = jdbc(connection);
            stranger.execute("DO $$ BEGIN CREATE ROLE owner_function_stranger; EXCEPTION WHEN duplicate_object THEN"
                    + " NULL; END $$");
            stranger.execute("SET ROLE owner_function_stranger");
            assertThatThrownBy(() -> stranger.queryForList(
                            "SELECT id FROM public.mentoring_due_work("
                                    + "'ENTRY_RETENTION', 86400, NULL, NULL, '{}', '{}', 1)",
                            UUID.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> stranger.queryForObject(
                            "SELECT public.person_row_sweep('EXPIRED_EXPORTS', NULL, NULL, now())", Long.class))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied");
            stranger.execute("RESET ROLE");
        }
    }

    private static void sweepAsApplication(TestDatabase database, String call) throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            Long changed = jdbc(connection).queryForObject(call, Long.class);
            assertThat(Objects.requireNonNull(changed))
                    .as("rows the sweep changed")
                    .isPositive();
        }
    }

    private static int sweep(JdbcTemplate app, RowSweepRule rule, @Nullable Long subject, @Nullable Long other) {
        return Objects.requireNonNull(app.queryForObject(
                "SELECT public.person_row_sweep(?, ?::bigint, ?::bigint, now())",
                Integer.class,
                rule.name(),
                subject,
                other));
    }

    private static byte[] signingKey(TestDatabase database) throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(database))) {
            return RowSweepAdmissionDatabaseFixture.installKey(connection);
        }
    }

    private static void sweepAsApplication(TestDatabase database, RowSweepRule rule, long subject, @Nullable Long other)
            throws Exception {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            connection.setAutoCommit(false);
            RowSweepAdmissionDatabaseFixture.admit(connection, signingKey(database), rule, subject, other);
            assertThat(sweep(jdbc(connection), rule, subject, other)).isPositive();
            connection.commit();
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
