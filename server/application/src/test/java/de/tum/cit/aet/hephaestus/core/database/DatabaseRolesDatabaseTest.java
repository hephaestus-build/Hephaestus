package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The roles of the chart's {@code files/database-roles.sql}: the owner migrates, and the application role reads and
 * writes rows, owns nothing and holds no attribute that escapes a grant.
 */
@Tag("database")
class DatabaseRolesDatabaseTest {

    /** A fresh install: the roles first, then the owner migrates the empty database. */
    private static final TestDatabase INSTALLED = install();

    private static TestDatabase install() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_roles_installed");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_installed");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        return database;
    }

    @Test
    void shouldGiveNeitherRoleAnAttributeThatEscapesAGrantWhenTheRolesAreSetUp() throws SQLException {
        try (Connection connection = connect(INSTALLED)) {
            List<@Nullable String> escapes = jdbc(connection).queryForList("""
                            SELECT rolname FROM pg_roles
                            WHERE rolname IN ('hephaestus_owner', 'hephaestus_app')
                              AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
                            """, String.class);
            assertThat(escapes).isEmpty();
            assertThat(jdbc(connection)
                            .queryForObject(
                                    "SELECT pg_has_role('hephaestus_app', 'hephaestus_owner', 'MEMBER')",
                                    Boolean.class))
                    .isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE TABLE public.role_probe (id bigint)",
                "CREATE SCHEMA role_probe",
                "ALTER TABLE public.workspace ADD COLUMN role_probe bigint",
                "CREATE INDEX role_probe ON public.workspace (id)",
                "DROP TABLE public.workspace",
                "TRUNCATE public.workspace",
                "CREATE FUNCTION public.role_probe() RETURNS int LANGUAGE sql AS 'SELECT 1'",
                "CALL partman.run_maintenance_proc()",
                "SELECT rolpassword FROM pg_authid",
                "ALTER ROLE hephaestus_app BYPASSRLS",
            })
    void shouldRefuseTheApplicationRoleWhenItChangesTheSchemaOrReadsTheRoleCatalog(String statement)
            throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(INSTALLED))) {
            assertThatThrownBy(() -> jdbc(connection).execute(statement))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void shouldHoldTheApplicationRoleToRowLevelSecurityWhenATableEnablesIt() throws SQLException {
        try (Connection owner = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
            var asOwner = jdbc(owner);
            asOwner.execute("CREATE TABLE public.role_probe_rls (id bigint PRIMARY KEY, visible boolean NOT NULL)");
            asOwner.execute("ALTER TABLE public.role_probe_rls ENABLE ROW LEVEL SECURITY");
            asOwner.execute("CREATE POLICY visible_rows ON public.role_probe_rls USING (visible)");
            asOwner.execute("INSERT INTO public.role_probe_rls VALUES (1, true), (2, false)");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(INSTALLED))) {
            var asApplication = jdbc(connection);
            // The owner's default privileges gave the application role this new table's rows.
            assertThat(asApplication.queryForList("SELECT id FROM public.role_probe_rls", Long.class))
                    .containsExactly(1L);
            asApplication.execute("SET row_security = off");
            assertThatThrownBy(() -> asApplication.queryForList("SELECT id FROM public.role_probe_rls", Long.class))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void shouldLetTheApplicationRoleUseRowsAndKeepPartitionsWhenTheOwnerMigrated() throws SQLException {
        assertApplicationWorks(INSTALLED);
    }

    /** A database that the administrator migrated, as every installation before 1.0 did. */
    @Test
    void shouldMoveEveryObjectToTheOwnerWhenTheAdministratorMigratedTheDatabase() throws SQLException {
        TestDatabase database = PostgreSQLTestContainer.createMigratedDatabase("hephaestus_roles_moved");

        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_moved");

        try (Connection connection = connect(database)) {
            List<@Nullable String> elsewhere = jdbc(connection).queryForList("""
                            SELECT c.oid::regclass::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname IN ('public', 'partman') AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                              AND c.relowner <> 'hephaestus_owner'::regrole
                              AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                              AND d.objid = c.oid AND d.deptype = 'e')
                            UNION ALL
                            SELECT p.oid::regprocedure::text FROM pg_proc p
                            JOIN pg_namespace n ON n.oid = p.pronamespace
                            WHERE n.nspname IN ('public', 'partman') AND p.proowner <> 'hephaestus_owner'::regrole
                              AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass
                                              AND d.objid = p.oid AND d.deptype = 'e')
                            """, String.class);
            assertThat(elsewhere).isEmpty();
        }
        // The owner migrates it from now on: Liquibase writes its history and lock tables as the owner.
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        assertApplicationWorks(database);
    }

    private static void assertApplicationWorks(TestDatabase database) throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var asApplication = jdbc(connection);
            assertThat(asApplication.queryForObject("SELECT current_user", String.class))
                    .isEqualTo(PostgreSQLTestContainer.APP_USER);
            // The roles wait for the schema by reading the migration history.
            assertThat(asApplication.queryForObject("SELECT count(*) FROM databasechangelog", Long.class))
                    .isPositive();
            asApplication.execute("SELECT maintain_auth_event_partitions()");
            Long id = asApplication.queryForObject(
                    "INSERT INTO auth_event (id, event_type, result) VALUES (nextval('auth_event_id_seq'), 'LOGIN',"
                            + " 'SUCCESS') RETURNING id",
                    Long.class);
            assertThat(asApplication.queryForObject("SELECT count(*) FROM auth_event WHERE id = ?", Long.class, id))
                    .isEqualTo(1L);
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
