package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class PerPersonResultsMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("per_person_results");
    private static final String CHANGELOG = "db/changelog/next/20261118031120-per-person-results-for-admins.xml";

    @Test
    void shouldKeepPerPersonResultsWhereReviewsRanAndRecordWhy() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("CREATE TABLE workspace (id bigint PRIMARY KEY, practices_enabled boolean NOT NULL)");
            statement.execute("""
                    CREATE TABLE config_audit_event (
                        id bigserial PRIMARY KEY,
                        occurred_at timestamp with time zone DEFAULT now() NOT NULL,
                        workspace_id bigint,
                        actor_kind varchar(16) NOT NULL,
                        actor_account_id bigint,
                        acting_account_id bigint,
                        entity_type varchar(48) NOT NULL,
                        entity_id varchar(64) NOT NULL,
                        action varchar(16) NOT NULL,
                        changed_keys text[] NOT NULL,
                        old_value text,
                        new_value text,
                        elevated_via_instance_admin boolean DEFAULT false NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE review (id uuid PRIMARY KEY, workspace_id bigint NOT NULL)");
            // 1 ran reviews; 2 turned practices on but never ran one; 3 ran reviews and later turned practices off.
            statement.execute("INSERT INTO workspace VALUES (1, true), (2, true), (3, false), (4, false)");
            statement.execute("INSERT INTO review VALUES (gen_random_uuid(), 1), (gen_random_uuid(), 3)");
            connection.commit();
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts("prod"), new LabelExpression());

                assertThat(enabled(connection, 1)).isTrue();
                assertThat(enabled(connection, 2)).isFalse();
                assertThat(enabled(connection, 3)).isTrue();
                assertThat(enabled(connection, 4)).isFalse();
                assertThat(audit(connection))
                        .containsExactlyInAnyOrder(
                                "1 SYSTEM WORKSPACE_VISIBILITY CREATED {\"perPersonResultsForAdmins\":true,\"keptFromBefore\":true}",
                                "3 SYSTEM WORKSPACE_VISIBILITY CREATED {\"perPersonResultsForAdmins\":true,\"keptFromBefore\":true}");
            }
        }
    }

    private static boolean enabled(Connection connection, long workspaceId) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT per_person_results_for_admins FROM workspace WHERE id = ?")) {
            statement.setLong(1, workspaceId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getBoolean(1);
            }
        }
    }

    private static List<String> audit(Connection connection) throws SQLException {
        var rows = new ArrayList<String>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT workspace_id, actor_kind, entity_type, action, new_value FROM config_audit_event")) {
            while (result.next()) {
                rows.add(result.getLong(1) + " " + result.getString(2) + " " + result.getString(3) + " "
                        + result.getString(4) + " " + result.getString(5));
            }
        }
        return rows;
    }
}
