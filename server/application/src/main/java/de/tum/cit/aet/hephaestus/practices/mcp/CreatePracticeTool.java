package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogController;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code create_practice}: a new workspace practice, as {@code PracticeCatalogController.create} makes it. */
@Component
@McpToolFor(controller = PracticeCatalogController.class, method = "create")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class CreatePracticeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "kind", "definition"], "additionalProperties": false,
             "properties": {
               "slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
               "kind": {"type": "string", "description": "The item kind the practice reviews, as list_practices names it"},
               "definition": {"type": "object", "description": "A complete practice definition in the shape that get_practice returns"},
               "groupSlug": {"type": ["string", "null"]},
               "position": {"type": ["integer", "null"], "minimum": 0}}}
            """;

    private final PracticeService practices;
    private final CatalogOriginPresenter presenter;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final McpSchema.Tool definition;

    CreatePracticeTool(
            PracticeService practices, CatalogOriginPresenter presenter, JsonMapper jsonMapper, Validator validator) {
        this.practices = practices;
        this.presenter = presenter;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.validator = validator;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "create_practice",
                        "Create a practice",
                        "Creates a practice in this workspace from a complete definition, which the server checks as the web"
                                + " app's practice editor does. Undo it with remove_practice."),
                INPUT_SCHEMA,
                McpProjections.PRACTICE_SCHEMA,
                McpTools.Effect.ADDS);
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
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        // The request names both optional fields, as the web app sends them.
        Map<String, Object> request = new HashMap<>(arguments);
        request.putIfAbsent("groupSlug", null);
        request.putIfAbsent("position", null);
        var input = McpTools.input(jsonMapper, validator, request, CreatePracticeRequestDTO.class);
        return McpTools.result(
                McpProjections.Practice.of(presenter.present(context.id(), practices.createPractice(context, input))));
    }
}
