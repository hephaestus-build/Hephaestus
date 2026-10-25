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
 * maps it. {@code docs/contributor/database-migration.mdx} § Workspace column gives the rule.
 */
class WorkspaceTableColumnIntegrationTest extends BaseIntegrationTest {

    /**
     * Tables that belong to no workspace and that no entity maps, so {@link WorkspaceScopedTables#GLOBAL_TABLES},
     * which must match the global entities, cannot list them.
     */
    private static final Set<String> GLOBAL_WITHOUT_ENTITY =
            Set.of("auth_rate_limit_bucket", "consent_notice", "shedlock");

    private static final String MIRROR =
            "a provider mirror that reaches its workspace through its repository or" + " organization";
    private static final String OPTIONAL = "workspace_id is NULL for feedback or a survey outside a workspace";

    /** Tables from before this rule, with how they reach their workspace. Do not add to this list. */
    private static final Map<String, String> BEFORE_THE_RULE = Map.ofEntries(
            Map.entry("commit_contributor", MIRROR),
            Map.entry("commit_file_change", MIRROR),
            Map.entry("commit_pull_request", MIRROR),
            Map.entry("discussion", MIRROR),
            Map.entry("discussion_category", MIRROR),
            Map.entry("discussion_comment", MIRROR),
            Map.entry("discussion_label", MIRROR),
            Map.entry("git_commit", MIRROR),
            Map.entry("issue", MIRROR),
            Map.entry("issue_assignee", MIRROR),
            Map.entry("issue_blocking", MIRROR),
            Map.entry("issue_comment", MIRROR),
            Map.entry("issue_label", MIRROR),
            Map.entry("label", MIRROR),
            Map.entry("milestone", MIRROR),
            Map.entry("organization_membership", MIRROR),
            Map.entry("project", MIRROR),
            Map.entry("project_field", MIRROR),
            Map.entry("project_field_value", MIRROR),
            Map.entry("project_item", MIRROR),
            Map.entry("project_status_update", MIRROR),
            Map.entry("pull_request_closing_issue", MIRROR),
            Map.entry("pull_request_requested_reviewers", MIRROR),
            Map.entry("pull_request_requested_team", MIRROR),
            Map.entry("pull_request_review", MIRROR),
            Map.entry("pull_request_review_comment", MIRROR),
            Map.entry("pull_request_review_thread", MIRROR),
            Map.entry("repository", MIRROR),
            Map.entry("repository_collaborator", MIRROR),
            Map.entry("team", MIRROR),
            Map.entry("team_membership", MIRROR),
            Map.entry("team_repository_permission", MIRROR),
            Map.entry("config_audit_event", "workspace_id is NULL only for a curated practice change"),
            Map.entry("product_feedback", OPTIONAL),
            Map.entry("product_survey", OPTIONAL),
            Map.entry("product_survey_email_invitation", OPTIONAL),
            Map.entry("product_survey_participation", OPTIONAL),
            Map.entry("product_survey_submission", OPTIONAL),
            Map.entry("connection_audit", "reaches its workspace through its connection"),
            Map.entry("event_publication", "the Spring Modulith event registry; a payload can name a workspace"));

    private static final String TABLES_WITHOUT_WORKSPACE_ID = """
            SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p') AND NOT c.relispartition
              AND NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'workspace_id'
                              AND a.attnotnull AND NOT a.attisdropped)
            ORDER BY c.relname
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldCarryNotNullWorkspaceIdInEveryWorkspaceTable() {
        List<String> missing = tablesWithoutWorkspaceId("public");

        assertThat(missing)
                .filteredOn(table -> !WorkspaceScopedTables.GLOBAL_TABLES.contains(table)
                        && !GLOBAL_WITHOUT_ENTITY.contains(table)
                        && !BEFORE_THE_RULE.containsKey(table))
                .as("These tables have no workspace_id, or a nullable one. Give each a NOT NULL workspace_id, or list a"
                        + " table that belongs to no workspace as docs/contributor/database-migration.mdx"
                        + " § Workspace column describes")
                .isEmpty();
        assertThat(BEFORE_THE_RULE.keySet())
                .filteredOn(table -> !missing.contains(table))
                .as("These tables now carry a NOT NULL workspace_id or no longer exist. Remove them from"
                        + " BEFORE_THE_RULE")
                .isEmpty();
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
