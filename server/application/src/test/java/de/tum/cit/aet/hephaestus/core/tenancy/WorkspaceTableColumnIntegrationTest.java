package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
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
            "consumer_dead_letter",
            "Installation lifecycle failures can arrive before a workspace exists",
            "config_audit_event",
            "workspace_id is NULL only for a curated practice change, which belongs to the instance",
            "product_feedback",
            "workspace_id is NULL only for product feedback sent outside a workspace");

    /**
     * Global tables that still have a {@code workspace_id}, and why it does not make the row workspace data. SQL tenancy
     * enforcement does not check these tables, so the column gives them no isolation.
     */
    private static final Map<String, String> GLOBAL_NAMING_A_WORKSPACE = Map.of(
            "auth_event",
            "an account's audit trail; workspace_id only names the workspace of an event that has one",
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
        // A schema of its own, so the fixture never shows up in the check of the real schema.
        try {
            jdbc.execute("CREATE SCHEMA workspace_rule_fixture");
            jdbc.execute("CREATE TABLE workspace_rule_fixture.missing (id bigint PRIMARY KEY)");
            jdbc.execute("CREATE TABLE workspace_rule_fixture.nullable (id bigint PRIMARY KEY, workspace_id bigint)");
            jdbc.execute(
                    "CREATE TABLE workspace_rule_fixture.scoped (id bigint PRIMARY KEY, workspace_id bigint NOT NULL)");

            assertThat(tablesWithoutWorkspaceId("workspace_rule_fixture")).containsExactly("missing", "nullable");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS workspace_rule_fixture CASCADE");
        }
    }

    private List<String> tablesWithoutWorkspaceId(String schema) {
        return jdbc.queryForList(TABLES_WITHOUT_WORKSPACE_ID, String.class, schema).stream()
                .map(Objects::requireNonNull)
                .toList();
    }
}
