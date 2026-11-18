package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeDraftController;
import de.tum.cit.aet.hephaestus.practices.PracticeDraftService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code remove_practice_draft}: deletes a draft practice and its trials, as {@code PracticeDraftController.delete} does. */
@Component
@McpToolFor(controller = PracticeDraftController.class, method = "delete")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class RemovePracticeDraftTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug"], "additionalProperties": false,
             "properties": {"slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["removed"], "additionalProperties": false,
             "properties": {"removed": {"type": "string", "description": "The slug of the removed draft practice"}}}
            """;

    private final PracticeDraftService drafts;
    private final McpSchema.Tool definition;

    RemovePracticeDraftTool(PracticeDraftService drafts, JsonMapper jsonMapper) {
        this.drafts = drafts;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "remove_practice_draft",
                        "Remove a draft practice",
                        "Deletes a draft practice and its trials. A draft has no observation, so nothing else is"
                                + " lost. A practice in use is refused: remove it with remove_practice. Undo it with"
                                + " save_practice_draft and the same files."),
                INPUT_SCHEMA,
                OUTPUT_SCHEMA,
                McpTools.Effect.REMOVES);
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
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        String slug = McpTools.requiredString(arguments, "slug");
        drafts.delete(WorkspaceContextHolder.getContext(), slug);
        return McpTools.result(new Result(slug));
    }

    record Result(String removed) {}
}
