package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsController;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code set_review_scope}: the repositories and people that Hephaestus reviews, as the review settings update saves them. */
@Component
@McpToolFor(controller = PracticeReviewSettingsController.class, method = "updatePracticeReviewSettings")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class SetReviewScopeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["etag", "reviewScope"], "additionalProperties": false,
             "properties": {
               %s,
               "reviewScope": {"type": "object", "required": ["repositories", "people", "selectedRepositories", "accountIds"], "additionalProperties": false,
                 "description": "The whole scope, in the shape that get_review_settings returns; it replaces the current one",
                 "properties": {
                   "repositories": {"enum": ["ALL", "SELECTED"]}, "people": {"enum": ["ALL", "SELECTED"]},
                   "selectedRepositories": {"type": "array", "items": {"type": "object", "required": ["repositoryItemId", "baseBranches"], "additionalProperties": false,
                     "properties": {"repositoryItemId": {"type": "string", "pattern": "^[1-9][0-9]*$", "description": "A repository item id from list_repositories"},
                                    "baseBranches": {"type": "array", "items": {"type": "string"}}}}},
                   "accountIds": {"type": "array", "items": {"type": "string", "pattern": "^[1-9][0-9]*$"}, "description": "Account ids from list_members"}}}}}
            """.formatted(ReviewSettingsChanges.ETAG_PROPERTY);

    private final ReviewSettingsChanges changes;
    private final McpSchema.Tool definition;

    SetReviewScopeTool(ReviewSettingsChanges changes, JsonMapper jsonMapper) {
        this.changes = changes;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "set_review_scope",
                        "Set the review scope",
                        "Replaces the repositories and people that Hephaestus reviews in this workspace. Before you change it,"
                                + " keep the reviewScope that get_review_settings returns: undo the change with this tool and"
                                + " that scope."),
                INPUT_SCHEMA,
                McpProjections.REVIEW_SETTINGS_SCHEMA,
                McpTools.Effect.CHANGES);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_REVIEW_SETTINGS")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        Map<String, Object> request = new HashMap<>();
        request.put("reviewScope", arguments.get("reviewScope"));
        return changes.apply(arguments, request);
    }
}
