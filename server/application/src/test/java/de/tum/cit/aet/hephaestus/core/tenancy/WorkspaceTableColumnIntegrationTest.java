package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every table of the migrated schema that holds workspace data carries a {@code NOT NULL workspace_id}. Then a query
 * can always scope it, and the table can later be distributed by workspace. A table that belongs to no workspace is in
 * {@link WorkspaceScopedTables#GLOBAL_TABLES}, a security decision, or in {@link #GLOBAL_WITHOUT_ENTITY} if no entity
 * maps it. If such a table has a {@code workspace_id}, {@link #GLOBAL_NAMING_A_WORKSPACE} says why.
 * {@code docs/contributor/database-migration.mdx} § Workspace column gives the rule.
 */
class WorkspaceTableColumnIntegrationTest extends BaseIntegrationTest {

    /**
     * Tables that belong to no workspace and that no entity maps, so {@link WorkspaceScopedTables#GLOBAL_TABLES},
     * which must match the global entities, cannot list them.
     */
    private static final Set<String> GLOBAL_WITHOUT_ENTITY = Set.of(
            "account_public_activity_hidden_by_default",
            "auth_rate_limit_bucket",
            "shedlock",
            // The Spring Modulith event registry: the library owns its shape, and completed events are deleted.
            "event_publication");

    /** These nullable-workspace tables still require SQL tenancy enforcement. */
    private static final Map<String, String> MIXED_SCOPE_TABLES = Map.of(
            "agent_action",
            "workspace_id is NULL only for an instance-level action, such as an email to an account",
            "provider_event",
            "workspace_id is NULL only for a GitHub App installation event, which can arrive before its workspace",
            "config_audit_event",
            "workspace_id is NULL only for a curated practice change, which belongs to the instance",
            "product_feedback",
            "workspace_id is NULL only for product feedback sent outside a workspace",
            "llm_usage_event",
            "workspace_id is NULL only for instance PRODUCT_FEEDBACK_DRAFT or PROBE calls, enforced by ck_llm_usage_event_scope");

    /**
     * Global tables that still have a {@code workspace_id}, and why it does not make the row workspace data. SQL tenancy
     * enforcement does not check these tables, so the column gives them no isolation.
     */
    private static final Map<String, String> GLOBAL_NAMING_A_WORKSPACE = Map.of(
            "auth_event",
            "an account's audit trail; workspace_id only names the workspace of an event that has one",
            "github_installation_request",
            "an instance-level installation request; workspace_id only names the workspace an adoption created",
            "llm_call_permit",
            "instance-owned admission to shared provider capacity; workspace_id only orders fair grants, and is NULL"
                    + " only for a product feedback call",
            "workspace_slug_history",
            "a former slug resolves to its workspace before any workspace context exists");

    private static final String TABLES_WITHOUT_WORKSPACE_ID = """
            SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p') AND NOT c.relispartition
              AND NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'workspace_id'
                              AND a.attnotnull AND NOT a.attisdropped)
            ORDER BY c.relname
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceScopedTables workspaceScopedTables;

    @Test
    void shouldCarryNotNullWorkspaceIdInEveryWorkspaceTable() {
        List<String> missing = tablesWithoutWorkspaceId("public");

        assertThat(missing)
                .filteredOn(table -> !WorkspaceScopedTables.GLOBAL_TABLES.contains(table)
                        && !GLOBAL_WITHOUT_ENTITY.contains(table)
                        && !MIXED_SCOPE_TABLES.containsKey(table)
                        && !WorkspaceScopedTables.MIRROR_TABLES.contains(table))
                .as("These tables have no workspace_id, or a nullable one. Give each a NOT NULL workspace_id, or record"
                        + " an approved exception as docs/contributor/database-migration.mdx"
                        + " § Workspace column describes")
                .isEmpty();
        assertThat(MIXED_SCOPE_TABLES).allSatisfy((table, reason) -> {
            assertThat(missing).as(reason).contains(table);
            assertThat(workspaceScopedTables.isScoped(table))
                    .as("Mixed-scope table %s must retain SQL tenancy enforcement", table)
                    .isTrue();
        });
        assertThat(WorkspaceScopedTables.MIRROR_TABLES).allSatisfy(table -> {
            assertThat(missing)
                    .as("Mirror table %s must exist and carry no workspace_id (ADR 0077)", table)
                    .contains(table);
            assertThat(workspaceScopedTables.isScoped(table))
                    .as("Mirror table %s must remain under SQL tenancy enforcement", table)
                    .isTrue();
        });
    }

    @Test
    void shouldKeepEveryReferenceToAWorkspaceRowInsideItsWorkspace() {
        // A target with UNIQUE (id, workspace_id) lets a reference name both columns, so the database itself refuses a
        // row that points into another workspace. A single-column key on id alone would accept it.
        List<String> crossing = jdbc.queryForList("""
                WITH workspace_keyed AS (
                    SELECT i.indrelid AS target FROM pg_index i
                    WHERE i.indisunique AND i.indnkeyatts = 2
                      AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_attribute a
                           WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey::int2[]))
                          = ARRAY['id', 'workspace_id'])
                SELECT c.conrelid::regclass::text || '.' || col.attname FROM pg_constraint c
                JOIN pg_attribute col ON col.attrelid = c.conrelid AND col.attnum = c.conkey[1]
                JOIN pg_attribute ws ON ws.attrelid = c.conrelid AND ws.attname = 'workspace_id'
                                    AND ws.attnotnull AND NOT ws.attisdropped
                WHERE c.contype = 'f' AND cardinality(c.conkey) = 1 AND c.connamespace = 'public'::regnamespace
                  AND c.confrelid IN (SELECT target FROM workspace_keyed)
                  AND c.confkey[1] = (SELECT a.attnum FROM pg_attribute a
                                      WHERE a.attrelid = c.confrelid AND a.attname = 'id')
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint d
                                  WHERE d.contype = 'f' AND d.conrelid = c.conrelid AND d.confrelid = c.confrelid
                                    AND c.conkey[1] = ANY (d.conkey) AND ws.attnum = ANY (d.conkey))
                ORDER BY 1
                """, String.class).stream()
                .map(Objects::requireNonNull)
                .toList();

        assertThat(crossing)
                .as("These references from a workspace table name a workspace row by id alone. Add a foreign key on"
                        + " (column, workspace_id), as docs/contributor/database-migration.mdx § Workspace column"
                        + " describes")
                .isEmpty();
    }

    @Test
    void shouldGiveAReasonWhenAGlobalTableNamesAWorkspace() {
        List<String> naming = jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'workspace_id' AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                """, String.class).stream()
                .map(Objects::requireNonNull)
                .toList();

        assertThat(naming)
                .filteredOn(table ->
                        WorkspaceScopedTables.GLOBAL_TABLES.contains(table) || GLOBAL_WITHOUT_ENTITY.contains(table))
                .as("A global table with a workspace_id is a security decision. Record why in"
                        + " GLOBAL_NAMING_A_WORKSPACE, as docs/contributor/database-migration.mdx § Workspace column"
                        + " describes, or remove the column")
                .containsExactlyInAnyOrderElementsOf(GLOBAL_NAMING_A_WORKSPACE.keySet());
    }

    @Test
    void shouldReportATableWithoutWorkspaceIdOrWithANullableOne() {
        // A schema of its own, so the fixture never shows up in the check of the real schema. The owner creates it:
        // the application role cannot.
        JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();
        try {
            owner.execute("CREATE SCHEMA workspace_rule_fixture");
            owner.execute("CREATE TABLE workspace_rule_fixture.missing (id bigint PRIMARY KEY)");
            owner.execute("CREATE TABLE workspace_rule_fixture.nullable (id bigint PRIMARY KEY, workspace_id bigint)");
            owner.execute(
                    "CREATE TABLE workspace_rule_fixture.scoped (id bigint PRIMARY KEY, workspace_id bigint NOT NULL)");

            assertThat(tablesWithoutWorkspaceId("workspace_rule_fixture")).containsExactly("missing", "nullable");
        } finally {
            owner.execute("DROP SCHEMA IF EXISTS workspace_rule_fixture CASCADE");
        }
    }

    private List<String> tablesWithoutWorkspaceId(String schema) {
        return jdbc.queryForList(TABLES_WITHOUT_WORKSPACE_ID, String.class, schema).stream()
                .map(Objects::requireNonNull)
                .toList();
    }
}
