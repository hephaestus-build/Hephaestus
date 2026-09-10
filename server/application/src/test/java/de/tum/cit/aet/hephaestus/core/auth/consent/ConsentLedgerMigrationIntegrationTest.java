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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ConsentLedgerMigrationIntegrationTest {

    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createMigratedDatabase("consent_ledger_migration_test");

    @Test
    void ledgerIsAppendOnlyAndSurvivesErasure() throws Exception {
        try (Connection connection = connect()) {
            long accountId = insertAccount(connection);
            long decisionId = insertDecision(connection, accountId);

            assertThatThrownBy(() ->
                            execute(connection, "UPDATE consent_decision SET granted = false WHERE id = " + decisionId))
                    .hasMessageContaining("consent_decision is append-only");
            assertThatThrownBy(() -> execute(connection, "DELETE FROM consent_decision WHERE id = " + decisionId))
                    .hasMessageContaining("consent_decision is append-only");

            // Erasure clears the account and leaves the evidence that a decision was taken.
            assertThatCode(() -> execute(
                            connection, "UPDATE consent_decision SET account_id = NULL WHERE id = " + decisionId))
                    .doesNotThrowAnyException();
            assertThat(noticeVersion(connection, decisionId)).isEqualTo(ConsentService.CURRENT_NOTICE_VERSION);
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

    private static long insertDecision(Connection connection, long accountId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO consent_decision "
                + "(account_id, purpose, granted, mechanism, notice_version, occurred_at) "
                + "VALUES (?, 'RESEARCH_PARTICIPATION', true, 'FIRST_LOGIN_INTERSTITIAL', ?, now()) "
                + "RETURNING id")) {
            statement.setLong(1, accountId);
            statement.setString(2, ConsentService.CURRENT_NOTICE_VERSION);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String noticeVersion(Connection connection, long decisionId) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT notice_version FROM consent_decision WHERE id = " + decisionId)) {
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
