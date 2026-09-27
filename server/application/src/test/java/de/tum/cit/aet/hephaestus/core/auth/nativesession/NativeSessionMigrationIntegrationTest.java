package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scalar foreign keys are migration-owned; Hibernate-created integration schemas cannot prove them. */
@Tag("database")
class NativeSessionMigrationIntegrationTest {
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createMigratedDatabase("native_session_migration_test");

    @Test
    void shouldDeleteCredentialHistoryAndDeviceWhenItsSessionIsRemoved() throws Exception {
        try (Connection connection = connect()) {
            long account = insertAccount(connection);
            UUID session = insertSession(connection, account);
            UUID jti = insertHistory(connection, session);
            UUID device = insertDevice(connection, session, account);
            execute(connection, "DELETE FROM native_session WHERE id = ?", session);
            assertThat(count(connection, "SELECT count(*) FROM native_session_token WHERE jti = ?", jti))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM push_device WHERE id = ?", device))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM account WHERE id = ?", account))
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldRemoveOnlyTheErasedAccountsNativeRecords() throws Exception {
        try (Connection connection = connect()) {
            long erased = insertAccount(connection);
            UUID erasedSession = insertSession(connection, erased);
            UUID erasedHistory = insertHistory(connection, erasedSession);
            UUID erasedDevice = insertDevice(connection, erasedSession, erased);
            long retained = insertAccount(connection);
            UUID retainedSession = insertSession(connection, retained);
            UUID retainedHistory = insertHistory(connection, retainedSession);
            UUID retainedDevice = insertDevice(connection, retainedSession, retained);
            execute(connection, "DELETE FROM account WHERE id = ?", erased);
            assertThat(count(connection, "SELECT count(*) FROM native_session WHERE id = ?", erasedSession))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM native_session_token WHERE jti = ?", erasedHistory))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM push_device WHERE id = ?", erasedDevice))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM native_session_token WHERE jti = ?", retainedHistory))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM push_device WHERE id = ?", retainedDevice))
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldRejectCredentialHistoryForAMissingSession() throws Exception {
        try (Connection connection = connect()) {
            assertThatThrownBy(() -> insertHistory(connection, UUID.randomUUID()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("sfk_native_session_token_session");
        }
    }

    @Test
    void shouldRemoveOnlyThePurgedWorkspacesNotificationsAndCascadeDeviceErasure() throws Exception {
        try (Connection connection = connect()) {
            long account = insertAccount(connection);
            UUID session = insertSession(connection, account);
            UUID device = insertDevice(connection, session, account);
            long firstWorkspace = insertWorkspace(connection);
            long secondWorkspace = insertWorkspace(connection);
            long recipient = insertRecipient(connection);
            UUID first = insertNotification(connection, device, firstWorkspace, recipient);
            UUID second = insertNotification(connection, device, secondWorkspace, recipient);
            execute(connection, "DELETE FROM workspace WHERE id = ?", firstWorkspace);
            assertThat(count(connection, "SELECT count(*) FROM push_notification WHERE id = ?", first))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM push_notification WHERE id = ?", second))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM push_device WHERE id = ?", device))
                    .isEqualTo(1);
            execute(connection, "DELETE FROM native_session WHERE id = ?", session);
            assertThat(count(connection, "SELECT count(*) FROM push_notification WHERE id = ?", second))
                    .isZero();
        }
    }

    private static long insertWorkspace(Connection connection) throws SQLException {
        String slug = UUID.randomUUID().toString();
        return returningId(
                connection,
                "INSERT INTO workspace (account_login, account_type, display_name, slug, status, is_publicly_viewable)"
                        + " VALUES (?, 'ORG', 'Mobile migration', ?, 'ACTIVE', false) RETURNING id",
                slug,
                slug);
    }

    private static long insertRecipient(Connection connection) throws SQLException {
        long provider = returningId(
                connection,
                "INSERT INTO identity_provider (type, server_url) VALUES ('GITHUB', ?) RETURNING id",
                "https://" + UUID.randomUUID() + ".example.test");
        return returningId(
                connection,
                "INSERT INTO \"user\" (native_id, provider_id, login) VALUES (1, ?, 'mobile-test') RETURNING id",
                provider);
    }

    private static UUID insertNotification(Connection connection, UUID device, long workspace, long recipient)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                connection,
                "INSERT INTO push_notification (id, push_device_id, workspace_id, recipient_user_id, kind, window_start, state, attempts, next_attempt_at, created_at, version)"
                        + " VALUES (?, ?, ?, ?, 'PRACTICE_FEEDBACK', now(), 'PENDING', 0, now(), now(), 0)",
                id,
                device,
                workspace,
                recipient);
        return id;
    }

    private static long returningId(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertAccount(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "INSERT INTO account (display_name) VALUES ('Native migration test') RETURNING id")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static UUID insertSession(Connection connection, long account) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                connection,
                "INSERT INTO native_session (id, account_id, current_jti, auth_time, created_at, session_expires_at)"
                        + " VALUES (?, ?, ?, now(), now(), now() + interval '7 days')",
                id,
                account,
                UUID.randomUUID());
        return id;
    }

    private static UUID insertHistory(Connection connection, UUID session) throws SQLException {
        UUID jti = UUID.randomUUID();
        execute(
                connection,
                "INSERT INTO native_session_token (jti, session_id, refresh_token_hash) VALUES (?, ?, ?)",
                jti,
                session,
                jti.toString());
        return jti;
    }

    private static UUID insertDevice(Connection connection, UUID session, long account) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                connection,
                "INSERT INTO push_device (id, account_id, native_session_id, installation_id, expo_push_token, platform, created_at, updated_at, version)"
                        + " VALUES (?, ?, ?, ?, ?, 'IOS', now(), now(), 0)",
                id,
                account,
                session,
                id.toString(),
                "ExponentPushToken[" + id + "]");
        return id;
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql, Object value) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
