package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogController;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** {@code remove_practice}: deletes a practice without review history, as {@code PracticeCatalogController.delete} does. */
@Component
@McpToolFor(controller = PracticeCatalogController.class, method = "delete")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class RemovePracticeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug"], "additionalProperties": false,
             "properties": {"slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["removed"], "additionalProperties": false,
             "properties": {"removed": {"type": "string", "description": "The slug of the removed practice"}}}
            """;

    private final PracticeService practices;
    private final McpSchema.Tool definition;

    RemovePracticeTool(PracticeService practices, JsonMapper jsonMapper) {
        this.practices = practices;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "remove_practice",
                        "Remove a practice",
                        "Deletes a practice that has no review history. A practice with review history cannot be deleted:"
                                + " set it to reviewed by nobody with update_practice instead. Undo it with"
                                + " adopt_catalog_practice or create_practice."),
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
        try {
            practices.deletePractice(WorkspaceContextHolder.getContext(), slug);
        } catch (ResponseStatusException refused) {
            if (refused.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This practice has recorded review history and cannot be deleted. Set it to reviewed by"
                                + " nobody with update_practice instead.",
                        refused);
            }
            throw refused;
        }
        return McpTools.result(new Result(slug));
    }

    record Result(String removed) {}
}
