package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ConsentLedgerMigrationIntegrationTest {

    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createMigratedDatabase("consent_ledger_migration_test");

    /**
     * The digest is what the previous release writes on every consent decision, so this release has to
     * keep accepting one: an application-server replica running the previous image reaches this schema
     * during a rolling upgrade, and rolling the image back has to stay possible afterwards.
     */
    @Test
    void shouldStillAcceptTheDigestThePreviousReleaseWrites() throws Exception {
        try (Connection connection = connect()) {
            String archived = archivedDigest(connection, "2026-08-30");
            assertThat(archived)
                    .as("the archived notice is not dropped by this release")
                    .isNotNull();

            long decisionId = insertDecision(connection, insertAccount(connection), "2026-08-30", archived);

            assertThat(digestOf(connection, decisionId)).isEqualTo(archived);
        }
    }

    @Test
    void shouldRecordADecisionWithoutADigest() throws Exception {
        try (Connection connection = connect()) {
            long decisionId =
                    insertDecision(connection, insertAccount(connection), ConsentService.WORDING_VERSION, null);

            assertThat(digestOf(connection, decisionId)).isNull();
        }
    }

    @Test
    void shouldArchiveTheOrganisationTheQuestionNamed() throws Exception {
        try (Connection connection = connect()) {
            long decisionId = insertDecision(
                    connection, insertAccount(connection), ConsentService.WORDING_VERSION, null, "A research group");

            assertThat(columnOf(connection, "research_organization", decisionId))
                    .isEqualTo("A research group");
        }
    }

    @Test
    void shouldKeepTheLedgerAppendOnlyAndStillPermitErasure() throws Exception {
        try (Connection connection = connect()) {
            long accountId = insertAccount(connection);
            long decisionId = insertDecision(connection, accountId, ConsentService.WORDING_VERSION, null);

            assertThatThrownBy(() ->
                            execute(connection, "UPDATE consent_decision SET granted = false WHERE id = " + decisionId))
                    .hasMessageContaining("consent_decision is append-only");
            assertThatThrownBy(() -> execute(connection, "DELETE FROM consent_decision WHERE id = " + decisionId))
                    .hasMessageContaining("consent_decision is append-only");

            // Erasure clears the account and leaves the evidence that a decision was taken. The guard
            // compares the digest too, and `NULL = NULL` is NULL, so a decision recorded without one
            // is exactly the case a naive comparison would refuse.
            assertThatCode(() -> execute(
                            connection, "UPDATE consent_decision SET account_id = NULL WHERE id = " + decisionId))
                    .doesNotThrowAnyException();
            assertThat(noticeVersion(connection, decisionId)).isEqualTo(ConsentService.WORDING_VERSION);
        }
    }

    private static long insertAccount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "INSERT INTO account (display_name) VALUES ('Consent test') RETURNING id")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long insertDecision(Connection connection, long accountId, String version, @Nullable String digest)
            throws Exception {
        return insertDecision(connection, accountId, version, digest, null);
    }

    private static long insertDecision(
            Connection connection,
            long accountId,
            String version,
            @Nullable String digest,
            @Nullable String organisation)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO consent_decision "
                + "(account_id, purpose, granted, mechanism, notice_version, notice_sha256, "
                + "research_organization, occurred_at) "
                + "VALUES (?, 'RESEARCH_PARTICIPATION', true, 'FIRST_LOGIN_INTERSTITIAL', ?, ?, ?, now()) "
                + "RETURNING id")) {
            statement.setLong(1, accountId);
            statement.setString(2, version);
            statement.setString(3, digest);
            statement.setString(4, organisation);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static @Nullable String archivedDigest(Connection connection, String version) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT sha256 FROM consent_notice WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static @Nullable String digestOf(Connection connection, long decisionId) throws Exception {
        return columnOf(connection, "notice_sha256", decisionId);
    }

    private static @Nullable String noticeVersion(Connection connection, long decisionId) throws Exception {
        return columnOf(connection, "notice_version", decisionId);
    }

    private static @Nullable String columnOf(Connection connection, String column, long decisionId) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT " + column + " FROM consent_decision WHERE id = " + decisionId)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
