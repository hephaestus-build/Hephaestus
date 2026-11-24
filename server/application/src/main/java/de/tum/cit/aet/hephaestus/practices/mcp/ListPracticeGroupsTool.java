package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupController;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code list_practice_groups}: the workspace's practice groups, as {@code PracticeGroupController.list} reads them. */
@Component
@McpToolFor(controller = PracticeGroupController.class, method = "list")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ListPracticeGroupsTool implements McpTool {

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["groups"], "additionalProperties": false,
             "properties": {"groups": {"type": "array", "items": {
               "type": "object", "required": ["slug", "name", "position", "shownToPeople", "feedbackMode"], "additionalProperties": false,
               "properties": {"slug": {"type": "string"}, "name": {"type": "string"}, "position": {"type": "integer"},
                              "shownToPeople": {"type": "boolean"}, "feedbackMode": %s}}}}}
            """.formatted(McpProjections.FEEDBACK_MODE_SCHEMA);

    private final PracticeGroupService groups;
    private final CatalogOriginPresenter presenter;
    private final McpSchema.Tool definition;

    ListPracticeGroupsTool(PracticeGroupService groups, CatalogOriginPresenter presenter, JsonMapper jsonMapper) {
        this.groups = groups;
        this.presenter = presenter;
        this.definition = McpTools.readTool(
                jsonMapper,
                "list_practice_groups",
                "List practice groups",
                "Lists the practice groups of this workspace with their position and feedback mode.",
                McpTools.NO_INPUT,
                OUTPUT_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        List<Group> rows = presenter.presentGroups(context.id(), groups.listGroups(context, null)).stream()
                .map(group -> new Group(
                        group.slug(),
                        group.name(),
                        group.position(),
                        group.shownToPeople(),
                        McpProjections.FeedbackMode.of(group.feedbackMode())))
                .toList();
        return McpTools.result(new Result(rows));
    }

    record Result(List<Group> groups) {}

    record Group(
            String slug, String name, int position, boolean shownToPeople, McpProjections.FeedbackMode feedbackMode) {}
}
