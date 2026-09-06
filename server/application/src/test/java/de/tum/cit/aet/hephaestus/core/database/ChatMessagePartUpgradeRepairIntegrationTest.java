package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The shape this repairs no longer exists in the schema, so the fixture builds the legacy tables the
 * way the release that shipped them did. What is asserted is the state changeset
 * {@code mentor-1071-drop-chat-message-part} demands before it will drop the table.
 */
@Tag("integration")
class ChatMessagePartUpgradeRepairIntegrationTest {

    private final ChatMessagePartUpgradeRepair repair = new ChatMessagePartUpgradeRepair();
    private final PostgreSQLContainer<?> postgres = PostgreSQLTestContainer.getInstance();

    private Connection open() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void runRepair() {
        DataSource dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        repair.postProcessBeforeInitialization(liquibase, "liquibase");
    }

    @BeforeEach
    void resetLegacyShape() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS chat_message_part");
            statement.execute("DROP TABLE IF EXISTS chat_message");
        }
    }

    @Test
    @DisplayName("a database with mentor history is left in the state the drop guard requires")
    void repairsAnInstallationThatStillHasParts() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chat_message (id UUID PRIMARY KEY)");
            statement.execute("CREATE TABLE chat_message_part (message_id UUID NOT NULL, order_index INT NOT NULL,"
                    + " content JSONB, original_type VARCHAR(64), type VARCHAR(32) NOT NULL)");
            statement.execute("INSERT INTO chat_message (id) VALUES ('11111111-1111-1111-1111-111111111111')");
            statement.execute(
                    "INSERT INTO chat_message_part (message_id, order_index, content, original_type, type) VALUES"
                            + " ('11111111-1111-1111-1111-111111111111', 1, '{\"text\":\"second\"}', NULL, 'TEXT'),"
                            + " ('11111111-1111-1111-1111-111111111111', 0, '{\"text\":\"first\"}', 'reasoning',"
                            + " 'REASONING')");
        }

        runRepair();

        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            // The guard counts rows in this table and raises on any; the drop proceeds only at zero.
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM chat_message_part")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            // What those rows carried survives on the message, ordered and typed the way the
            // changelog's own backfill writes it.
            try (ResultSet rows = statement.executeQuery("SELECT parts::text FROM chat_message")) {
                assertThat(rows.next()).isTrue();
                String parts = rows.getString(1);
                assertThat(parts).contains("first").contains("second").contains("reasoning");
                assertThat(parts.indexOf("first")).isLessThan(parts.indexOf("second"));
            }
        }
    }

    @Test
    @DisplayName("an installation without the legacy table is untouched")
    void doesNothingWhenThereIsNoLegacyTable() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chat_message (id UUID PRIMARY KEY)");
        }

        runRepair();

        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM information_schema.columns WHERE table_name = 'chat_message'"
                                + " AND column_name = 'parts'")) {
            // Nothing is added where there was nothing to migrate: a fresh install takes its schema
            // from the changelog alone.
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }
}
