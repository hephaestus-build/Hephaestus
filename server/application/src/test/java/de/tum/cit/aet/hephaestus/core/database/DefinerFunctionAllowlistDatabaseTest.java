package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * A {@code SECURITY DEFINER} function runs with its owner's rights. Owned by {@code hephaestus_owner}, it reaches every
 * table the owner may use, and it passes the row policies of the owner's tables unless a table forces row-level
 * security. The chart's default privileges give {@code hephaestus_app} EXECUTE on every routine that
 * {@code hephaestus_owner} creates in {@code public}, so the server may call a definer function as soon as a migration
 * adds it. This list is the guard: each definer function that the application role can call is named here, owned by
 * the owner and closed to {@code PUBLIC}. Its search path is {@code pg_catalog, pg_temp}, or
 * {@code pg_catalog, public, pg_temp} when a trigger it fires names a table without a schema: a trigger runs with the
 * search path of the function that fires it. {@code DatabaseRolesDatabaseTest} proves that only the owner can create an
 * object in {@code public}.
 */
@Tag("database")
class DefinerFunctionAllowlistDatabaseTest {

    /** Every definer function the application role may call, each a narrow job that crosses a boundary on purpose. */
    private static final Set<String> ALLOWED = Set.of("public.maintain_auth_event_partitions()");

    /** A fresh install: the roles first, then the owner migrates the empty database. */
    private static final TestDatabase INSTALLED = install();

    private static TestDatabase install() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_definer_allowlist");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_definer_allowlist");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        return database;
    }

    @Test
    void shouldLetTheApplicationRoleCallOnlyListedOwnedPinnedAndClosedDefinerFunctions() throws SQLException {
        assertOnlyListedOwnedPinnedAndClosed(INSTALLED);
    }

    @Test
    void shouldAcceptPublicBetweenTheCatalogAndTemporaryObjects() throws SQLException {
        TestDatabase probe = PostgreSQLTestContainer.copyDatabase("hephaestus_definer_public_path", INSTALLED);
        try (Connection connection = connect(PostgreSQLTestContainer.asOwner(probe))) {
            jdbc(connection)
                    .execute("ALTER FUNCTION public.maintain_auth_event_partitions()"
                            + " SET search_path = pg_catalog, public, pg_temp");
        }

        assertOnlyListedOwnedPinnedAndClosed(probe);
    }

    /**
     * Each change breaks one property; the same assertion as the installed database must refuse it, for that property.
     * The unlisted function revokes the EXECUTE that PostgreSQL gives {@code PUBLIC} by default, so only the list
     * catches it.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "an unlisted function|unlisted|owner|definer functions the application role can call"
                        + "|CREATE FUNCTION public.definer_probe() RETURNS bigint LANGUAGE sql SECURITY DEFINER"
                        + " SET search_path = pg_catalog, pg_temp AS 'SELECT 1';"
                        + " REVOKE EXECUTE ON FUNCTION public.definer_probe() FROM PUBLIC",
                "a schema the application can write|writable_path|administrator|search path from pg_catalog to pg_temp"
                        + "|CREATE SCHEMA scratch; GRANT USAGE ON SCHEMA scratch TO hephaestus_owner;"
                        + " GRANT USAGE, CREATE ON SCHEMA scratch TO hephaestus_app;"
                        + " ALTER FUNCTION public.maintain_auth_event_partitions()"
                        + " SET search_path = pg_catalog, scratch, pg_temp",
                "temporary objects first|temp_first|owner|search path from pg_catalog to pg_temp"
                        + "|ALTER FUNCTION public.maintain_auth_event_partitions() SET search_path = pg_catalog, public",
                "a grant to PUBLIC|public|owner|PUBLIC may call"
                        + "|GRANT EXECUTE ON FUNCTION public.maintain_auth_event_partitions() TO PUBLIC",
                "another owner|owner_role|administrator|owner of"
                        + "|ALTER FUNCTION public.maintain_auth_event_partitions() OWNER TO CURRENT_USER",
            })
    void shouldRefuseADefinerFunctionThatBreaksTheRule(
            String change, String probeName, String role, String refusal, String statement) throws SQLException {
        TestDatabase probe = PostgreSQLTestContainer.copyDatabase("hephaestus_definer_" + probeName, INSTALLED);
        try (Connection connection = connect(role.equals("owner") ? PostgreSQLTestContainer.asOwner(probe) : probe)) {
            jdbc(connection).execute(statement);
        }

        assertThatThrownBy(() -> assertOnlyListedOwnedPinnedAndClosed(probe))
                .as(change)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(refusal);
    }

    /**
     * The definer functions that the application role can call by name: it may execute them and use their schema.
     * Functions that an extension owns, such as pg_partman's, belong to the extension's own contract and are left out.
     * Signatures name the function's schema. PostgreSQL's own schemas hold its built-in routines and are left out.
     */
    private static void assertOnlyListedOwnedPinnedAndClosed(TestDatabase database) throws SQLException {
        try (Connection connection = connect(database)) {
            var functions = jdbc(connection).queryForList("""
                            SELECT format('%I.%I(%s)', n.nspname, p.proname, pg_get_function_identity_arguments(p.oid))
                                       AS signature,
                                   pg_get_userbyid(p.proowner) AS owner,
                                   coalesce(p.proconfig && ARRAY['search_path=pg_catalog, pg_temp',
                                                                 'search_path=pg_catalog, public, pg_temp'], false)
                                       AS pinned,
                                   EXISTS (SELECT FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                                           WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE') AS public_calls
                            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                            WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                              AND has_function_privilege('hephaestus_app', p.oid, 'EXECUTE')
                              AND has_schema_privilege('hephaestus_app', n.oid, 'USAGE')
                              AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass
                                              AND d.objid = p.oid AND d.deptype = 'e')
                            """);
            Set<String> signatures = functions.stream()
                    .map(row -> Objects.toString(row.get("signature")))
                    .collect(Collectors.toSet());
            assertThat(signatures)
                    .as("definer functions the application role can call")
                    .isEqualTo(ALLOWED);
            assertThat(functions).allSatisfy(row -> {
                String signature = Objects.toString(row.get("signature"));
                assertThat(row.get("owner")).as("owner of %s", signature).isEqualTo("hephaestus_owner");
                assertThat(Boolean.TRUE.equals(row.get("pinned")))
                        .as("%s has a search path from pg_catalog to pg_temp through public at most", signature)
                        .isTrue();
                assertThat(Boolean.TRUE.equals(row.get("public_calls")))
                        .as("PUBLIC may call %s", signature)
                        .isFalse();
            });
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
