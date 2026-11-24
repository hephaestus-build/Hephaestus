package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
class DatabaseRoleGuardTest {
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "ALTER ROLE hephaestus_app BYPASSRLS | ALTER ROLE hephaestus_app NOBYPASSRLS",
                "ALTER ROLE hephaestus_app SUPERUSER | ALTER ROLE hephaestus_app NOSUPERUSER",
                "ALTER ROLE hephaestus_app CREATEROLE | ALTER ROLE hephaestus_app NOCREATEROLE",
                "ALTER ROLE hephaestus_app REPLICATION | ALTER ROLE hephaestus_app NOREPLICATION",
                "GRANT hephaestus_owner TO hephaestus_app WITH INHERIT FALSE | REVOKE hephaestus_owner FROM hephaestus_app"
            })
    void shouldRefuseTheApplicationLoginWhenItHasAnElevatedPrivilege(String grant, String revoke) {
        var administrator = jdbc(PostgreSQLTestContainer.administrator());
        var guard = new DatabaseRoleGuard(jdbc(PostgreSQLTestContainer.application()));
        guard.requireApplicationRole();
        administrator.execute(grant);
        try {
            assertRefuses(guard);
        } finally {
            administrator.execute(revoke);
        }
    }

    @Test
    void shouldRefuseAnUnelevatedLoginWithADifferentName() {
        var database = PostgreSQLTestContainer.administrator();
        var administrator = jdbc(database);
        String password = UUID.randomUUID().toString();
        administrator.execute("CREATE ROLE other_runtime_login LOGIN PASSWORD '%s'".formatted(password));
        try {
            var other = jdbc(new TestDatabase(database.jdbcUrl(), "other_runtime_login", password));
            assertThat(other.queryForObject("SELECT current_user", String.class))
                    .isEqualTo("other_runtime_login");
            assertRefuses(new DatabaseRoleGuard(other));
        } finally {
            administrator.execute("DROP ROLE other_runtime_login");
        }
    }

    @Test
    void shouldRefuseAnAdministratorThatConnectsWithTheApplicationRoleSelected() {
        var administrator = PostgreSQLTestContainer.administrator();
        var dataSource = new DriverManagerDataSource(
                administrator.jdbcUrl(), administrator.username(), administrator.password());
        var properties = new Properties();
        properties.setProperty("options", "-c role=hephaestus_app");
        dataSource.setConnectionProperties(properties);
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("hephaestus_app");
        assertRefuses(new DatabaseRoleGuard(jdbc));
    }

    @Test
    void shouldRefuseOwnershipOfTheDatabaseOrMembershipInTheRoleThatOwnsAnRlsTable() {
        String name = "person_role_ownership";
        var database = PostgreSQLTestContainer.createDatabase(name);
        var administrator = jdbc(database);
        var guard = new DatabaseRoleGuard(jdbc(PostgreSQLTestContainer.asApplication(database)));
        guard.requireApplicationRole();

        administrator.execute("ALTER DATABASE " + name + " OWNER TO hephaestus_app");
        try {
            assertRefuses(guard);
        } finally {
            administrator.execute("ALTER DATABASE " + name + " OWNER TO CURRENT_USER");
        }

        administrator.execute("CREATE ROLE row_policy_fixture_owner");
        try {
            administrator.execute("""
                    CREATE TABLE public.owned_policy_fixture (id bigint);
                    ALTER TABLE public.owned_policy_fixture ENABLE ROW LEVEL SECURITY;
                    ALTER TABLE public.owned_policy_fixture OWNER TO row_policy_fixture_owner;
                    GRANT row_policy_fixture_owner TO hephaestus_app WITH INHERIT FALSE
                    """);
            assertRefuses(guard);
        } finally {
            administrator.execute("""
                    DROP TABLE IF EXISTS public.owned_policy_fixture;
                    DROP ROLE row_policy_fixture_owner
                    """);
        }
    }

    private static void assertRefuses(DatabaseRoleGuard guard) {
        assertThatThrownBy(guard::requireApplicationRole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restricted hephaestus_app role");
    }

    private static JdbcTemplate jdbc(TestDatabase database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
    }
}
