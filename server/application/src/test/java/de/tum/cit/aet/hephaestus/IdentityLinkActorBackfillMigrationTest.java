package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class IdentityLinkActorBackfillMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("identity_link_actor_backfill");

    @Test
    void shouldWireEachLinkToTheUserWithItsNumericIdWhenTheProviderMatches() throws Exception {
        execute("""
            CREATE TABLE auth_event (
                id bigint PRIMARY KEY,
                event_type varchar(64) NOT NULL,
                CONSTRAINT ck_auth_event_event_type CHECK (event_type IN ('LOGIN'))
            )
            """);
        execute("""
            CREATE TABLE "user" (
                id bigint PRIMARY KEY,
                provider_id bigint NOT NULL,
                native_id bigint NOT NULL,
                login varchar(255) NOT NULL
            )
            """);
        execute("""
            CREATE TABLE identity_link (
                id bigint PRIMARY KEY,
                provider_id bigint NOT NULL,
                subject varchar(255) NOT NULL,
                external_actor_id bigint
            )
            """);
        execute("""
            INSERT INTO "user" (id, provider_id, native_id, login) VALUES
                (10, 1, 777, 'alex'),
                (11, 2, 777, 'alex'),
                (12, 1, 888, 'renamed-login')
            """);
        execute("""
            INSERT INTO identity_link (id, provider_id, subject, external_actor_id) VALUES
                (1, 1, '777', NULL),
                (2, 2, '777', NULL),
                (3, 1, '888', 10),
                (4, 1, '999', NULL),
                (5, 3, 'U0777', NULL),
                (6, 1, '99999999999999999999', NULL)
            """);

        migrate();

        assertThat(wiring()).containsExactly("1=10", "2=11", "3=10", "4=null", "5=null", "6=null");
    }

    private static List<String> wiring() throws SQLException {
        var rows = new ArrayList<String>();
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT id, external_actor_id FROM identity_link ORDER BY id")) {
            while (result.next()) {
                rows.add(result.getLong("id") + "=" + result.getString("external_actor_id"));
            }
        }
        return rows;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate() throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect()));
        try (Liquibase liquibase = new Liquibase(
                "db/changelog/1789140289208_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(new Contexts());
        }
    }
}
