package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonTableRlsCompletenessTest extends BaseIntegrationTest {
    @Autowired
    private EntityManagerFactory entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldHoldEveryMarkedEntityToAnIndexedOwnerPolicyAndInvokerViews() {
        var tables = entities.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType())
                .filter(type -> type.isAnnotationPresent(PersonOwned.class))
                .map(type ->
                        Objects.requireNonNull(type.getAnnotation(Table.class)).name())
                .toList();
        assertThat(tables).isNotEmpty();
        tables.forEach(this::assertSecurity);
    }

    @Test
    void shouldRefuseAMissingPolicyIndexOrInvokerSecurity() {
        var owner = PostgreSQLTestContainer.ownerJdbc();
        owner.execute("CREATE TABLE public.person_policy_fixture (account_id bigint NOT NULL)");
        try {
            assertThatThrownBy(() -> assertSecurity("person_policy_fixture"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("owner policy");
            owner.execute("""
                ALTER TABLE public.person_policy_fixture ENABLE ROW LEVEL SECURITY;
                CREATE POLICY fixture_owner ON public.person_policy_fixture TO hephaestus_app
                    USING (account_id = public.current_owner_account())
                    WITH CHECK (account_id = public.current_owner_account())
                """);
            shouldRejectIncompletePolicy(owner);
            owner.execute("""
                CREATE INDEX fixture_partial_idx ON public.person_policy_fixture(account_id) WHERE account_id > 10
                """);
            assertFixtureRefused("owner index");
            owner.execute("""
                CREATE INDEX fixture_owner_idx ON public.person_policy_fixture(account_id);
                CREATE VIEW public.person_policy_fixture_view AS SELECT * FROM public.person_policy_fixture
                """);
            assertFixtureRefused("dependent views");
            owner.execute("ALTER VIEW public.person_policy_fixture_view SET (security_invoker = on)");
            assertSecurity("person_policy_fixture");
            owner.execute("""
                CREATE VIEW public.person_policy_fixture_nested_view AS SELECT * FROM public.person_policy_fixture_view
                """);
            assertFixtureRefused("dependent views");
            owner.execute("ALTER VIEW public.person_policy_fixture_nested_view SET (security_invoker = true)");
            assertSecurity("person_policy_fixture");
            owner.execute("CREATE POLICY fixture_read_all ON public.person_policy_fixture USING (true)");
            assertThatThrownBy(() -> assertSecurity("person_policy_fixture"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("owner policy");
        } finally {
            owner.execute("DROP TABLE public.person_policy_fixture CASCADE");
        }
    }

    private void shouldRejectIncompletePolicy(JdbcTemplate owner) {
        owner.execute("ALTER TABLE public.person_policy_fixture FORCE ROW LEVEL SECURITY");
        assertFixtureRefused("owner policy");
        owner.execute("ALTER TABLE public.person_policy_fixture NO FORCE ROW LEVEL SECURITY");
        owner.execute("ALTER POLICY fixture_owner ON public.person_policy_fixture TO PUBLIC");
        assertFixtureRefused("owner policy");
        owner.execute("ALTER POLICY fixture_owner ON public.person_policy_fixture TO hephaestus_app WITH CHECK (true)");
        assertFixtureRefused("owner policy");
        owner.execute("""
            ALTER POLICY fixture_owner ON public.person_policy_fixture
                WITH CHECK (account_id = public.current_owner_account())
            """);
    }

    private void assertFixtureRefused(String reason) {
        assertThatThrownBy(() -> assertSecurity("person_policy_fixture"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(reason);
    }

    private void assertSecurity(String table) {
        assertThat(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT FROM pg_class c JOIN pg_policy p ON p.polrelid = c.oid
                WHERE c.oid = to_regclass(?) AND c.relrowsecurity AND NOT c.relforcerowsecurity
                    AND p.polroles = ARRAY['hephaestus_app'::regrole::oid]
                    AND p.polcmd = '*' AND p.polpermissive
                    AND pg_get_expr(p.polqual, c.oid) = '(account_id = current_owner_account())'
                    AND pg_get_expr(p.polwithcheck, c.oid) = '(account_id = current_owner_account())'
                    AND (SELECT count(*) FROM pg_policy WHERE polrelid = c.oid) = 1
            )
            """, Boolean.class, "public." + table))
                .as("%s owner policy", table)
                .isTrue();

        assertThat(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                WHERE i.indrelid = to_regclass(?) AND a.attname = 'account_id'
                    AND i.indisvalid AND i.indpred IS NULL
            )
            """, Boolean.class, "public." + table))
                .as("%s owner index", table)
                .isTrue();

        assertThat(jdbc.queryForList("""
            WITH RECURSIVE dependents(oid) AS (
                SELECT to_regclass(?)::oid
                UNION
                SELECT dependent_view.oid
                FROM dependents parent
                JOIN pg_depend d ON d.refclassid = 'pg_class'::regclass AND d.refobjid = parent.oid
                JOIN pg_rewrite r ON d.classid = 'pg_rewrite'::regclass AND r.oid = d.objid
                JOIN pg_class dependent_view ON dependent_view.oid = r.ev_class AND dependent_view.relkind IN ('v', 'm')
            )
            SELECT v.relname FROM dependents JOIN pg_class v USING (oid)
            WHERE v.relkind = 'm' OR (v.relkind = 'v' AND NOT EXISTS (
                SELECT FROM pg_options_to_table(v.reloptions)
                WHERE option_name = 'security_invoker' AND CAST(option_value AS boolean)
            ))
            """, String.class, "public." + table))
                .as("%s dependent views can bypass the owner policy", table)
                .isEmpty();
    }
}
