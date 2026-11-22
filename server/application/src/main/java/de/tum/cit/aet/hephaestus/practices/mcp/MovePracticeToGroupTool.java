package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupPlacementController;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.dto.BindPracticeGroupRequestDTO;
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

/**
 * {@code move_practice_to_group}: the group a practice belongs to, as {@code PracticeGroupPlacementController.bind}
 * sets it.
 */
@Component
@McpToolFor(controller = PracticeGroupPlacementController.class, method = "bind")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class MovePracticeToGroupTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "groupSlug"], "additionalProperties": false,
             "properties": {
               "slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
               "groupSlug": {"type": ["string", "null"], "description": "A group slug from list_practice_groups, or null for no group"}}}
            """;

    private final PracticeGroupService groups;
    private final CatalogOriginPresenter presenter;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final McpSchema.Tool definition;

    MovePracticeToGroupTool(
            PracticeGroupService groups, CatalogOriginPresenter presenter, JsonMapper jsonMapper, Validator validator) {
        this.groups = groups;
        this.presenter = presenter;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.validator = validator;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "move_practice_to_group",
                        "Move a practice to a group",
                        "Moves a practice into a practice group, or out of every group with null. It lands at the end of"
                                + " the group. Undo it with this tool and the earlier groupSlug from get_practice."),
                INPUT_SCHEMA,
                McpProjections.PRACTICE_SCHEMA,
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
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        Map<String, Object> request = new HashMap<>();
        request.put("groupSlug", arguments.get("groupSlug"));
        var input = McpTools.input(jsonMapper, validator, request, BindPracticeGroupRequestDTO.class);
        var moved = groups.bindPractice(context, McpTools.requiredString(arguments, "slug"), input.groupSlug());
        return McpTools.result(McpProjections.Practice.of(presenter.present(context.id(), moved)));
    }
}
