package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class MembershipRoleSourceMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("membership_role_source");
    private static final String CHANGELOG = "db/changelog/next/20261111223352-provider-derived-roles.xml";

    @Test
    void shouldStartOwnersAsAPersonsRoleAndEveryOtherRoleFromTheProvider() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE membership (
                        workspace_id bigint NOT NULL,
                        account_id bigint NOT NULL,
                        role varchar(16) DEFAULT 'MEMBER' NOT NULL,
                        PRIMARY KEY (workspace_id, account_id)
                    )
                    """);
            statement.execute("INSERT INTO membership VALUES (1, 1, 'OWNER'), (1, 2, 'ADMIN'), (1, 3, 'MEMBER')");
            connection.commit();
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);

                assertThat(source(connection, 1)).isEqualTo("PERSON");
                assertThat(source(connection, 2)).isEqualTo("PROVIDER");
                assertThat(source(connection, 3)).isEqualTo("PROVIDER");
                assertThatThrownBy(() -> statement.execute(
                                "INSERT INTO membership (workspace_id, account_id, role) VALUES (1, 4, 'OWNER')"))
                        .hasMessageContaining("ck_membership_owner_role_source");
                database.rollback();

                liquibase.rollback(1, contexts, labels);
                try (var columns = connection.getMetaData().getColumns(null, null, "membership", "role_source")) {
                    assertThat(columns.next()).isFalse();
                }
            }
        }
    }

    private static String source(Connection connection, long accountId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT role_source FROM membership WHERE account_id = ?")) {
            statement.setLong(1, accountId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
