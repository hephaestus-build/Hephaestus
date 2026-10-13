package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ledger rules as the Liquibase schema enforces them: the integration tier builds its schema from the JPA model,
 * which has no partial index and no CHECK constraint.
 */
@Tag("database")
class MentorLedgerSchemaLiquibaseTest {

    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createMigratedDatabase("mentor_ledger_test");

    private long workspaceId;
    private long userId;

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    @BeforeEach
    void seedOwner() throws SQLException {
        String key = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = connection()) {
            workspaceId = insertReturningId(
                    c,
                    "INSERT INTO workspace (account_login, account_type, display_name, slug, status) "
                            + "VALUES ('ledger', 'ORG', 'Ledger', ?, 'ACTIVE') RETURNING id",
                    "ledger-" + key);
            long providerId = insertReturningId(
                    c,
                    "INSERT INTO identity_provider (type, server_url) VALUES ('GITHUB', ?) RETURNING id",
                    "https://ledger-" + key + ".example");
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO \"user\" (native_id, provider_id, login) VALUES (?, ?, ?) RETURNING id")) {
                insert.setLong(1, providerId);
                insert.setLong(2, providerId);
                insert.setString(3, "ledger-" + key);
                try (ResultSet rs = insert.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    userId = rs.getLong(1);
                }
            }
        }
    }

    @Test
    void shouldRejectASecondMainConversationWhenTheDeveloperAlreadyHasOne() throws SQLException {
        try (Connection c = connection()) {
            conversation(c, null, null);
            assertThatThrownBy(() -> conversation(c, null, null)).hasMessageContaining("ux_mentor_conversation_main");
        }
    }

    @Test
    void shouldRejectAPartialSlackIdentityWhenOnlyTheUserIsSet() throws SQLException {
        try (Connection c = connection()) {
            UUID main = conversation(c, null, null);
            assertThatThrownBy(
                            () -> update(c, "UPDATE mentor_conversation SET slack_user_id = 'U1' WHERE id = ?", main))
                    .hasMessageContaining("chk_mentor_conversation_slack");
        }
    }

    @Test
    void shouldRejectASecondRunningTurnWhenTheConversationAlreadyRunsOne() throws SQLException {
        try (Connection c = connection()) {
            UUID main = conversation(c, null, null);
            turn(c, main, "RUNNING");
            assertThatCode(() -> turn(c, main, "QUEUED")).doesNotThrowAnyException();
            assertThatThrownBy(() -> turn(c, main, "RUNNING")).hasMessageContaining("ux_mentor_turn_running");
        }
    }

    @Test
    void shouldRejectAReusedRequestIdOrAnUnknownKindWhenAnEntryIsStored() throws SQLException {
        try (Connection c = connection()) {
            UUID main = conversation(c, null, null);
            entry(c, main, 1, "USER", "same-request");
            assertThatCode(() -> entry(c, main, 2, "USER", null)).doesNotThrowAnyException();
            assertThatCode(() -> entry(c, main, 3, "USER", null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> entry(c, main, 4, "USER", "same-request"))
                    .hasMessageContaining("ux_mentor_entry_request");
            assertThatThrownBy(() -> entry(c, main, 5, "REACTION", null)).hasMessageContaining("chk_mentor_entry_kind");
        }
    }

    @Test
    void shouldRejectASecondEntryAtOneSequenceNumberOrOneSlackMessageWhenTheConversationHasOne() throws SQLException {
        try (Connection c = connection()) {
            UUID main = conversation(c, null, null);
            UUID question = entry(c, main, 1, "USER", null);
            assertThatThrownBy(() -> entry(c, main, 1, "HEPH", null))
                    .hasMessageContaining("uk_mentor_entry_conversation_seq");

            update(c, "UPDATE mentor_entry SET slack_ts = '1700000000.000100' WHERE id = ?", question);
            UUID answer = entry(c, main, 2, "HEPH", null);
            assertThatThrownBy(() ->
                            update(c, "UPDATE mentor_entry SET slack_ts = '1700000000.000100' WHERE id = ?", answer))
                    .hasMessageContaining("uk_mentor_entry_conversation_slack_ts");
        }
    }

    @Test
    void shouldDeleteTheEntriesAndTurnsWhenTheConversationIsDeleted() throws SQLException {
        try (Connection c = connection()) {
            UUID main = conversation(c, null, null);
            UUID parent = entry(c, main, 1, "USER", null);
            UUID thread = conversation(c, main, parent);
            entry(c, thread, 1, "HEPH", null);
            turn(c, main, "QUEUED");

            update(c, "DELETE FROM mentor_conversation WHERE id = ?", main);

            try (PreparedStatement count =
                    c.prepareStatement("SELECT (SELECT count(*) FROM mentor_entry WHERE conversation_id IN (?, ?)) + "
                            + "(SELECT count(*) FROM mentor_turn WHERE conversation_id = ?) + "
                            + "(SELECT count(*) FROM mentor_conversation WHERE id = ?)")) {
                count.setObject(1, main);
                count.setObject(2, thread);
                count.setObject(3, main);
                count.setObject(4, thread);
                try (ResultSet rs = count.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isZero();
                }
            }
        }
    }

    private UUID conversation(Connection c, @Nullable UUID parentConversation, @Nullable UUID parentEntry)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO mentor_conversation (id, workspace_id, user_id, parent_conversation_id, parent_entry_id, "
                        + "created_at, last_activity_at) VALUES (?, ?, ?, ?, ?, now(), now())")) {
            insert.setObject(1, id);
            insert.setLong(2, workspaceId);
            insert.setLong(3, userId);
            insert.setObject(4, parentConversation);
            insert.setObject(5, parentEntry);
            insert.executeUpdate();
        }
        return id;
    }

    private UUID entry(Connection c, UUID conversation, long seq, String kind, @Nullable String requestId)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, request_id, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, 'APP', 'text', ?, now())")) {
            insert.setObject(1, id);
            insert.setLong(2, workspaceId);
            insert.setObject(3, conversation);
            insert.setLong(4, seq);
            insert.setString(5, kind);
            insert.setString(6, requestId);
            insert.executeUpdate();
        }
        return id;
    }

    private void turn(Connection c, UUID conversation, String status) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO mentor_turn (id, workspace_id, conversation_id, request_id, surface, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'APP', ?, now())")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setLong(2, workspaceId);
            insert.setObject(3, conversation);
            insert.setString(4, UUID.randomUUID().toString());
            insert.setString(5, status);
            insert.executeUpdate();
        }
    }

    private static void update(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private static long insertReturningId(Connection c, String sql, String value) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(sql)) {
            insert.setString(1, value);
            try (ResultSet rs = insert.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
